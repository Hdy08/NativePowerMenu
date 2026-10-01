package com.nativepowermenu;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import de.robv.android.xposed.XposedHelpers;

/**
 * Builds the power-menu entry list from {@link PowerMenuConfig} and executes the selected action.
 *
 * <p>Every action is routed through the same facilities AOSP's own {@code GlobalActionsDialogLite}
 * uses, so the semantics are identical:
 *
 * <ul>
 *   <li>{@code power} -&gt; {@code GlobalActionsManager.shutdown()} (long press: reboot to safe mode)
 *   <li>{@code restart} -&gt; {@code GlobalActionsManager.reboot(false)}
 *   <li>{@code screenshot} -&gt; {@code com.android.internal.util.ScreenshotHelper}
 *   <li>{@code emergency} -&gt; {@code ACTION_EMERGENCY_ASSISTANCE}
 *   <li>{@code lockdown} -&gt; {@code LockPatternUtils.requireStrongAuth()} + {@code IWindowManager.lockNow()}
 *   <li>{@code bootloader} / {@code recovery} -&gt; the extended entries, which need the system
 *       process (see {@link SystemBridge}); a tap reboots immediately, with no confirmation step
 * </ul>
 */
final class PowerMenuActions {

    /** {@code LockPatternUtils.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN}. */
    private static final int STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN = 32;
    /** {@code UserHandle.USER_ALL}. */
    private static final int USER_ALL = -1;

    /** Extra wait before capturing, so the power menu is really gone from the screen. */
    private static final long SCREENSHOT_DELAY_MS = 500L;

    private final Context mContext;
    private final ClassLoader mClassLoader;
    private final Resources mSysUiRes;

    /** {@code com.android.systemui.plugins.GlobalActions.GlobalActionsManager}. */
    private Object mManager;
    /** {@code com.android.systemui.statusbar.policy.KeyguardStateController}. */
    private Object mKeyguardStateController;

    PowerMenuActions(Context context, ClassLoader classLoader) {
        mContext = context;
        mClassLoader = classLoader;
        mSysUiRes = context.getResources();
    }

    void setManager(Object manager) {
        mManager = manager;
    }

    void setKeyguardStateController(Object controller) {
        mKeyguardStateController = controller;
    }

    // ---------------------------------------------------------------- status bar bookkeeping

    /** Tells the platform that the power menu became visible. */
    void onShownCompat() {
        invokeManager("onGlobalActionsShown");
    }

    /** Tells the platform that the power menu went away. */
    void onHiddenCompat() {
        invokeManager("onGlobalActionsHidden");
    }

    // ---------------------------------------------------------------- item list

    List<PowerMenuItem> createItems(boolean keyguardShowing, boolean deviceProvisioned) {
        PowerMenuConfig config = PowerMenuConfig.get(mContext);
        boolean telephony = mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        boolean secure = isKeyguardSecure();

        List<PowerMenuItem> items = new ArrayList<>();
        for (String key : config.order) {
            if (!config.isEnabled(key)) {
                continue;
            }
            switch (key) {
                case PowerMenuItems.POWER:
                    items.add(new PowerMenuItem(key, PowerMenuItems.icon(mContext, key),
                            PowerMenuItems.label(mContext, key), false,
                            () -> invokeManager("shutdown"),
                            () -> invokeManager("reboot", Boolean.TRUE)));
                    break;
                case PowerMenuItems.RESTART:
                    items.add(simple(key, () -> invokeManager("reboot", Boolean.FALSE)));
                    break;
                case PowerMenuItems.EMERGENCY:
                    if (telephony) {
                        items.add(simple(key, this::startEmergencyDialer));
                    }
                    break;
                case PowerMenuItems.LOCKDOWN:
                    // AOSP hides Lockdown unless the device has a secure lock screen.
                    if (deviceProvisioned && secure && isLockdownAllowed()) {
                        items.add(simple(key, this::lockDown));
                    }
                    break;
                case PowerMenuItems.SCREENSHOT:
                    // AOSP: ScreenshotAction.showBeforeProvisioning() == false
                    if (deviceProvisioned) {
                        items.add(simple(key, this::takeScreenshot));
                    }
                    break;
                case PowerMenuItems.BOOTLOADER:
                    items.add(simple(key, () -> rebootTo(SystemBridge.REASON_BOOTLOADER)));
                    break;
                case PowerMenuItems.RECOVERY:
                    items.add(simple(key, () -> rebootTo(SystemBridge.REASON_RECOVERY)));
                    break;
                default:
                    // Unknown key: skip rather than showing a dead button.
                    break;
            }
        }
        return items;
    }

    private PowerMenuItem simple(String key, Runnable onPress) {
        return new PowerMenuItem(key, PowerMenuItems.icon(mContext, key),
                PowerMenuItems.label(mContext, key),
                PowerMenuItems.EMERGENCY.equals(key),
                onPress, null);
    }

    // ---------------------------------------------------------------- actions

    private void invokeManager(String method, Object... args) {
        Object manager = mManager;
        if (manager == null) {
            ModuleLog.w("no GlobalActionsManager, cannot call " + method);
            return;
        }
        try {
            XposedHelpers.callMethod(manager, method, args);
        } catch (Throwable t) {
            ModuleLog.e("GlobalActionsManager." + method + " failed", t);
        }
    }

    /**
     * Extended reboot. SystemUI cannot reboot with a custom reason itself (no
     * {@code android.permission.REBOOT}), so the request travels through the system process;
     * see {@link SystemBridge}.
     */
    private void rebootTo(String reason) {
        if (SystemClient.reboot(mManager, reason)) {
            ModuleLog.d("system_server acknowledged the reboot to " + reason);
            return;
        }
        ModuleLog.e("the system process did not answer the reboot request for " + reason
                + " - the module is probably not scoped to the system framework", null);
        try {
            Toast.makeText(mContext,
                    ModuleResources.string(mContext, R.string.reboot_unavailable,
                            "Reboot failed - enable the module for the system framework"),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // Toasts are best-effort only.
        }
    }

    /**
     * AOSP launches the emergency dialer through {@code TelecomManager}, because
     * {@code ACTION_EMERGENCY_ASSISTANCE} has no handler on ColorOS (verified in the LSPosed log:
     * {@code ActivityNotFoundException}). The dialer is also reachable through its well-known
     * component and, failing that, by pre-filling the GSM emergency number.
     */
    private void startEmergencyDialer() {
        if (startEmergencyDialerViaTelecom()) {
            return;
        }
        if (startActivitySafely(new Intent(Intent.ACTION_DIAL)
                .setComponent(new ComponentName(
                        "com.android.phone", "com.android.phone.EmergencyDialer"))
                .putExtra("com.android.phone.EmergencyDialer.extra.ENTRY_TYPE", 2))) {
            return;
        }
        if (startActivitySafely(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))) {
            return;
        }
        ModuleLog.e("emergency dialer failed: no handler for the emergency dialer", null);
    }

    private boolean startEmergencyDialerViaTelecom() {
        try {
            Object telecom = mContext.getSystemService(Context.TELECOM_SERVICE);
            if (telecom == null) {
                return false;
            }
            // TelecomManager.createLaunchEmergencyDialerIntent is hidden in the public SDK.
            Method create = XposedHelpers.findMethodExact(
                    telecom.getClass(), "createLaunchEmergencyDialerIntent", String.class);
            Intent intent = (Intent) create.invoke(telecom, (Object) null);
            if (intent == null) {
                return false;
            }
            intent.putExtra("com.android.phone.EmergencyDialer.extra.ENTRY_TYPE", 2);
            return startActivitySafely(intent);
        } catch (Throwable t) {
            ModuleLog.w("TelecomManager emergency dialer unavailable: " + t);
            return false;
        }
    }

    private boolean startActivitySafely(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            mContext.startActivity(intent);
            return true;
        } catch (Throwable t) {
            ModuleLog.w("could not start " + intent + ": " + t);
            return false;
        }
    }

    /**
     * AOSP delays the capture on purpose ("to give the dialog a chance to go away before it takes a
     * screenshot"); the dialog itself is already dismissed by the caller, but the window removal is
     * asynchronous, so wait a little longer before asking for the shot.
     */
    private void takeScreenshot() {
        new Handler(Looper.getMainLooper())
                .postDelayed(this::doTakeScreenshot, SCREENSHOT_DELAY_MS);
    }

    private void doTakeScreenshot() {
        try {
            Class<?> helperClass = XposedHelpers.findClass(
                    "com.android.internal.util.ScreenshotHelper", mClassLoader);
            Object helper = XposedHelpers.newInstance(helperClass, mContext);
            Method takeScreenshot = XposedHelpers.findMethodExact(
                    helperClass, "takeScreenshot", int.class, Handler.class, Consumer.class);
            // 0 == ScreenshotHelper.ScreenshotSource.GLOBAL_ACTIONS
            takeScreenshot.invoke(helper, 0, new Handler(Looper.getMainLooper()), null);
        } catch (Throwable t) {
            ModuleLog.e("screenshot failed", t);
        }
    }

    private void lockDown() {
        try {
            Object utils = newLockPatternUtils();
            if (utils == null) {
                return;
            }
            XposedHelpers.findMethodExact(
                    utils.getClass(), "requireStrongAuth", int.class, int.class)
                    .invoke(utils, STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN, USER_ALL);

            Object windowManager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.view.WindowManagerGlobal", mClassLoader),
                    "getWindowManagerService");
            // IWindowManager.lockNow(Bundle) is hidden and the argument is null, so resolve the
            // method by shape instead of by argument type.
            for (Method method : windowManager.getClass().getMethods()) {
                if ("lockNow".equals(method.getName())
                        && method.getParameterTypes().length == 1) {
                    method.invoke(windowManager, new Object[]{null});
                    break;
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("lockdown failed", t);
        }
    }

    // ---------------------------------------------------------------- capability probes

    private boolean isKeyguardSecure() {
        // ColorOS only exposes this as KeyguardStateControllerImpl.mSecure; the interface has
        // neither isShowing() nor isMethodSecure(), unlike AOSP.
        return Reflect.booleanValue(mKeyguardStateController, "mSecure", "isMethodSecure", false);
    }

    /**
     * AOSP hides the lockdown button while the device is already locked down, because it would be a
     * no-op. {@code LockPatternUtils.STRONG_AUTH_NOT_REQUIRED == 0} and
     * {@code SOME_AUTH_REQUIRED_AFTER_USER_REQUEST == 4} are the two states where it still matters.
     */
    private boolean isLockdownAllowed() {
        try {
            Object utils = newLockPatternUtils();
            if (utils == null) {
                return false;
            }
            int state = (Integer) XposedHelpers.findMethodExact(
                    utils.getClass(), "getStrongAuthForUser", int.class)
                    .invoke(utils, currentUserId());
            return state == 0 || state == 4;
        } catch (Throwable t) {
            ModuleLog.w("could not query strong auth state: " + t);
            return false;
        }
    }

    private Object newLockPatternUtils() {
        try {
            Class<?> utilsClass = XposedHelpers.findClass(
                    "com.android.internal.widget.LockPatternUtils", mClassLoader);
            return XposedHelpers.newInstance(utilsClass, mContext);
        } catch (Throwable t) {
            ModuleLog.w("could not create LockPatternUtils: " + t);
            return null;
        }
    }

    /** {@code ActivityManager.getCurrentUser()} is hidden in the public SDK. */
    private int currentUserId() {
        try {
            Object userId = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityManager", mClassLoader),
                    "getCurrentUser");
            return userId instanceof Integer ? (Integer) userId : 0;
        } catch (Throwable t) {
            ModuleLog.w("could not resolve current user: " + t);
            return 0;
        }
    }
}
