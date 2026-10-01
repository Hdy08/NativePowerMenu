package com.nativepowermenu;

import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * System-server side of the module: answers the reboot and timeout requests, and applies the
 * power-button long-press timeout.
 *
 * <p>See {@link SystemBridge} for why the channel exists and why
 * {@code StatusBarManagerService.getDisableFlags} is the carrier. The carrier hook only reacts to
 * the module's own magic request codes.
 *
 * <p>The delay before the power menu appears is enforced in two places, because the two Android
 * paths that can lead there are both live: {@code PowerKeyRule#getLongPressTimeoutMs} (the plain
 * long press) and {@code SingleKeyGestureDetectorExtImpl#modifyPressTimeout} - ColorOS' own hook,
 * which hardcodes the power key's <em>very</em>-long-press timeout to 2500 ms and is what actually
 * shows the menu on this device. See {@link #installVeryLongPressHook()}.
 *
 * <p>{@code com.android.server.*} lives in {@code services.jar}, which may not be loadable yet when
 * modules are first injected, so a failed first attempt is retried from {@code Application#onCreate}.
 */
final class SystemServerHook {

    private static final String CLASS_STATUS_BAR_MANAGER_SERVICE =
            "com.android.server.statusbar.StatusBarManagerService";
    private static final String CLASS_POWER_KEY_RULE =
            "com.android.server.policy.PhoneWindowManager$PowerKeyRule";

    private static final String METHOD_LONG_PRESS_TIMEOUT = "getLongPressTimeoutMs";
    private static final String CLASS_PHONE_WINDOW_MANAGER =
            "com.android.server.policy.PhoneWindowManager";
    private static final String CLASS_PHONE_WINDOW_MANAGER_EXT =
            "com.android.server.policy.PhoneWindowManagerExtImpl";
    /**
     * ColorOS' own hook into {@code SingleKeyGestureDetector}: it rewrites the power key's
     * very-long-press timeout, and that - not the long-press timeout - is what actually shows the
     * power menu on this device.
     */
    private static final String CLASS_SINGLE_KEY_EXT =
            "com.android.server.policy.SingleKeyGestureDetectorExtImpl";
    private static final String METHOD_MODIFY_PRESS_TIMEOUT = "modifyPressTimeout";
    /** {@code SingleKeyGestureDetectorExtImpl.PRESS_TYPE_VERY_LONG}. */
    private static final int PRESS_TYPE_VERY_LONG = 1;
    private static final int KEYCODE_POWER = 26;

    /** Written by the carrier hook, read by the key-rule hook; both live in system_server. */
    private static volatile int sLongPressTimeoutMs;
    /**
     * What the framework would use on its own, as seen on the last power key press. Reported back to
     * SystemUI so the settings screen can show the device's real default instead of guessing it from
     * {@code config_longPressOnPowerDurationMs}.
     */
    private static volatile int sFrameworkTimeoutMs;

    private final ClassLoader mClassLoader;

    /** How many key presses get logged before only value changes are. */
    private static final int HOOK_LOG_LIMIT = 12;

    private boolean mCarrierInstalled;
    private boolean mPowerKeyInstalled;
    private boolean mVeryLongPressInstalled;
    private boolean mTimelineInstalled;
    private int mHookLogs;
    private int mLastApplied = -1;

    SystemServerHook(ClassLoader classLoader) {
        mClassLoader = classLoader;
    }

    void install() {
        if (tryInstall()) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", mClassLoader, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            tryInstall();
                        }
                    });
            ModuleLog.d("system_server: deferred the hook until Application#onCreate");
        } catch (Throwable t) {
            ModuleLog.e("system_server: could not defer the hook", t);
        }
    }

    private synchronized boolean tryInstall() {
        boolean carrier = mCarrierInstalled || installCarrierHook();
        boolean powerKey = mPowerKeyInstalled || installPowerKeyHook();
        boolean veryLong = mVeryLongPressInstalled || installVeryLongPressHook();
        installTimelineHooks();
        return carrier && powerKey && veryLong;
    }

    // ---------------------------------------------------------------- the real power menu timer

    /**
     * The lever that matters on ColorOS.
     *
     * <p>{@code SingleKeyGestureDetector.interceptKeyDown()} schedules the very-long-press message
     * as {@code mSingleKeyGestureDetectorExt.modifyPressTimeout(1, rule.getVeryLongPressTimeoutMs(),
     * event)}, and ColorOS' implementation returns a hardcoded {@code 2500} for the power key. That
     * message is what ends up in {@code powerVeryLongPress() -> showGlobalActions()} - the long-press
     * timeout is never even consulted on this device (verified from the LSPosed log: the key press
     * and {@code showGlobalActions()} are 2.5 s apart while
     * {@code PowerKeyRule#getLongPressTimeoutMs()} reports 500 ms and its {@code onLongPress} never
     * fires).
     *
     * <p>So this hook replaces that hardcoded value, and records it: it is the device's real default,
     * which the settings screen shows.
     */
    private boolean installVeryLongPressHook() {
        Class<?> ext;
        try {
            ext = XposedHelpers.findClass(CLASS_SINGLE_KEY_EXT, mClassLoader);
        } catch (Throwable t) {
            ModuleLog.w("system_server: " + CLASS_SINGLE_KEY_EXT + " is not loadable yet: " + t);
            return false;
        }

        int count = XposedBridge.hookAllMethods(ext, METHOD_MODIFY_PRESS_TIMEOUT, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    int configured = sLongPressTimeoutMs;
                    if (configured <= 0 || param.args == null || param.args.length != 3
                            || !(param.args[0] instanceof Integer)
                            || !(param.args[2] instanceof KeyEvent)) {
                        return;
                    }
                    if ((Integer) param.args[0] != PRESS_TYPE_VERY_LONG
                            || ((KeyEvent) param.args[2]).getKeyCode() != KEYCODE_POWER) {
                        return;
                    }
                    int deviceValue = 0;
                    if (param.getResult() instanceof Long) {
                        deviceValue = (int) (long) (Long) param.getResult();
                        if (deviceValue > 0 && deviceValue <= 60000) {
                            sFrameworkTimeoutMs = deviceValue;
                        }
                    }
                    param.setResult((long) configured);
                    logApplied(deviceValue, configured);
                } catch (Throwable t) {
                    // Never let this hurt the input path; the device value stays in place.
                    ModuleLog.w("system_server: could not apply the timeout: " + t);
                }
            }
        }).size();

        if (count == 0) {
            ModuleLog.w("system_server: " + METHOD_MODIFY_PRESS_TIMEOUT + " not found on "
                    + CLASS_SINGLE_KEY_EXT);
            return false;
        }
        mVeryLongPressInstalled = true;
        ModuleLog.d("system_server: hooked " + count + " " + METHOD_MODIFY_PRESS_TIMEOUT
                + " method(s) on " + CLASS_SINGLE_KEY_EXT);
        return true;
    }

    // ---------------------------------------------------------------- power key timeline

    /**
     * Logs the whole power-key path once, so "the timeout setting does nothing" can be told apart
     * from "the menu is not shown by this path at all". Each of these only runs on a long press.
     */
    private void installTimelineHooks() {
        if (mTimelineInstalled) {
            return;
        }
        mTimelineInstalled = true;
        hookOrWarn(CLASS_PHONE_WINDOW_MANAGER, "powerLongPress", "powerLongPress fired", true,
                new Class<?>[]{long.class});
        hookOrWarn(CLASS_PHONE_WINDOW_MANAGER, "showGlobalActions", "showGlobalActions()", false,
                new Class<?>[0]);
        hookOrWarn(CLASS_POWER_KEY_RULE, "onLongPress", "PowerKeyRule.onLongPress", false,
                new Class<?>[]{long.class});
        hookOrWarn(CLASS_POWER_KEY_RULE, "onVeryLongPress", "PowerKeyRule.onVeryLongPress", false,
                new Class<?>[]{long.class});
        hookOrWarn(CLASS_PHONE_WINDOW_MANAGER, "powerVeryLongPress", "powerVeryLongPress()", false,
                new Class<?>[0]);
        hookOrWarn(CLASS_PHONE_WINDOW_MANAGER_EXT, "oplusInterceptLongPowerPress",
                "oplusInterceptLongPowerPress -> ", true, new Class<?>[0]);
    }

    private void hookOrWarn(String className, String method, final String message,
            final boolean logResult, Class<?>... signature) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, mClassLoader);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ModuleLog.d(message
                                + (logResult ? String.valueOf(param.getResult()) : ""));
                    } catch (Throwable ignored) {
                        // Logging must never break the policy path.
                    }
                }
            };
            XposedHelpers.findAndHookMethod(clazz, method, arguments(signature, hook));
        } catch (Throwable t) {
            ModuleLog.w("system_server: could not hook " + className + "#" + method + ": " + t);
        }
    }

    /** {@code findAndHookMethod}'s varargs form: the parameter types followed by the callback. */
    private static Object[] arguments(Class<?>[] signature, XC_MethodHook hook) {
        Object[] params = new Object[signature.length + 1];
        System.arraycopy(signature, 0, params, 0, signature.length);
        params[signature.length] = hook;
        return params;
    }

    // ---------------------------------------------------------------- the request carrier

    private boolean installCarrierHook() {
        Class<?> service;
        try {
            service = XposedHelpers.findClass(CLASS_STATUS_BAR_MANAGER_SERVICE, mClassLoader);
        } catch (Throwable t) {
            ModuleLog.w("system_server: " + CLASS_STATUS_BAR_MANAGER_SERVICE
                    + " is not loadable yet: " + t);
            return false;
        }

        int count = XposedBridge.hookAllMethods(
                service, SystemBridge.methodName(), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length != 2
                                    || !(param.args[1] instanceof Integer)) {
                                return;
                            }
                            int request = (Integer) param.args[1];

                            int timeout = SystemBridge.timeoutFromRequestCode(request);
                            if (timeout >= 0) {
                                SystemServerHook.sLongPressTimeoutMs =
                                        LongPress.clamp(timeout);
                                ModuleLog.d("long-press timeout from uid "
                                        + SystemBridge.callingUid() + ": "
                                        + (timeout == 0 ? "framework default"
                                                : timeout + " ms"));
                                // The second element tells SystemUI what the framework itself uses,
                                // so the settings screen can display the device's real default.
                                param.setResult(new int[]{
                                        SystemBridge.ACK, sFrameworkTimeoutMs});
                                return;
                            }

                            String reason = SystemBridge.reasonFromRequestCode(request);
                            if (reason == null) {
                                return;
                            }
                            // Perform the reboot, then answer: the reply is how SystemUI learns
                            // whether the system side was reachable and whether it worked, instead
                            // of failing silently. IPowerManager#reboot does not block, so the reply
                            // still leaves before the device goes down.
                            ModuleLog.d("reboot request from uid "
                                    + SystemBridge.callingUid() + ": " + reason);
                            boolean ok = SystemBridge.reboot(reason, mClassLoader);
                            param.setResult(new int[]{
                                    ok ? SystemBridge.ACK : SystemBridge.NAK});
                        } catch (Throwable t) {
                            // Never let the carrier path hurt system_server.
                            ModuleLog.e("system_server: carrier hook failed", t);
                        }
                    }
                }).size();

        if (count == 0) {
            ModuleLog.w("system_server: " + SystemBridge.methodName()
                    + " not found on " + CLASS_STATUS_BAR_MANAGER_SERVICE);
            return false;
        }
        mCarrierInstalled = true;
        ModuleLog.d("system_server: hooked " + count + " " + SystemBridge.methodName()
                + " method(s) on " + CLASS_STATUS_BAR_MANAGER_SERVICE);
        return true;
    }

    // ---------------------------------------------------------------- the long-press timeout

    private boolean installPowerKeyHook() {
        Class<?> rule;
        try {
            rule = XposedHelpers.findClass(CLASS_POWER_KEY_RULE, mClassLoader);
        } catch (Throwable t) {
            ModuleLog.w("system_server: " + CLASS_POWER_KEY_RULE + " is not loadable yet: " + t);
            return false;
        }

        int count = XposedBridge.hookAllMethods(rule, METHOD_LONG_PRESS_TIMEOUT,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // Whatever the framework decided, remember it: that is the number the
                            // settings screen shows for "default", and it is not necessarily what
                            // config_longPressOnPowerDurationMs says.
                            Object result = param.getResult();
                            int framework = 0;
                            if (result instanceof Long && (Long) result > 0
                                    && (Long) result <= 60000) {
                                framework = (int) (long) (Long) result;
                                sFrameworkTimeoutMs = framework;
                            }

                            // The plain long-press path. Kept because it is the one that fires on
                            // stock AOSP; on ColorOS the menu comes from the very-long press (see
                            // installVeryLongPressHook), so this is silent.
                            int configured = sLongPressTimeoutMs;
                            if (configured > 0) {
                                param.setResult((long) configured);
                            }
                        } catch (Throwable t) {
                            // Falling through to the framework's own value is the safe outcome.
                            ModuleLog.w("system_server: could not apply the long-press timeout: " + t);
                        }
                    }
                }).size();

        if (count == 0) {
            ModuleLog.w("system_server: " + METHOD_LONG_PRESS_TIMEOUT + " not found on "
                    + CLASS_POWER_KEY_RULE);
            return false;
        }
        mPowerKeyInstalled = true;
        ModuleLog.d("system_server: hooked " + count + " " + METHOD_LONG_PRESS_TIMEOUT
                + " method(s) on " + CLASS_POWER_KEY_RULE);
        return true;
    }

    /**
     * Logs the value the device would have used and the one the module applied. The first few
     * presses of every process are always logged - that is what makes "the setting does nothing"
     * diagnosable from the log alone - and afterwards only when the applied value changes.
     */
    private void logApplied(int deviceValue, int applied) {
        if (mHookLogs >= HOOK_LOG_LIMIT && applied == mLastApplied) {
            return;
        }
        mHookLogs++;
        mLastApplied = applied;
        ModuleLog.d("power key long press: device=" + deviceValue + " ms, applied=" + applied + " ms");
    }
}
