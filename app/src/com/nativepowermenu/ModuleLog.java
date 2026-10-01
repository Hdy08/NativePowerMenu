package com.nativepowermenu;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Thin logging facade. Everything is mirrored to logcat under the {@value #TAG} tag so the behaviour
 * can be watched while testing, and to the LSPosed log when there is one.
 *
 * <p>The Xposed API only exists inside hooked processes; the settings app has neither it nor any
 * need for it, so it is reached through reflection and every call is guarded. Referencing it
 * directly once killed the settings screen with a {@code NoClassDefFoundError} thrown from a layout
 * callback.
 */
public final class ModuleLog {

    public static final String TAG = "NativePowerMenu";

    private static final String XPOSED_BRIDGE = "de.robv.android.xposed.XposedBridge";

    /** {@code null} until probed; the answer cannot change inside a process. */
    private static Boolean sXposed;
    private static Method sXposedLog;

    private ModuleLog() {
    }

    public static void d(String message) {
        Log.d(TAG, message);
        toXposed(TAG + ": " + message);
    }

    public static void w(String message) {
        Log.w(TAG, message);
        toXposed(TAG + ": W " + message);
    }

    public static void w(String message, Throwable throwable) {
        Log.w(TAG, message, throwable);
        toXposed(TAG + ": W " + message + " (" + throwable + ")");
    }

    public static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        toXposed(TAG + ": E " + message
                + (throwable == null ? "" : " (" + throwable + ")"));
        if (throwable != null) {
            toXposed(Log.getStackTraceString(throwable));
        }
    }

    private static void toXposed(String line) {
        if (!xposedAvailable()) {
            return;
        }
        try {
            sXposedLog.invoke(null, line);
        } catch (Throwable t) {
            // Should not happen once the probe succeeded, but logging must never be fatal.
            sXposed = Boolean.FALSE;
        }
    }

    private static boolean xposedAvailable() {
        if (sXposed == null) {
            try {
                Class<?> bridge = Class.forName(
                        XPOSED_BRIDGE, false, ModuleLog.class.getClassLoader());
                sXposedLog = bridge.getMethod("log", String.class);
                sXposed = Boolean.TRUE;
            } catch (Throwable t) {
                sXposed = Boolean.FALSE;
            }
        }
        return sXposed;
    }
}
