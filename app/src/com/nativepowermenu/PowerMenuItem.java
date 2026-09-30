package com.nativepowermenu;

/** Immutable description of one button in the power menu. */
final class PowerMenuItem {

    /** Key used by AOSP's {@code config_globalActionsList}, e.g. {@code power}. */
    final String key;
    /** Drawable from the {@code android} package, e.g. {@code ic_lock_power_off}. */
    final int iconResId;
    final CharSequence label;
    /** Emergency entries are drawn with the red emergency accent, exactly like AOSP. */
    final boolean emergency;
    final Runnable onPress;
    /** May be {@code null}; mirrors AOSP's {@code LongPressAction}. */
    final Runnable onLongPress;

    PowerMenuItem(String key, int iconResId, CharSequence label, boolean emergency,
            Runnable onPress, Runnable onLongPress) {
        this.key = key;
        this.iconResId = iconResId;
        this.label = label;
        this.emergency = emergency;
        this.onPress = onPress;
        this.onLongPress = onLongPress;
    }
}
