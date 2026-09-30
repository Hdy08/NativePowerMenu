package com.nativepowermenu;

import android.graphics.drawable.Drawable;

/**
 * Immutable description of one button in the power menu.
 *
 * <p>Mirrors AOSP's {@code GlobalActionsDialogLite.Action}: an icon, a label, an optional long-press
 * handler, plus an optional confirmation step. The confirmation is what the extended entries
 * (bootloader / recovery) use - a stray tap must not drop the phone into fastboot.
 */
final class PowerMenuItem {

    /** Key used by AOSP's {@code config_globalActionsList}, e.g. {@code power}. */
    final String key;

    /**
     * Drawable resource id. Framework drawables use the {@code android} package ids, extended
     * entries use the module's own ids resolved through {@link ModuleResources}.
     */
    final int iconResId;

    /**
     * Non-null when the icon must come from the module APK instead of {@code iconResId};
     * {@link ModuleResources} only works with a live host context, so it is resolved lazily.
     */
    final Drawable iconDrawable;

    final CharSequence label;
    /** Emergency entries are drawn with the red emergency accent, exactly like AOSP. */
    final boolean emergency;

    /** When set, the action is only performed after the user confirms. */
    final CharSequence confirmTitle;
    final CharSequence confirmMessage;

    final Runnable onPress;
    /** May be {@code null}; mirrors AOSP's {@code LongPressAction}. */
    final Runnable onLongPress;

    PowerMenuItem(String key, int iconResId, Drawable iconDrawable, CharSequence label,
            boolean emergency, CharSequence confirmTitle, CharSequence confirmMessage,
            Runnable onPress, Runnable onLongPress) {
        this.key = key;
        this.iconResId = iconResId;
        this.iconDrawable = iconDrawable;
        this.label = label;
        this.emergency = emergency;
        this.confirmTitle = confirmTitle;
        this.confirmMessage = confirmMessage;
        this.onPress = onPress;
        this.onLongPress = onLongPress;
    }

    boolean needsConfirmation() {
        return confirmTitle != null;
    }
}
