package com.nativepowermenu;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Legacy Xposed API entry point (also used by LSPosed).
 *
 * <p>Two processes matter:
 *
 * <ul>
 *   <li>{@code com.android.systemui} - owns the power menu; the whole UI lives there.
 *   <li>{@code android} (system_server) - the only process allowed to reboot with a custom reason,
 *       used by the extended bootloader / recovery entries.
 * </ul>
 *
 * Both are optional: if only SystemUI is scoped, the module still replaces the menu and the two
 * extended entries simply report that they are unavailable.
 */
public class XposedEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    static final String TARGET_PACKAGE = "com.android.systemui";
    static final String SYSTEM_SERVER_PACKAGE = "android";

    @Override
    public void initZygote(StartupParam startupParam) {
        // The module only needs its own APK path to resolve its resources inside other processes.
        ModuleResources.setModulePath(startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (TARGET_PACKAGE.equals(lpparam.packageName)) {
            try {
                new GlobalActionsHook(lpparam.classLoader).install();
            } catch (Throwable t) {
                ModuleLog.e("failed to install the SystemUI hooks", t);
            }
            return;
        }
        if (SYSTEM_SERVER_PACKAGE.equals(lpparam.packageName)) {
            try {
                new SystemServerHook(lpparam.classLoader).install();
            } catch (Throwable t) {
                ModuleLog.e("failed to install the system_server hooks", t);
            }
        }
    }
}
