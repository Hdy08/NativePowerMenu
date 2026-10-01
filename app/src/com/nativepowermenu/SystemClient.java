package com.nativepowermenu;

import android.os.IBinder;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/**
 * SystemUI side of the module's channel to system_server.
 *
 * <p>Requests go out over {@code IStatusBarService.getDisableFlags(IBinder, int)} - the binder
 * SystemUI already holds as {@code GlobalActionsComponent.mBarService}, or resolved from
 * {@code ServiceManager} when no manager instance is around (the timeout is pushed as soon as
 * SystemUI starts, which is before any power menu exists). The return value is the point:
 * {@link SystemBridge#ACK} means the module answered from system_server, anything else means nobody
 * is listening (in practice: the module is not scoped to the system framework).
 */
final class SystemClient {

    private static final String FIELD_BAR_SERVICE = "mBarService";
    private static final String SERVICE_STATUS_BAR = "statusbar";

    private SystemClient() {
    }

    // ---------------------------------------------------------------- requests

    static boolean reboot(Object globalActionsManager, String reason) {
        int request = SystemBridge.requestCode(reason);
        if (request < 0) {
            ModuleLog.w("unknown reboot reason " + reason);
            return false;
        }
        Object barService = barService(globalActionsManager);
        if (barService == null) {
            return false;
        }
        int[] answer = send(barService, request);
        if (answer == null) {
            return false;
        }
        if (answer[0] == SystemBridge.ACK) {
            return true;
        }
        if (answer[0] == SystemBridge.NAK) {
            ModuleLog.e("system_server reached the reboot path but it failed", null);
        }
        return false;
    }

    /**
     * Pushes the power long-press timeout to system_server ({@code 0} restores the framework's own
     * value). Called when SystemUI starts and whenever the settings app applies new values, so the
     * change takes effect without restarting the system process.
     *
     * @return the timeout the framework itself uses on this device, as observed by system_server -
     *     useful because that value is not necessarily what {@code config_longPressOnPowerDurationMs}
     *     says - or {@code -1} when the system side could not be reached (or has not seen a power
     *     key press yet).
     */
    static int pushLongPressTimeout(int ms) {
        int clamped = LongPress.clamp(ms);
        Object barService = barServiceFromServiceManager();
        if (barService == null) {
            return -1;
        }
        int[] answer = send(barService, SystemBridge.timeoutRequestCode(clamped));
        if (answer == null) {
            return -1;
        }
        if (answer[0] == SystemBridge.ACK) {
            int frameworkDefault = answer.length > 1 ? answer[1] : 0;
            ModuleLog.d("system_server accepted the long-press timeout: "
                    + (clamped == 0 ? "framework default" : clamped + " ms")
                    + " (framework's own: "
                    + (frameworkDefault > 0 ? frameworkDefault + " ms" : "not seen yet") + ")");
            return frameworkDefault > 0 ? frameworkDefault : -1;
        }
        if (answer[0] != SystemBridge.NAK) {
            ModuleLog.w("no answer for the long-press timeout - the module is probably not scoped to"
                    + " the system framework");
        }
        return -1;
    }

    // ---------------------------------------------------------------- plumbing

    /** The system side's reply, or {@code null} when the call could not be made or made sense of. */
    private static int[] send(Object barService, int request) {
        Method carrier;
        try {
            carrier = XposedHelpers.findMethodExact(barService.getClass(),
                    SystemBridge.methodName(), SystemBridge.methodSignature());
        } catch (Throwable t) {
            ModuleLog.w("IStatusBarService has no " + SystemBridge.methodName()
                    + " method to carry the request", t);
            return null;
        }
        try {
            Object result = carrier.invoke(barService, (IBinder) null, request);
            if (result instanceof int[] && ((int[]) result).length > 0) {
                int[] answer = (int[]) result;
                if (answer[0] != SystemBridge.ACK && answer[0] != SystemBridge.NAK) {
                    ModuleLog.w("no answer from system_server (got " + describe(result)
                            + ") - the module is probably not scoped to the system framework");
                }
                return answer;
            }
            ModuleLog.w("unexpected answer from system_server: " + describe(result));
        } catch (Throwable t) {
            ModuleLog.e("could not send the request", t);
        }
        return null;
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

    private static Object barServiceFromServiceManager() {
        try {
            Object binder = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.ServiceManager", null),
                    "getService", SERVICE_STATUS_BAR);
            if (binder == null) {
                ModuleLog.w("no " + SERVICE_STATUS_BAR + " service to send the request through");
                return null;
            }
            return XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.internal.statusbar.IStatusBarService$Stub",
                            null),
                    "asInterface", binder);
        } catch (Throwable t) {
            ModuleLog.e("could not resolve the status bar service", t);
            return null;
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
}
