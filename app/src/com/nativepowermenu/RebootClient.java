package com.nativepowermenu;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/**
 * SystemUI side of the reboot-with-reason channel.
 *
 * <p>The request is sent through {@code IStatusBarService.setIcon(...)}, the binder SystemUI already
 * holds as {@code GlobalActionsComponent.mBarService}. Nothing about the icon store is touched: the
 * system process recognises the magic slot and swallows the call. See {@link RebootBridge}.
 */
final class RebootClient {

    private static final String FIELD_BAR_SERVICE = "mBarService";
    private static final String METHOD_CARRIER = "setIcon";

    private RebootClient() {
    }

    static boolean reboot(Object globalActionsManager, String reason) {
        Object barService = barService(globalActionsManager);
        if (barService == null) {
            return false;
        }
        Method carrier = carrier(barService.getClass());
        if (carrier == null) {
            ModuleLog.w("IStatusBarService has no " + METHOD_CARRIER + " method to carry the request");
            return false;
        }
        try {
            carrier.invoke(barService, RebootBridge.token(reason), "", 0, 0, null);
            return true;
        } catch (Throwable t) {
            ModuleLog.e("could not send the reboot request", t);
            return false;
        }
    }

    private static Object barService(Object globalActionsManager) {
        if (globalActionsManager == null) {
            return null;
        }
        try {
            return XposedHelpers.getObjectField(globalActionsManager, FIELD_BAR_SERVICE);
        } catch (Throwable t) {
            ModuleLog.w("GlobalActionsManager has no " + FIELD_BAR_SERVICE + " field: " + t);
            return null;
        }
    }

    /**
     * Resolved by shape rather than by exact class: the runtime object is the AIDL proxy, and its
     * declaring class name differs between ROMs.
     */
    private static Method carrier(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            if (METHOD_CARRIER.equals(method.getName())
                    && method.getParameterTypes().length == 5) {
                return method;
            }
        }
        return null;
    }
}
