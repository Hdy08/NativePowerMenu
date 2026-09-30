package com.nativepowermenu;

import de.robv.android.xposed.XposedHelpers;

/**
 * Tolerant reflective field/method reader.
 *
 * <p>SystemUI's internal classes are not stable across ROMs: on this ColorOS build
 * {@code KeyguardStateController} is a much smaller interface than in AOSP and exposes neither
 * {@code isShowing()} nor {@code isMethodSecure()} - the values only exist as the
 * {@code KeyguardStateControllerImpl.mShowing} / {@code mSecure} fields. Reading the field first and
 * falling back to the method (or the other way round) keeps the module working on both layouts.
 */
final class Reflect {

    private Reflect() {
    }

    static boolean booleanValue(Object target, String fieldName, String methodName,
            boolean fallback) {
        if (target == null) {
            return fallback;
        }
        if (fieldName != null) {
            try {
                return XposedHelpers.getBooleanField(target, fieldName);
            } catch (Throwable ignored) {
                // Fall through to the accessor.
            }
        }
        if (methodName != null) {
            try {
                Object value = XposedHelpers.callMethod(target, methodName);
                if (value instanceof Boolean) {
                    return (Boolean) value;
                }
            } catch (Throwable ignored) {
                // Fall through to the default.
            }
        }
        ModuleLog.d("neither " + fieldName + " nor " + methodName + "() is available on "
                + target.getClass().getName() + ", assuming " + fallback);
        return fallback;
    }
}
