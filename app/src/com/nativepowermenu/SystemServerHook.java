package com.nativepowermenu;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * System-server side of the module: answers the reboot request and performs it.
 *
 * <p>See {@link RebootBridge} for why this exists and why
 * {@code StatusBarManagerService.getDisableFlags} is the carrier. Nothing else is hooked in
 * system_server, and the hook only reacts to the module's own magic request code.
 *
 * <p>{@code com.android.server.*} lives in {@code services.jar}, which may not be loadable yet when
 * modules are first injected, so a failed first attempt is retried from
 * {@code Application#onCreate}.
 */
final class SystemServerHook {

    private static final String CLASS_STATUS_BAR_MANAGER_SERVICE =
            "com.android.server.statusbar.StatusBarManagerService";

    private final ClassLoader mClassLoader;

    private boolean mInstalled;

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
        if (mInstalled) {
            return true;
        }
        Class<?> service;
        try {
            service = XposedHelpers.findClass(CLASS_STATUS_BAR_MANAGER_SERVICE, mClassLoader);
        } catch (Throwable t) {
            ModuleLog.w("system_server: " + CLASS_STATUS_BAR_MANAGER_SERVICE
                    + " is not loadable yet: " + t);
            return false;
        }

        int count = XposedBridge.hookAllMethods(
                service, RebootBridge.methodName(), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length != 2
                                    || !(param.args[1] instanceof Integer)) {
                                return;
                            }
                            String reason = RebootBridge.reasonFromRequestCode(
                                    (Integer) param.args[1]);
                            if (reason == null) {
                                return;
                            }
                            // Perform the reboot, then answer: the reply is how SystemUI learns
                            // whether the system side was reachable and whether it worked, instead
                            // of failing silently. IPowerManager#reboot does not block, so the reply
                            // still leaves before the device goes down.
                            ModuleLog.d("reboot request from uid "
                                    + RebootBridge.callingUid() + ": " + reason);
                            boolean ok = RebootBridge.reboot(reason, mClassLoader);
                            param.setResult(new int[]{
                                    ok ? RebootBridge.ACK : RebootBridge.NAK});
                        } catch (Throwable t) {
                            // Never let the carrier path hurt system_server.
                            ModuleLog.e("system_server: carrier hook failed", t);
                        }
                    }
                }).size();

        if (count == 0) {
            ModuleLog.w("system_server: " + RebootBridge.methodName()
                    + " not found on " + CLASS_STATUS_BAR_MANAGER_SERVICE);
            return false;
        }
        mInstalled = true;
        ModuleLog.d("system_server: hooked " + count + " " + RebootBridge.methodName()
                + " method(s) on " + CLASS_STATUS_BAR_MANAGER_SERVICE);
        return true;
    }
}
