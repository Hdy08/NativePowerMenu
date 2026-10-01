package com.nativepowermenu;

/**
 * The power long-press timeout setting: its bounds, the slider granularity, and the validation both
 * ends of the channel apply.
 *
 * <p>Deliberately free of Xposed and Android imports: the settings app (which never loads the
 * Xposed API) and system_server both use this class, and neither should pull the other's
 * dependencies in.
 */
final class LongPress {

    /** The shortest delay offered; below this the menu would appear from an accidental press. */
    static final int MIN_MS = 500;
    /**
     * The longest delay offered. ColorOS starts its hardware-shutdown bookkeeping 3 s into a press,
     * but that only records state and flushes databases - the platform's own force-off is handled in
     * hardware well beyond this - so 5 s is safe.
     */
    static final int MAX_MS = 5000;
    /** Slider granularity. Exact values can be typed in, so the slider does not have to be fine. */
    static final int STEP_MS = 100;

    /**
     * What ColorOS hardcodes as the power key's delay ({@code SingleKeyGestureDetectorExtImpl
     * .modifyPressTimeout} returns 2500 for the power key). Used to position the slider until
     * system_server reports the value it really observed, which happens on the first key press.
     */
    static final int DEVICE_DEFAULT_MS = 2500;

    private LongPress() {
    }

    /** {@code 0} (leave the device's own value alone), or a value inside the supported range. */
    static int clamp(int ms) {
        if (ms <= 0) {
            return 0;
        }
        return Math.max(MIN_MS, Math.min(MAX_MS, ms));
    }

    /** Number of slider steps between {@link #MIN_MS} and {@link #MAX_MS}. */
    static int steps() {
        return (MAX_MS - MIN_MS) / STEP_MS;
    }

    static int stepToMs(int step) {
        int clamped = Math.max(0, Math.min(steps(), step));
        return MIN_MS + clamped * STEP_MS;
    }

    /** The step closest to {@code ms}, so a typed value still lands somewhere sensible on the bar. */
    static int msToStep(int ms) {
        int step = Math.round((ms - MIN_MS) / (float) STEP_MS);
        return Math.max(0, Math.min(steps(), step));
    }
}
