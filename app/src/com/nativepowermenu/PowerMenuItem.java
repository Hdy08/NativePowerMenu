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

    /** Key from {@link PowerMenuItems}, e.g. {@code power}. */
    final String key;

    /** Already resolved: framework drawables and the module's own icons both land here. */
    final Drawable icon;

    final CharSequence label;
    /** Emergency entries are drawn with the red emergency accent, exactly like AOSP. */
    final boolean emergency;

    /** When set, the action is only performed after the user confirms. */
    final CharSequence confirmTitle;
    final CharSequence confirmMessage;

    final Runnable onPress;
    /** May be {@code null}; mirrors AOSP's {@code LongPressAction}. */
    final Runnable onLongPress;

    PowerMenuItem(String key, Drawable icon, CharSequence label, boolean emergency,
            CharSequence confirmTitle, CharSequence confirmMessage,
            Runnable onPress, Runnable onLongPress) {
        this.key = key;
        this.icon = icon;
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
