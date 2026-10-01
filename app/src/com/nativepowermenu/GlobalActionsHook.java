package com.nativepowermenu;

import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Replaces the ColorOS power menu with the AOSP one, and applies settings coming from the app.
 *
 * <p>Injection point: {@code com.android.systemui.globalactions.GlobalActionsImpl#showGlobalActions}
 * - the {@code GlobalActions} plugin that {@code GlobalActionsComponent} calls when the platform
 * asks for the power menu. That method is shared by every ROM because it is part of the SystemUI
 * plugin contract; ColorOS only swapped the dialog behind it.
 *
 * <p>When the module's master switch is off nothing is swallowed, so the stock ColorOS menu comes
 * back immediately, without a reboot.
 */
final class GlobalActionsHook {

    private static final String CLASS_GLOBAL_ACTIONS_IMPL =
            "com.android.systemui.globalactions.GlobalActionsImpl";
    private static final String METHOD_SHOW_GLOBAL_ACTIONS = "showGlobalActions";

    /** Delay before killing SystemUI, so the ordered broadcast result reaches the settings app. */
    private static final long RESTART_DELAY_MS = 700L;

    private final ClassLoader mClassLoader;

    private PowerMenuDialog mDialog;
    private boolean mReceiverInstalled;

    GlobalActionsHook(ClassLoader classLoader) {
        mClassLoader = classLoader;
    }

    void install() {
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

        installApplyReceiver();
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
                    context, new PowerMenuActions(context, mClassLoader), mClassLoader);
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
        SystemClient.setLongPressTimeout(PowerMenuConfig.get(context).longPressMs);
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
                    // Confirm delivery so the settings app can tell the user what happened.
                    setResultCode(PowerMenuConfig.RESULT_APPLIED);
                    // Takes effect immediately - the timeout lives in system_server, so it does not
                    // have to wait for SystemUI to come back.
                    SystemClient.setLongPressTimeout(config.longPressMs);
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

    /** SystemUI is a persistent app: killing it makes the system start it again immediately. */
    private static void restartSystemUi() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ModuleLog.d("restarting SystemUI to apply the new settings");
            Process.killProcess(Process.myPid());
        }, RESTART_DELAY_MS);
    }
}
