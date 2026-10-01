package com.nativepowermenu;

/**
 * The power long-press timeout setting: its bounds, the presets the settings screen offers, and the
 * validation both ends of the channel apply.
 *
 * <p>Deliberately free of Xposed and Android imports: the settings app (which never loads the
 * Xposed API) and system_server both use this class, and neither should pull the other's
 * dependencies in.
 */
final class LongPress {

    /** Below this the menu would appear from an accidental tap. */
    static final int MIN_MS = 100;
    /** Kept well below {@code config_veryLongPressTimeout} (3500 ms on this device). */
    static final int MAX_MS = 3000;

    /** Offered by the settings screen; {@code 0} means "leave the framework's own value alone". */
    static final int[] PRESETS = {0, 150, 200, 300, 400, 500, 700, 1000, 1500};

    private LongPress() {
    }

    /** {@code 0}, or a value the system-server side is willing to apply. */
    static int clamp(int ms) {
        if (ms <= 0) {
            return 0;
        }
        return Math.max(MIN_MS, Math.min(MAX_MS, ms));
    }

    /** Position of {@code ms} in {@link #PRESETS}, or the nearest one. */
    static int presetIndex(int ms) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < PRESETS.length; i++) {
            int distance = Math.abs(PRESETS[i] - ms);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
