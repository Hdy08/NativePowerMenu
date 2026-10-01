package com.nativepowermenu;

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
 * <p>The timeout is enforced by hooking
 * {@code com.android.server.policy.PhoneWindowManager$PowerKeyRule#getLongPressTimeoutMs}. That rule
 * is what {@code SingleKeyGestureDetector} asks for the delay before a power-key press turns into a
 * long press, which is the moment {@code powerLongPress()} shows the global actions menu; on this
 * device its default comes from {@code config_longPressOnPowerDurationMs} (500 ms).
 *
 * <p>{@code com.android.server.*} lives in {@code services.jar}, which may not be loadable yet when
 * modules are first injected, so a failed first attempt is retried from {@code Application#onCreate}.
 */
final class SystemServerHook {

    private static final String CLASS_STATUS_BAR_MANAGER_SERVICE =
            "com.android.server.statusbar.StatusBarManagerService";
    private static final String CLASS_POWER_KEY_RULE =
            "com.android.server.policy.PhoneWindowManager$PowerKeyRule";

    /** {@code PhoneWindowManager.LONG_PRESS_POWER_ASSISTANT}. */
    private static final int LONG_PRESS_POWER_ASSISTANT = 5;

    private static final String METHOD_LONG_PRESS_TIMEOUT = "getLongPressTimeoutMs";

    /** Written by the carrier hook, read by the key-rule hook; both live in system_server. */
    private static volatile int sLongPressTimeoutMs;
    /**
     * What the framework would use on its own, as seen on the last power key press. Reported back to
     * SystemUI so the settings screen can show the device's real default instead of guessing it from
     * {@code config_longPressOnPowerDurationMs}.
     */
    private static volatile int sFrameworkTimeoutMs;

    private final ClassLoader mClassLoader;

    private boolean mCarrierInstalled;
    private boolean mPowerKeyInstalled;
    /** The framework's own timeout, logged once so it can be compared with what the user picked. */
    private boolean mDefaultLogged;

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
        return carrier && powerKey;
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
                            if (result instanceof Long && (Long) result > 0
                                    && (Long) result <= 60000) {
                                sFrameworkTimeoutMs = (int) (long) (Long) result;
                            }

                            int configured = sLongPressTimeoutMs;
                            if (configured <= 0) {
                                logFrameworkDefault(param);
                                return;
                            }
                            if (isAssistantLongPress(param.thisObject)) {
                                // The power key is configured to launch the assistant, whose
                                // timeout is a separate, deliberately short one.
                                return;
                            }
                            param.setResult((long) configured);
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

    /** {@code PhoneWindowManager.LONG_PRESS_POWER_ASSISTANT} means "hold power for the assistant". */
    private boolean isAssistantLongPress(Object powerKeyRule) {
        try {
            Object policy = XposedHelpers.getObjectField(powerKeyRule, "this$0");
            Object behavior = XposedHelpers.callMethod(policy, "getResolvedLongPressOnPowerBehavior");
            return behavior instanceof Integer && (Integer) behavior == LONG_PRESS_POWER_ASSISTANT;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Once per process: records what the device would do without the module. */
    private void logFrameworkDefault(XC_MethodHook.MethodHookParam param) {
        if (mDefaultLogged) {
            return;
        }
        mDefaultLogged = true;
        ModuleLog.d("system_server: power long-press timeout is the framework's own, "
                + param.getResult() + " ms");
    }
}
