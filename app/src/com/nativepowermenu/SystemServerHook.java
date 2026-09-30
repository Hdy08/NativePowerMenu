package com.nativepowermenu;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * System-server side of the module: turns a {@code setIcon} carrier call into a reboot.
 *
 * <p>See {@link RebootBridge} for why this exists. Nothing else is hooked in system_server, so the
 * blast radius is one dead method.
 */
final class SystemServerHook {

    private static final String CLASS_STATUS_BAR_MANAGER_SERVICE =
            "com.android.server.statusbar.StatusBarManagerService";
    private static final String METHOD_CARRIER = "setIcon";

    private final ClassLoader mClassLoader;

    SystemServerHook(ClassLoader classLoader) {
        mClassLoader = classLoader;
    }

    void install() {
        Class<?> service = XposedHelpers.findClass(
                CLASS_STATUS_BAR_MANAGER_SERVICE, mClassLoader);

        int count = XposedBridge.hookAllMethods(service, METHOD_CARRIER, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args == null || param.args.length != 5
                            || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String reason = RebootBridge.reasonFromToken((String) param.args[0]);
                    if (reason == null) {
                        return;
                    }
                    // Swallow the carrier call so no status-bar icon bookkeeping happens.
                    param.setResult(null);
                    ModuleLog.d("reboot request from uid " + RebootBridge.callingUid());
                    RebootBridge.reboot(reason, mClassLoader);
                } catch (Throwable t) {
                    // Never let the carrier path hurt system_server.
                    ModuleLog.e("system_server: carrier hook failed", t);
                }
            }
        }).size();

        ModuleLog.d("system_server: hooked " + count + " " + METHOD_CARRIER + " method(s) on "
                + CLASS_STATUS_BAR_MANAGER_SERVICE);
    }
}
