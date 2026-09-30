package com.nativepowermenu;

import android.content.res.Resources;

/**
 * Resolves resources by name.
 *
 * <p>The module deliberately ships no UI resources of its own: every dimension, colour, label and
 * icon is taken from the resources that are already installed on the device. Framework drawables
 * such as {@code android:drawable/ic_restart} come from {@code framework-res.apk}, while the layout
 * metrics come from {@code com.android.systemui}, which is exactly what AOSP's own power menu uses.
 * That keeps the module independent of any particular ColorOS version, and it automatically follows
 * the user's language and theme.
 */
final class ResourceLookup {

    static final String PKG_ANDROID = "android";
    static final String PKG_SYSTEMUI = "com.android.systemui";

    private ResourceLookup() {
    }

    static int dimen(Resources res, String pkg, String name, int fallbackPx) {
        int id = res.getIdentifier(name, "dimen", pkg);
        if (id == 0) {
            return fallbackPx;
        }
        try {
            return res.getDimensionPixelSize(id);
        } catch (Throwable t) {
            return fallbackPx;
        }
    }

    static int integer(Resources res, String pkg, String name, int fallback) {
        int id = res.getIdentifier(name, "integer", pkg);
        if (id == 0) {
            return fallback;
        }
        try {
            return res.getInteger(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static int color(Resources res, String pkg, String name, int fallback) {
        int id = res.getIdentifier(name, "color", pkg);
        if (id == 0) {
            return fallback;
        }
        try {
            return res.getColor(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static int drawableId(Resources res, String pkg, String name) {
        return res.getIdentifier(name, "drawable", pkg);
    }

    static String string(Resources res, String pkg, String name, String fallback) {
        int id = res.getIdentifier(name, "string", pkg);
        if (id == 0) {
            return fallback;
        }
        try {
            return res.getString(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static String[] stringArray(Resources res, String pkg, String name) {
        int id = res.getIdentifier(name, "array", pkg);
        if (id == 0) {
            return null;
        }
        try {
            return res.getStringArray(id);
        } catch (Throwable t) {
            return null;
        }
    }

    static int styleId(Resources res, String pkg, String name) {
        return res.getIdentifier(name, "style", pkg);
    }
}
