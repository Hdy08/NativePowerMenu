package com.nativepowermenu;

import android.content.Context;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Replaces the ColorOS power menu with the AOSP one.
 *
 * <p>Injection point: {@code com.android.systemui.globalactions.GlobalActionsImpl#showGlobalActions}
 * - the {@code GlobalActions} plugin that {@code GlobalActionsComponent} calls when the platform
 * asks for the power menu. That method is shared by every ROM because it is part of the SystemUI
 * plugin contract; ColorOS only swapped the dialog behind it.
 *
 * <p>The vendor method is always short-circuited ({@code setResult(null)}), so the Oplus shutdown
 * view is never inflated. The one argument of the method is the
 * {@code GlobalActions.GlobalActionsManager}, which is exactly what AOSP's dialog uses to report
 * visibility and to trigger shutdown/reboot - we reuse it instead of re-implementing those.
 */
final class GlobalActionsHook {

    private static final String CLASS_GLOBAL_ACTIONS_IMPL =
            "com.android.systemui.globalactions.GlobalActionsImpl";
    private static final String METHOD_SHOW_GLOBAL_ACTIONS = "showGlobalActions";

    private final ClassLoader mClassLoader;

    private PowerMenuDialog mDialog;

    GlobalActionsHook(ClassLoader classLoader) {
        mClassLoader = classLoader;
    }

    void install() {
        Class<?> impl = XposedHelpers.findClass(CLASS_GLOBAL_ACTIONS_IMPL, mClassLoader);

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                impl, METHOD_SHOW_GLOBAL_ACTIONS, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 1) {
                            return;
                        }
                        // Never let the vendor implementation run: it would inflate the ColorOS
                        // shutdown view. Swallow it first so a crash in our code cannot fall back
                        // to the unwanted UI.
                        param.setResult(null);
                        try {
                            handleShow(param.thisObject, param.args[0]);
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

    private void handleShow(Object globalActionsImpl, Object manager) {
        Context context = (Context) XposedHelpers.getObjectField(globalActionsImpl, "mContext");

        if (XposedHelpers.getBooleanField(globalActionsImpl, "mDisabled")) {
            ModuleLog.d("global actions are disabled by the platform, ignoring request");
            return;
        }

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
            mDialog = new PowerMenuDialog(context, new PowerMenuActions(context, mClassLoader));
        }
        return mDialog;
    }
}
