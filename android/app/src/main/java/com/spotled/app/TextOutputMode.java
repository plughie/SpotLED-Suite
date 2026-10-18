package com.spotled.app;

/** Selects native panel text unless raster rendering is requested or rotation requires pixels. */
final class TextOutputMode {
    private TextOutputMode() {}

    static boolean useNativeText(boolean rasterRequested, int rotationDegrees) {
        return !rasterRequested && rotationDegrees == 0;
    }
}
