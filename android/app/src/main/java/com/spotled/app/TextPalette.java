package com.spotled.app;

/** Static text palette; Rainbow is a spatial hue gradient, not a flashing animation. */
final class TextPalette {
    static final String[] NAMES = {
            "White", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Magenta", "Rainbow (gradient)"
    };

    private TextPalette() {}

    static int colorFor(int paletteIndex, int characterIndex, int characterCount) {
        switch (paletteIndex) {
            case 0: return 0xffffffff;
            case 1: return 0xffff0000;
            case 2: return 0xffff8000;
            case 3: return 0xffffff00;
            case 4: return 0xff00ff00;
            case 5: return 0xff00ffff;
            case 6: return 0xff0000ff;
            case 7: return 0xffff00ff;
            case 8:
                if (characterCount <= 1) return 0xffff0000;
                return hsvToArgb((360.0 * characterIndex) / characterCount);
            default: throw new IllegalArgumentException("Unknown text color selection");
        }
    }

    private static int hsvToArgb(double hue) {
        double h = ((hue % 360.0) + 360.0) % 360.0 / 60.0;
        double x = 1.0 - Math.abs((h % 2.0) - 1.0);
        double r, g, b;
        if (h < 1) { r = 1; g = x; b = 0; }
        else if (h < 2) { r = x; g = 1; b = 0; }
        else if (h < 3) { r = 0; g = 1; b = x; }
        else if (h < 4) { r = 0; g = x; b = 1; }
        else if (h < 5) { r = x; g = 0; b = 1; }
        else { r = 1; g = 0; b = x; }
        int red = (int) Math.round(r * 255.0);
        int green = (int) Math.round(g * 255.0);
        int blue = (int) Math.round(b * 255.0);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }
}
