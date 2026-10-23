package com.spotled.app;

/** Compact raster text renderer backed by generated shared 3x5 glyphs. */
final class CompactTextRaster {
    static final class PanelFrames {
        final RgbTransforms.Frame preview;
        final RgbTransforms.Frame wire;
        PanelFrames(RgbTransforms.Frame preview, RgbTransforms.Frame wire) {
            this.preview = preview; this.wire = wire;
        }
    }

    private CompactTextRaster() {}

    static String[] rowsFor(int codePoint) {
        return GeneratedGlyphs.compactRowsFor(codePoint);
    }

    static RgbTransforms.Frame render(String text, int paletteIndex) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("Text must not be empty");
        int[] codePoints = text.codePoints().toArray();
        int width = codePoints.length * 4 - 1;
        byte[] rgb = new byte[width * 5 * 3];
        for (int i = 0; i < codePoints.length; i++) {
            String[] glyph = rowsFor(codePoints[i]);
            int color = TextPalette.colorFor(paletteIndex, i, codePoints.length);
            byte red = (byte) (color >> 16);
            byte green = (byte) (color >> 8);
            byte blue = (byte) color;
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 3; x++) {
                    if (glyph[y].charAt(x) == '1') {
                        int offset = (y * width + i * 4 + x) * 3;
                        rgb[offset] = red;
                        rgb[offset + 1] = green;
                        rgb[offset + 2] = blue;
                    }
                }
            }
        }
        return new RgbTransforms.Frame(width, 5, rgb);
    }

    static PanelFrames renderForPanel(String text, int paletteIndex, int orientationDegrees) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("Text must not be empty");
        if (orientationDegrees != 0 && orientationDegrees != 90 && orientationDegrees != 270)
            throw new IllegalArgumentException("Text orientation must be 0, 90, or 270 degrees");
        if (orientationDegrees != 0) return renderRotatedNativePreview(text, paletteIndex, orientationDegrees);
        RgbTransforms.Frame source = render(text, paletteIndex);
        RgbTransforms.Frame panel = RgbTransforms.fit(source.rgb, source.width,
                source.height, SpotLedProtocol.SCREEN_WIDTH, SpotLedProtocol.SCREEN_HEIGHT, "contain");
        return new PanelFrames(panel, panel);
    }

    private static PanelFrames renderRotatedNativePreview(String text, int paletteIndex,
                                                           int orientationDegrees) {
        int[] codePoints = text.codePoints().toArray();
        byte[] pixels = new byte[SpotLedProtocol.SCREEN_WIDTH
                * SpotLedProtocol.SCREEN_HEIGHT * 3];
        for (int index = 0; index < codePoints.length; index++) {
            int xStart = index * 16;
            if (xStart >= SpotLedProtocol.SCREEN_WIDTH) break;
            byte[] glyph = SpotLedProtocol.rotatedFontObject((char) codePoints[index], orientationDegrees);
            int color = TextPalette.colorFor(paletteIndex, index, codePoints.length);
            for (int y = 0; y < 16; y++) {
                int rowOffset = 14 + y * 2;
                int bits = ((glyph[rowOffset] & 0xff) << 8) | (glyph[rowOffset + 1] & 0xff);
                for (int x = 0; x < 16 && xStart + x < SpotLedProtocol.SCREEN_WIDTH; x++) {
                    if ((bits & (0x8000 >>> x)) == 0) continue;
                    int offset = (y * SpotLedProtocol.SCREEN_WIDTH + xStart + x) * 3;
                    pixels[offset] = (byte) (color >> 16);
                    pixels[offset + 1] = (byte) (color >> 8);
                    pixels[offset + 2] = (byte) color;
                }
            }
        }
        RgbTransforms.Frame panel = new RgbTransforms.Frame(SpotLedProtocol.SCREEN_WIDTH,
                SpotLedProtocol.SCREEN_HEIGHT, pixels);
        return new PanelFrames(panel, panel);
    }
}
