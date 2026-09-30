package com.nativepowermenu;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * Thin logging facade. Everything is mirrored to logcat under the {@value #TAG} tag so that the
 * behaviour can be inspected with {@code logcat -s NativePowerMenu} while testing, and to the
 * LSPosed log for end users.
 */
public final class ModuleLog {

    public static final String TAG = "NativePowerMenu";

    private ModuleLog() {
    }

    public static void d(String message) {
        Log.d(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    public static void w(String message) {
        Log.w(TAG, message);
        XposedBridge.log(TAG + ": W " + message);
    }

    public static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        XposedBridge.log(TAG + ": E " + message);
        XposedBridge.log(throwable);
    }
}
