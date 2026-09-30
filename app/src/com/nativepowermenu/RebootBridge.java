package com.nativepowermenu;

import android.os.Binder;

import de.robv.android.xposed.XposedHelpers;

/**
 * The two ends of the reboot-with-reason channel.
 *
 * <p>Why a channel at all: {@code PowerManager.reboot(String)} is guarded by
 * {@code android.permission.REBOOT}, and SystemUI does not hold it (verified with
 * {@code dumpsys package com.android.systemui} - the permission is not even requested). Only the
 * system process may reboot with a custom reason, which is exactly how ColorOS' own
 * {@code StatusBarManagerService.reboot(boolean)} works.
 *
 * <p>So the module also runs in system_server and uses
 * {@code StatusBarManagerService.setIcon(String slot, ...)} as a private carrier. That method is
 * dead in modern Android (it only logs a deprecation warning), SystemUI already holds the
 * {@code IStatusBarService} binder it lives behind, and it is protected by
 * {@code STATUS_BAR_SERVICE} - a permission only SystemUI has. The hook recognises a magic
 * {@code slot} prefix, swallows the call before the real icon bookkeeping happens, and performs the
 * reboot instead.
 */
final class RebootBridge {

    /** Magic prefix that turns a status-bar icon slot into a reboot request. */
    static final String TOKEN_PREFIX = "native_power_menu:";

    static final String REASON_RECOVERY = "recovery";
    static final String REASON_BOOTLOADER = "bootloader";

    private RebootBridge() {
    }

    static String token(String reason) {
        return TOKEN_PREFIX + reason;
    }

    /** Returns {@code null} unless {@code token} is one of our own, well-known requests. */
    static String reasonFromToken(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        String reason = token.substring(TOKEN_PREFIX.length());
        if (REASON_RECOVERY.equals(reason) || REASON_BOOTLOADER.equals(reason)) {
            return reason;
        }
        ModuleLog.w("ignoring unsupported reboot reason '" + reason + "'");
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
            XposedHelpers.callMethod(powerManager, "reboot",
                    Boolean.FALSE, reason, Boolean.FALSE);
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
