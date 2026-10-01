package com.nativepowermenu;

import android.os.Binder;
import android.os.IBinder;

import de.robv.android.xposed.XposedHelpers;

/**
 * The two ends of the module's private channel between SystemUI and system_server.
 *
 * <p><b>Why a channel at all.</b> {@code PowerManagerService.reboot()} enforces
 * {@code android.permission.REBOOT} (and {@code RECOVERY} for the recovery reason), and SystemUI
 * holds neither - it does not even request them. Only the system process may reboot with a custom
 * reason, which is exactly how ColorOS' own {@code StatusBarManagerService.reboot(boolean)} works.
 * The long-press timeout has the same shape: it lives in {@code PhoneWindowManager}, which only
 * system_server can reach.
 *
 * <p>So the module also runs in system_server, and requests travel over
 * {@code IStatusBarService.getDisableFlags(IBinder token, int userId)}. That method is chosen
 * because it <em>returns</em> an {@code int[]}: the system side answers with {@link #ACK}, so
 * SystemUI can tell "the module is loaded there" from "nobody is listening" instead of failing
 * silently. Called with a null token and a magic {@code userId} the real implementation matches no
 * record and returns {@code {0, 0}}, so an unhooked system_server sees no side effect at all.
 *
 * <p>The magic {@code userId} carries both the kind of request and, for timeouts, the value:
 *
 * <ul>
 *   <li>{@link #REQUEST_RECOVERY} / {@link #REQUEST_BOOTLOADER} - reboot with that reason
 *   <li>{@code 0x4E510000 | ms} - set the power long-press timeout to {@code ms} (0 = the
 *       framework's own value)
 * </ul>
 */
final class SystemBridge {

    /** Magic {@code userId} range for reboots. Real calls only ever pass real user/display ids. */
    private static final int REQUEST_BASE = 0x4E504D00;

    private static final int REQUEST_RECOVERY = REQUEST_BASE | 1;
    private static final int REQUEST_BOOTLOADER = REQUEST_BASE | 2;

    /**
     * Magic {@code userId} range for timeouts, with the value in the low 16 bits. The high half is
     * deliberately different from {@link #REQUEST_BASE}'s so the two families cannot be confused.
     */
    private static final int TIMEOUT_REQUEST_BASE = 0x4E510000;

    /** First element of the returned array: the system side handled the request successfully. */
    static final int ACK = 0x4E504D01;
    /** The system side was reached but the request itself failed. */
    static final int NAK = 0x4E504D02;

    static final String REASON_RECOVERY = "recovery";
    static final String REASON_BOOTLOADER = "bootloader";

    private SystemBridge() {
    }

    static String methodName() {
        return "getDisableFlags";
    }

    /** The exact signature the client resolves on the binder proxy. */
    static Class<?>[] methodSignature() {
        return new Class<?>[]{IBinder.class, int.class};
    }

    // ------------------------------------------------------------------ request encoding

    static int requestCode(String reason) {
        if (REASON_RECOVERY.equals(reason)) {
            return REQUEST_RECOVERY;
        }
        if (REASON_BOOTLOADER.equals(reason)) {
            return REQUEST_BOOTLOADER;
        }
        return -1;
    }

    /** Returns {@code null} unless {@code code} is one of our own, well-known reboot requests. */
    static String reasonFromRequestCode(int code) {
        if (code == REQUEST_RECOVERY) {
            return REASON_RECOVERY;
        }
        if (code == REQUEST_BOOTLOADER) {
            return REASON_BOOTLOADER;
        }
        return null;
    }

    static int timeoutRequestCode(int ms) {
        return TIMEOUT_REQUEST_BASE | (ms & 0xFFFF);
    }

    /** Returns the requested timeout in milliseconds, or {@code -1} for a non-timeout request. */
    static int timeoutFromRequestCode(int code) {
        if ((code & 0xFFFF0000) != TIMEOUT_REQUEST_BASE) {
            return -1;
        }
        return code & 0xFFFF;
    }

    // ------------------------------------------------------------------ system_server side

    /**
     * Runs inside system_server. {@code IPowerManager.reboot} is called by the system process
     * itself, which always passes the platform's own permission checks - the same path
     * {@code StatusBarManagerService} and {@code ShutdownThread} use.
     *
     * <p><b>The {@code clearCallingIdentity()} is essential.</b> This runs while the current thread
     * is still servicing SystemUI's binder call into system_server, and a nested same-process call
     * inherits that thread's calling identity - without clearing it,
     * {@code PowerManagerService$BinderService.reboot} sees uid 10237 (SystemUI) and throws
     * {@code SecurityException: Neither user 10237 nor current process has
     * android.permission.REBOOT}. ColorOS' own {@code StatusBarManagerService.reboot} clears the
     * identity for exactly the same reason.
     */
    static boolean reboot(String reason, ClassLoader classLoader) {
        ModuleLog.d("system_server: rebooting to " + reason);
        long identity = Binder.clearCallingIdentity();
        try {
            Object binder = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.ServiceManager", classLoader),
                    "getService", "power");
            Object powerManager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.IPowerManager$Stub", classLoader),
                    "asInterface", binder);
            // Resolved by exact signature: the arguments are primitives, and callMethod() would
            // have to rely on autoboxing-aware matching to find it.
            // wait=false, so this returns as soon as the shutdown is scheduled.
            XposedHelpers.findMethodExact(powerManager.getClass(), "reboot",
                            boolean.class, String.class, boolean.class)
                    .invoke(powerManager, false, reason, false);
            return true;
        } catch (Throwable t) {
            ModuleLog.e("system_server: reboot(" + reason + ") failed", t);
        } finally {
            Binder.restoreCallingIdentity(identity);
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
