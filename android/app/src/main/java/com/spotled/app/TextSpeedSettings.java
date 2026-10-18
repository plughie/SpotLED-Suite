package com.spotled.app;

/** Shared Android labels and millisecond intervals for SpotLED text speed choices. */
final class TextSpeedSettings {
    private static final String[] LABELS = {
            "Slow (300 ms)", "250 ms", "Normal (200 ms)", "150 ms", "Fast (100 ms)"
    };
    private static final int[] TIMES_MS = {300, 250, 200, 150, 100};

    private TextSpeedSettings() { }

    static String[] labels() { return LABELS.clone(); }

    static int millisecondsForPosition(int position) {
        if (position < 0 || position >= TIMES_MS.length)
            throw new IllegalArgumentException("unknown text speed position");
        return TIMES_MS[position];
    }

    static int positionForMilliseconds(int milliseconds) {
        for (int i = 0; i < TIMES_MS.length; i++)
            if (TIMES_MS[i] == milliseconds) return i;
        if (milliseconds >= 275) return 0;
        if (milliseconds >= 225) return 1;
        if (milliseconds >= 175) return 2;
        if (milliseconds >= 125) return 3;
        return 4;
    }
}
