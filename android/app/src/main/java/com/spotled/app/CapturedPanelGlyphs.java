package com.spotled.app;

/** Compatibility accessor for captured native glyphs generated from glyph_data. */
final class CapturedPanelGlyphs {
    private CapturedPanelGlyphs() {}

    static byte[] fontObject(char ch) {
        return GeneratedGlyphs.capturedPanel16(ch);
    }
}
