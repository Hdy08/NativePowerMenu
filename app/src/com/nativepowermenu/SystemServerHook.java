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

    private static final String METHOD_LONG_PRESS_TIMEOUT = "getLongPressTimeoutMs";
    private static final String CLASS_PHONE_WINDOW_MANAGER =
            "com.android.server.policy.PhoneWindowManager";
    private static final String CLASS_PHONE_WINDOW_MANAGER_EXT =
            "com.android.server.policy.PhoneWindowManagerExtImpl";

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
        installTimelineHooks();
        return carrier && powerKey;
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

                            int configured = sLongPressTimeoutMs;
                            int applied = configured > 0 ? configured : framework;
                            logApplied(param.thisObject, framework, applied);

                            // ColorOS routes its power menu through the behavior-5 ("hold for the
                            // assistant") path, so the assistant's timeout IS this device's power
                            // menu timeout - overriding it here is the whole point, not a side
                            // effect. (Stock AOSP would launch the assistant instead; this module
                            // only exists for ColorOS.)
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
     * {@code PhoneWindowManager.getResolvedLongPressOnPowerBehavior()}: 1 = global actions,
     * 5 = "hold power for the assistant" (which is how ColorOS reaches its power menu). Logged so a
     * single power key press in the LSPosed log says exactly which path the device takes.
     */
    private int resolvedBehavior(Object powerKeyRule) {
        try {
            Object policy = XposedHelpers.getObjectField(powerKeyRule, "this$0");
            Object behavior = XposedHelpers.callMethod(policy, "getResolvedLongPressOnPowerBehavior");
            return behavior instanceof Integer ? (Integer) behavior : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Logs what the framework would use and what the module returned. The first few presses of every
     * process are always logged - that is what makes "the setting does nothing" diagnosable from the
     * log alone - and afterwards only when the applied value changes, so pressing the power key to
     * lock the screen does not fill the log.
     */
    private void logApplied(Object powerKeyRule, int framework, int applied) {
        if (mHookLogs >= HOOK_LOG_LIMIT && applied == mLastApplied) {
            return;
        }
        mHookLogs++;
        mLastApplied = applied;
        ModuleLog.d("power key long press: behavior=" + resolvedBehavior(powerKeyRule)
                + " framework=" + framework + " ms, applied=" + applied + " ms");
    }
}
