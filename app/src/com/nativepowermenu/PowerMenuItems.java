package com.nativepowermenu;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything that is shared between the settings app and the SystemUI process.
 *
 * <p>Single source of truth for the item keys, their canonical order, their labels, their icons and
 * - importantly - which of them may never be turned off.
 */
final class PowerMenuItems {

    static final String POWER = "power";
    static final String RESTART = "restart";
    static final String SCREENSHOT = "screenshot";
    static final String EMERGENCY = "emergency";
    static final String LOCKDOWN = "lockdown";
    static final String BOOTLOADER = "bootloader";
    static final String RECOVERY = "recovery";

    /** Everything this module knows how to render. */
    static final List<String> SUPPORTED = Collections.unmodifiableList(Arrays.asList(
            EMERGENCY, LOCKDOWN, POWER, RESTART, SCREENSHOT, BOOTLOADER, RECOVERY));

    /** Order used when {@code config_globalActionsList} cannot be read. */
    static final List<String> DEFAULT_ORDER = Collections.unmodifiableList(Arrays.asList(
            EMERGENCY, LOCKDOWN, POWER, RESTART, SCREENSHOT));

    /**
     * A power menu without these is a brick: they can be reordered but never disabled.
     */
    static final Set<String> ALWAYS_ON = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(POWER, RESTART)));

    private PowerMenuItems() {
    }

    static boolean isSupported(String key) {
        return key != null && SUPPORTED.contains(key);
    }

    // ---------------------------------------------------------------- icons

    /** Framework drawable id, or 0 for the module-local icons and unknown keys. */
    static int frameworkIconId(Resources res, String key) {
        switch (key) {
            case POWER:
                return ResourceLookup.drawableId(
                        res, ResourceLookup.PKG_ANDROID, "ic_lock_power_off");
            case RESTART:
                return ResourceLookup.drawableId(res, ResourceLookup.PKG_ANDROID, "ic_restart");
            case SCREENSHOT:
                return ResourceLookup.drawableId(res, ResourceLookup.PKG_ANDROID, "ic_screenshot");
            case EMERGENCY:
                return ResourceLookup.drawableId(res, ResourceLookup.PKG_ANDROID, "emergency_icon");
            case LOCKDOWN:
                return ResourceLookup.drawableId(
                        res, ResourceLookup.PKG_ANDROID, "ic_lock_lockdown");
            default:
                // bootloader / recovery ship with the module.
                return 0;
        }
    }

    static Drawable icon(Context context, String key) {
        try {
            switch (key) {
                case BOOTLOADER:
                    return moduleDrawable(context, R.drawable.ic_bootloader);
                case RECOVERY:
                    return moduleDrawable(context, R.drawable.ic_recovery);
                default:
                    int id = frameworkIconId(context.getResources(), key);
                    return id != 0 ? context.getDrawable(id) : null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolves one of the module's own drawables.
     *
     * <p>This has to go through {@link ModuleResources} whenever the host is not the module's own
     * process: {@code R.drawable.ic_bootloader} is an id in the module's resource table, and handing
     * it to SystemUI's {@code Resources} looks up an unrelated entry (or throws) instead.
     */
    private static Drawable moduleDrawable(Context context, int resId) {
        if (ModuleResources.PACKAGE_NAME.equals(context.getPackageName())) {
            try {
                return context.getDrawable(resId);
            } catch (Throwable ignored) {
                // Fall through to the module resources path below.
            }
        }
        return ModuleResources.drawable(context, resId);
    }

    // ---------------------------------------------------------------- text

    /** Menu label, from framework-res so it follows the system language. */
    static CharSequence label(Context context, String key) {
        Resources res = context.getResources();
        switch (key) {
            case POWER:
                return ResourceLookup.string(res, ResourceLookup.PKG_ANDROID,
                        "global_action_power_off", "Power off");
            case RESTART:
                return ResourceLookup.string(res, ResourceLookup.PKG_ANDROID,
                        "global_action_restart", "Restart");
            case SCREENSHOT:
                return ResourceLookup.string(res, ResourceLookup.PKG_ANDROID,
                        "global_action_screenshot", "Screenshot");
            case EMERGENCY:
                return ResourceLookup.string(res, ResourceLookup.PKG_ANDROID,
                        "global_action_emergency", "Emergency");
            case LOCKDOWN:
                return ResourceLookup.string(res, ResourceLookup.PKG_ANDROID,
                        "global_action_lockdown", "Lockdown");
            case BOOTLOADER:
                return ModuleResources.string(context, R.string.reboot_bootloader_title, "Bootloader");
            case RECOVERY:
                return ModuleResources.string(context, R.string.reboot_recovery_title, "Recovery");
            default:
                return key;
        }
    }
}
