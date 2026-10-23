package com.spotled.app;

/** Selects native text transport for normal landscape and rotated native glyph text. */
final class TextOutputMode {
    private TextOutputMode() {}

    static boolean useNativeText(boolean rasterRequested, int orientationDegrees) {
        return orientationDegrees == 270
                || (!rasterRequested && orientationDegrees == 0);
    }
}
