package com.nativepowermenu;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XModuleResources;
import android.content.res.XResources;
import android.graphics.drawable.Drawable;

/**
 * Access to the module's own resources from inside the SystemUI process.
 *
 * <p>The power menu itself draws only with the device's resources, so the module APK's resources are
 * needed for just two things: the labels of the extended entries and their icons.
 *
 * <p>Two ways in, in order of preference: a package context for the module APK (plain Android, no
 * Xposed magic) and {@code XModuleResources} (which re-points the module's ids at the host
 * process).
 */
final class ModuleResources {

    static final String PACKAGE_NAME = "com.nativepowermenu";

    private static String sModulePath;
    private static Resources sResources;

    private ModuleResources() {
    }

    /** Captured from {@code IXposedHookZygoteInit#initZygote}. */
    static void setModulePath(String modulePath) {
        if (modulePath != null && !modulePath.isEmpty()) {
            sModulePath = modulePath;
        }
    }

    static synchronized Resources get(Context hostContext) {
        if (sResources != null) {
            return sResources;
        }
        sResources = fromPackageContext(hostContext);
        if (sResources == null) {
            sResources = fromXModuleResources(hostContext);
        }
        if (sResources == null) {
            ModuleLog.w("module resources are unavailable, extended entries fall back to plain text");
        }
        return sResources;
    }

    static String string(Context hostContext, int resId, String fallback) {
        Resources resources = get(hostContext);
        if (resources == null) {
            return fallback;
        }
        try {
            return resources.getString(resId);
        } catch (Throwable t) {
            ModuleLog.w("could not resolve module string 0x" + Integer.toHexString(resId), t);
            return fallback;
        }
    }

    /** Returns {@code null} when the icon cannot be resolved. */
    static Drawable drawable(Context hostContext, int resId) {
        Resources resources = get(hostContext);
        if (resources == null) {
            return null;
        }
        try {
            // No theme: the host's theme belongs to a different Resources, and these drawables have
            // no theme attributes anyway.
            return resources.getDrawable(resId, null);
        } catch (Throwable t) {
            ModuleLog.w("could not resolve module drawable 0x" + Integer.toHexString(resId), t);
            return null;
        }
    }

    private static Resources fromPackageContext(Context hostContext) {
        try {
            return hostContext.createPackageContext(PACKAGE_NAME, 0).getResources();
        } catch (Throwable t) {
            ModuleLog.w("no package context for " + PACKAGE_NAME + ": " + t);
            return null;
        }
    }

    private static Resources fromXModuleResources(Context hostContext) {
        String path = sModulePath;
        if (path == null) {
            try {
                path = hostContext.getPackageManager()
                        .getApplicationInfo(PACKAGE_NAME, 0).sourceDir;
            } catch (Throwable t) {
                ModuleLog.w("module APK path unknown: " + t);
                return null;
            }
        }
        try {
            // Resources in a hooked process are XResources instances; the cast is the price of the
            // legacy Xposed API signature.
            return XModuleResources.createInstance(path, (XResources) hostContext.getResources());
        } catch (Throwable t) {
            ModuleLog.w("XModuleResources.createInstance failed for " + path, t);
            return null;
        }
    }
}
