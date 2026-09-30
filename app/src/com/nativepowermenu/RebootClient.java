package com.nativepowermenu;

import android.os.IBinder;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/**
 * SystemUI side of the reboot-with-reason channel.
 *
 * <p>The request goes out over {@code IStatusBarService.getDisableFlags(IBinder, int)} - the binder
 * SystemUI already holds as {@code GlobalActionsComponent.mBarService}. The return value is the
 * point: {@link RebootBridge#ACK} means the module answered from system_server, anything else means
 * nobody is listening (in practice: the module is not scoped to the system framework).
 */
final class RebootClient {

    private static final String FIELD_BAR_SERVICE = "mBarService";

    private RebootClient() {
    }

    static boolean reboot(Object globalActionsManager, String reason) {
        Object barService = barService(globalActionsManager);
        if (barService == null) {
            return false;
        }
        int request = RebootBridge.requestCode(reason);
        if (request < 0) {
            ModuleLog.w("unknown reboot reason " + reason);
            return false;
        }
        Method carrier;
        try {
            carrier = XposedHelpers.findMethodExact(barService.getClass(),
                    RebootBridge.methodName(), RebootBridge.methodSignature());
        } catch (Throwable t) {
            ModuleLog.w("IStatusBarService has no " + RebootBridge.methodName()
                    + " method to carry the request", t);
            return false;
        }
        try {
            Object result = carrier.invoke(barService, (IBinder) null, request);
            if (result instanceof int[] && ((int[]) result).length > 0) {
                int ack = ((int[]) result)[0];
                if (ack == RebootBridge.ACK) {
                    return true;
                }
                if (ack == RebootBridge.NAK) {
                    ModuleLog.e("system_server reached the reboot path but it failed", null);
                    return false;
                }
                ModuleLog.w("no answer from system_server (got " + describe(result)
                        + ") - the module is probably not scoped to the system framework");
                return false;
            }
            ModuleLog.w("unexpected answer from system_server: " + describe(result));
            return false;
        } catch (Throwable t) {
            ModuleLog.e("could not send the reboot request", t);
            return false;
        }
    }

    private static String describe(Object result) {
        if (result instanceof int[]) {
            int[] values = (int[]) result;
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(values[i]);
            }
            return builder.append(']').toString();
        }
        return String.valueOf(result);
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
}
