package com.nativepowermenu;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Legacy Xposed API entry point (also used by LSPosed).
 *
 * <p>The module only cares about {@code com.android.systemui}: that is the process which owns the
 * power menu on ColorOS, and hooking anything else would be pointless (and risky).
 */
public class XposedEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    static final String TARGET_PACKAGE = "com.android.systemui";

    @Override
    public void initZygote(StartupParam startupParam) {
        // Nothing to do in zygote: the hook is installed per-process below.
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        try {
            new GlobalActionsHook(lpparam.classLoader).install();
        } catch (Throwable t) {
            ModuleLog.e("failed to install hooks", t);
        }
    }
}
