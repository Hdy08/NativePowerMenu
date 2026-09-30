package com.nativepowermenu;

import android.graphics.drawable.Drawable;

/**
 * Immutable description of one button in the power menu.
 *
 * <p>Mirrors AOSP's {@code GlobalActionsDialogLite.Action}: an icon, a label and an optional
 * long-press handler. Every entry acts immediately - the extended entries deliberately have no
 * confirmation step, so a tap on "bootloader" or "recovery" reboots straight away.
 */
final class PowerMenuItem {

    /** Key from {@link PowerMenuItems}, e.g. {@code power}. */
    final String key;

    /** Already resolved: framework drawables and the module's own icons both land here. */
    final Drawable icon;

    final CharSequence label;
    /** Emergency entries are drawn with the red emergency accent, exactly like AOSP. */
    final boolean emergency;

    final Runnable onPress;
    /** May be {@code null}; mirrors AOSP's {@code LongPressAction}. */
    final Runnable onLongPress;

    PowerMenuItem(String key, Drawable icon, CharSequence label, boolean emergency,
            Runnable onPress, Runnable onLongPress) {
        this.key = key;
        this.icon = icon;
        this.label = label;
        this.emergency = emergency;
        this.onPress = onPress;
        this.onLongPress = onLongPress;
    }
}
