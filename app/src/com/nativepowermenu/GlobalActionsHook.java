package com.nativepowermenu;

import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Pair;
import android.util.SparseArray;

import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Replaces the vendor power menu with the AOSP one and applies settings coming from the app.
 *
 * <p>Injection point: {@code com.android.systemui.globalactions.GlobalActionsImpl#showGlobalActions}
 * on ColorOS. HyperOS may load a different implementation from the MIUI plugin APK, so hook the
 * host {@code GlobalActionsComponent} before it dispatches to either implementation.
 *
 * <p>When the module's master switch is off nothing is swallowed, so the stock menu comes back
 * without rebooting the device.
 */
final class GlobalActionsHook {

    private static final String CLASS_GLOBAL_ACTIONS_IMPL =
            "com.android.systemui.globalactions.GlobalActionsImpl";
    private static final String METHOD_SHOW_GLOBAL_ACTIONS = "showGlobalActions";
    private static final String CLASS_GLOBAL_ACTIONS_COMPONENT =
            "com.android.systemui.globalactions.GlobalActionsComponent";
    /** {@code StatusBarManager.DISABLE2_GLOBAL_ACTIONS}. */
    private static final int DISABLE2_GLOBAL_ACTIONS = 0x8;

    /** Delay before killing SystemUI, so the ordered broadcast result reaches the settings app. */
    private static final long RESTART_DELAY_MS = 700L;

    private final ClassLoader mClassLoader;
    private final boolean mHyperOs;

    private PowerMenuDialog mDialog;
    private boolean mReceiverInstalled;

    GlobalActionsHook(ClassLoader classLoader, boolean hyperOs) {
        mClassLoader = classLoader;
        mHyperOs = hyperOs;
    }

    void install() {
        if (mHyperOs) {
            if (!installHyperOsHooks()) {
                return;
            }
        } else {
            installDefaultHook();
        }
        installApplyReceiver();
    }

    private void installDefaultHook() {
        Class<?> impl = XposedHelpers.findClass(CLASS_GLOBAL_ACTIONS_IMPL, mClassLoader);

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                impl, METHOD_SHOW_GLOBAL_ACTIONS, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length != 1) {
                                return;
                            }
                            Context context = (Context) XposedHelpers.getObjectField(
                                    param.thisObject, "mContext");
                            if (!PowerMenuConfig.get(context).enabled) {
                                // Master switch off: let ColorOS show its own menu.
                                ModuleLog.d("module is switched off, keeping the stock power menu");
                                return;
                            }
                            if (XposedHelpers.getBooleanField(param.thisObject, "mDisabled")) {
                                ModuleLog.d("global actions are disabled by the platform");
                                param.setResult(null);
                                return;
                            }
                            // Never let the vendor implementation run: it would inflate the ColorOS
                            // shutdown view. Swallow it first so a crash in our code cannot fall
                            // back to the unwanted UI.
                            param.setResult(null);
                            handleShow(context, param.thisObject, param.args[0]);
                        } catch (Throwable t) {
                            ModuleLog.e("handling showGlobalActions failed", t);
                        }
                    }
                });

        int count = unhooks == null ? 0 : unhooks.size();
        if (count == 0) {
            ModuleLog.w("no " + METHOD_SHOW_GLOBAL_ACTIONS + " method found on "
                    + CLASS_GLOBAL_ACTIONS_IMPL + " - nothing was hooked");
        } else {
            ModuleLog.d("installed " + count + " hook(s) on "
                    + CLASS_GLOBAL_ACTIONS_IMPL + "#" + METHOD_SHOW_GLOBAL_ACTIONS);
        }
    }

    private boolean installHyperOsHooks() {
        Class<?> component = XposedHelpers.findClassIfExists(
                CLASS_GLOBAL_ACTIONS_COMPONENT, mClassLoader);
        if (component == null) {
            ModuleLog.w("HyperOS: no GlobalActionsComponent, keeping the stock power menu");
            return false;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object manager = param.thisObject;
                    Object keyguardViewManager = XposedHelpers.getObjectField(
                            manager, "mStatusBarKeyguardViewManager");
                    Context context = (Context) XposedHelpers.getObjectField(
                            keyguardViewManager, "mContext");
                    if (!PowerMenuConfig.get(context).enabled) {
                        return;
                    }
                    int displayId = (Integer) XposedHelpers.callMethod(context, "getDisplayId");
                    if (areHyperOsGlobalActionsDisabled(manager, displayId)) {
                        param.setResult(null);
                        return;
                    }
                    Object keyguardController = XposedHelpers.getObjectField(
                            keyguardViewManager, "mKeyguardStateController");
                    boolean keyguardShowing = Reflect.booleanValue(
                            keyguardController, "mShowing", "isShowing", true);
                    boolean provisioned = Settings.Global.getInt(context.getContentResolver(),
                            Settings.Global.DEVICE_PROVISIONED, 0) != 0;
                    // Suppress vendor dispatch only once the replacement has handled the request.
                    if (getDialog(context).toggle(
                            manager, keyguardController, keyguardShowing, provisioned)) {
                        param.setResult(null);
                        ModuleLog.d("HyperOS: handled " + param.method.getName()
                                + " (keyguardShowing=" + keyguardShowing + ")");
                    }
                } catch (Throwable t) {
                    ModuleLog.e("HyperOS: could not show the replacement power menu", t);
                }
            }
        };
        int count = 0;
        for (String name : new String[]{
                "handleShowGlobalActionsMenu", "handleShowOrHideGlobalActionsMenu"}) {
            Method method = XposedHelpers.findMethodExactIfExists(component, name);
            if (method != null && method.getReturnType() == void.class) {
                XposedBridge.hookMethod(method, hook);
                count++;
                ModuleLog.d("HyperOS: hooked " + CLASS_GLOBAL_ACTIONS_COMPONENT + "#" + name);
            }
        }
        if (count == 0) {
            ModuleLog.w("HyperOS: no compatible global actions callback, keeping the stock menu");
        }
        return count > 0;
    }

    private static boolean areHyperOsGlobalActionsDisabled(Object manager, int displayId) {
        Object commandQueue = XposedHelpers.getObjectField(manager, "mCommandQueue");
        SparseArray<?> disabled = (SparseArray<?>) XposedHelpers.getObjectField(
                commandQueue, "mDisplayDisabled");
        Pair<?, ?> flags = (Pair<?, ?>) disabled.get(displayId);
        return flags != null && ((Integer) flags.second & DISABLE2_GLOBAL_ACTIONS) != 0;
    }

    private void handleShow(Context context, Object globalActionsImpl, Object manager) {
        Object keyguardController = XposedHelpers.getObjectField(
                globalActionsImpl, "mKeyguardStateController");
        Object provisionController = XposedHelpers.getObjectField(
                globalActionsImpl, "mDeviceProvisionedController");

        // KeyguardStateController is a minimal interface on ColorOS: mShowing only exists as a
        // field on the Impl, so read tolerantly through both shapes.
        boolean keyguardShowing = Reflect.booleanValue(
                keyguardController, "mShowing", "isShowing", false);
        boolean deviceProvisioned = Reflect.booleanValue(
                provisionController, null, "isDeviceProvisioned", true);

        ModuleLog.d("power menu requested (keyguardShowing=" + keyguardShowing
                + ", deviceProvisioned=" + deviceProvisioned + ")");

        getDialog(context).toggle(manager, keyguardController, keyguardShowing, deviceProvisioned);
    }

    private synchronized PowerMenuDialog getDialog(Context context) {
        if (mDialog == null) {
            mDialog = new PowerMenuDialog(
                    context, new PowerMenuActions(context, mClassLoader, mHyperOs), mClassLoader);
        }
        return mDialog;
    }

    // ---------------------------------------------------------------- settings receiver

    /**
     * Listens for the settings app's "apply" broadcast. A Context is not available at hook time on
     * every ROM, so this first tries the current Application and otherwise waits for
     * {@code Application#onCreate}.
     */
    private void installApplyReceiver() {
        Context application = currentApplication();
        if (application != null) {
            onApplicationReady(application);
            return;
        }
        ModuleLog.d("application not ready yet, the apply receiver will be installed on onCreate");
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", mClassLoader, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onApplicationReady((Context) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            ModuleLog.e("could not hook Application#onCreate for the apply receiver", t);
        }
    }

    private void onApplicationReady(Context context) {
        registerApplyReceiver(context);
        // Re-assert the stored long-press timeout: it lives in system_server, which outlives us.
        pushLongPressTimeout(PowerMenuConfig.get(context));
    }

    private static Context currentApplication() {
        try {
            return AndroidAppHelper.currentApplication();
        } catch (Throwable t) {
            return null;
        }
    }

    private synchronized void registerApplyReceiver(Context context) {
        if (mReceiverInstalled) {
            return;
        }
        mReceiverInstalled = true;
        try {
            IntentFilter filter = new IntentFilter(PowerMenuConfig.ACTION_APPLY);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    PowerMenuConfig config = PowerMenuConfig.apply(receiverContext, intent);
                    if (config == null) {
                        return;
                    }
                    // Takes effect immediately - the timeout lives in system_server, so it does not
                    // have to wait for SystemUI to come back.
                    int frameworkDefault = pushLongPressTimeout(config);
                    // Confirm delivery so the settings app can tell the user what happened, and hand
                    // it the timeout the framework itself uses (0 when system_server has not seen a
                    // power key press yet, since only it can observe that value).
                    Bundle result = new Bundle();
                    result.putInt(PowerMenuConfig.EXTRA_FRAMEWORK_DEFAULT_MS,
                            Math.max(0, frameworkDefault));
                    setResultExtras(result);
                    setResultCode(PowerMenuConfig.RESULT_APPLIED);
                    // A silent re-sync (sent when the app is opened) only persists the values; the
                    // menu picks them up on the next long press because the cache was invalidated.
                    if (intent.getBooleanExtra(PowerMenuConfig.EXTRA_RESTART, true)) {
                        restartSystemUi();
                    }
                }
            };
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? Context.RECEIVER_EXPORTED : 0;
            // The permission is declared by the module and only its own signature holds it, so no
            // other app can restart SystemUI through this channel.
            context.registerReceiver(receiver, filter,
                    PowerMenuConfig.APPLY_PERMISSION, null, flags);
            ModuleLog.d("apply receiver installed");
        } catch (Throwable t) {
            mReceiverInstalled = false;
            ModuleLog.e("could not register the apply receiver", t);
        }
    }

    private int pushLongPressTimeout(PowerMenuConfig config) {
        // HyperOS's master switch restores both stock dispatch and stock power-key timing.
        int timeout = mHyperOs && !config.enabled ? 0 : config.longPressMs;
        return SystemClient.pushLongPressTimeout(timeout);
    }

    /** SystemUI is a persistent app: killing it makes the system start it again immediately. */
    private static void restartSystemUi() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ModuleLog.d("restarting SystemUI to apply the new settings");
            Process.killProcess(Process.myPid());
        }, RESTART_DELAY_MS);
    }
}
