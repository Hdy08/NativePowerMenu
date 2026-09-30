package com.nativepowermenu;

import android.os.Binder;
import android.os.IBinder;

import de.robv.android.xposed.XposedHelpers;

/**
 * The two ends of the reboot-with-reason channel.
 *
 * <p>Why a channel at all: {@code PowerManagerService.reboot()} enforces
 * {@code android.permission.REBOOT} (and {@code RECOVERY} for the recovery reason), and SystemUI
 * holds neither - it does not even request them. Only the system process may reboot with a custom
 * reason, which is exactly how ColorOS' own {@code StatusBarManagerService.reboot(boolean)} works.
 *
 * <p>So the module also runs in system_server, and the request travels over
 * {@code IStatusBarService.getDisableFlags(IBinder token, int userId)}. That method is chosen
 * because it <em>returns</em> an {@code int[]}: the system side answers with {@link #ACK}, so
 * SystemUI can tell "the module is loaded there" from "nobody is listening" instead of failing
 * silently. Called with a null token and a magic {@code userId} the real implementation matches no
 * record and returns {@code {0, 0}}, so an unhooked system_server sees no side effect at all.
 */
final class RebootBridge {

    /** Magic {@code userId} range. Real calls only ever pass real user/display ids. */
    private static final int REQUEST_BASE = 0x4E504D00;

    private static final int REQUEST_RECOVERY = REQUEST_BASE | 1;
    private static final int REQUEST_BOOTLOADER = REQUEST_BASE | 2;

    /** First element of the returned array: the system side handled the request successfully. */
    static final int ACK = 0x4E504D01;
    /** The system side was reached but the reboot itself failed. */
    static final int NAK = 0x4E504D02;

    static final String REASON_RECOVERY = "recovery";
    static final String REASON_BOOTLOADER = "bootloader";

    private RebootBridge() {
    }

    static String methodName() {
        return "getDisableFlags";
    }

    /** The exact signature the client resolves on the binder proxy. */
    static Class<?>[] methodSignature() {
        return new Class<?>[]{IBinder.class, int.class};
    }

    static int requestCode(String reason) {
        if (REASON_RECOVERY.equals(reason)) {
            return REQUEST_RECOVERY;
        }
        if (REASON_BOOTLOADER.equals(reason)) {
            return REQUEST_BOOTLOADER;
        }
        return -1;
    }

    /** Returns {@code null} unless {@code code} is one of our own, well-known requests. */
    static String reasonFromRequestCode(int code) {
        if (code == REQUEST_RECOVERY) {
            return REASON_RECOVERY;
        }
        if (code == REQUEST_BOOTLOADER) {
            return REASON_BOOTLOADER;
        }
        return null;
    }

    // ------------------------------------------------------------------ system_server side

    /**
     * Runs inside system_server. {@code IPowerManager.reboot} is called by the system process
     * itself, which always passes the platform's own permission checks - the same path
     * {@code StatusBarManagerService} and {@code ShutdownThread} use.
     */
    static boolean reboot(String reason, ClassLoader classLoader) {
        ModuleLog.d("system_server: rebooting to " + reason);
        try {
            Object binder = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.ServiceManager", classLoader),
                    "getService", "power");
            Object powerManager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.IPowerManager$Stub", classLoader),
                    "asInterface", binder);
            // Resolved by exact signature: the arguments are primitives, and callMethod() would
            // have to rely on autoboxing-aware matching to find it.
            XposedHelpers.findMethodExact(powerManager.getClass(), "reboot",
                            boolean.class, String.class, boolean.class)
                    .invoke(powerManager, false, reason, false);
            return true;
        } catch (Throwable t) {
            ModuleLog.e("system_server: reboot(" + reason + ") failed", t);
        }
        return false;
    }

    /** Only used for logging the caller when the hook fires. */
    static int callingUid() {
        try {
            return Binder.getCallingUid();
        } catch (Throwable t) {
            return -1;
        }
    }
}
