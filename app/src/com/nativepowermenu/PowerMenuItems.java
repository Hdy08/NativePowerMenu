package com.nativepowermenu;

import android.content.Context;
import android.content.pm.PackageManager;
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

    /** Framework drawable id, or 0 for the module-local extension icons and unknown keys. */
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

    static Drawable icon(Context context, String key, boolean hyperOs) {
        String aospName = aospIconName(key);
        Drawable stock = aospName == null ? null : systemUiIcon(context, aospName);
        if (stock != null) {
            return stock;
        }
        try {
            switch (key) {
                case EMERGENCY:
                    if (hyperOs) {
                        Drawable hyperOsEmergency = systemUiIcon(
                                context, "ic_global_actions_emergency");
                        if (hyperOsEmergency != null) {
                            return hyperOsEmergency;
                        }
                    }
                    int emergencyId = frameworkIconId(context.getResources(), key);
                    return emergencyId != 0 ? context.getDrawable(emergencyId) : null;
                case BOOTLOADER:
                    return moduleDrawable(context, R.drawable.ic_bootloader);
                case RECOVERY:
                    return moduleDrawable(context, R.drawable.ic_recovery);
                default:
                    if (hyperOs && LOCKDOWN.equals(key)) {
                        Drawable hyperOsLockdown = systemUiIcon(
                                context, "ic_global_actions_lockdown");
                        if (hyperOsLockdown != null) {
                            return hyperOsLockdown;
                        }
                    }
                    Resources res = context.getResources();
                    int id = frameworkIconId(res, key);
                    if (id == 0 && hyperOs && LOCKDOWN.equals(key)) {
                        id = ResourceLookup.drawableId(
                                res, ResourceLookup.PKG_ANDROID, "ic_lock_lockdown_round");
                        if (id == 0) {
                            id = android.R.drawable.ic_lock_lock;
                        }
                    }
                    return id != 0 ? context.getDrawable(id) : null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String aospIconName(String key) {
        switch (key) {
            case POWER:
                return "ic_lock_power_off";
            case RESTART:
                return "ic_restart";
            case SCREENSHOT:
                return "ic_screenshot";
            case EMERGENCY:
                return "ic_emergency_star";
            case LOCKDOWN:
                return "ic_lock_lockdown";
            default:
                return null;
        }
    }

    private static Drawable systemUiIcon(Context context, String name) {
        String pkg = ResourceLookup.PKG_SYSTEMUI;
        try {
            // The settings preview needs SystemUI's resource table, but not its code.
            Context owner = systemUiContext(context);
            int id = ResourceLookup.drawableId(owner.getResources(), pkg, name);
            return id != 0 ? owner.getDrawable(id) : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    private static Context systemUiContext(Context context)
            throws PackageManager.NameNotFoundException {
        String pkg = ResourceLookup.PKG_SYSTEMUI;
        return pkg.equals(context.getPackageName())
                ? context : context.createPackageContext(pkg, 0);
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

    /** Menu labels from the module resources so vendor SystemUI fallbacks cannot leak English. */
    static CharSequence label(Context context, String key) {
        switch (key) {
            case POWER:
                return ModuleResources.string(context, R.string.power_off_title, "关机");
            case RESTART:
                return ModuleResources.string(context, R.string.restart_title, "重启");
            case SCREENSHOT:
                return ModuleResources.string(context, R.string.screenshot_title, "截屏");
            case EMERGENCY:
                return ModuleResources.string(context, R.string.emergency_call_title, "紧急呼叫");
            case LOCKDOWN:
                return ModuleResources.string(context, R.string.lockdown_title, "锁定");
            case BOOTLOADER:
                return ModuleResources.string(context, R.string.reboot_bootloader_title, "引导模式");
            case RECOVERY:
                return ModuleResources.string(context, R.string.reboot_recovery_title, "恢复模式");
            default:
                return key;
        }
    }
}
