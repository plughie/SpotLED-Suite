package com.spotled.app;

import java.util.HashMap;
import java.util.Map;

/** The same independently authored 3x5 glyphs and spacing as the Python reference. */
final class CompactTextRaster {
    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    static {
        glyph('A', "010", "101", "111", "101", "101");
        glyph('B', "110", "101", "110", "101", "110");
        glyph('C', "011", "100", "100", "100", "011");
        glyph('D', "110", "101", "101", "101", "110");
        glyph('E', "111", "100", "110", "100", "111");
        glyph('F', "111", "100", "110", "100", "100");
        glyph('G', "011", "100", "101", "101", "011");
        glyph('H', "101", "101", "111", "101", "101");
        glyph('I', "111", "010", "010", "010", "111");
        glyph('J', "001", "001", "001", "101", "010");
        glyph('K', "101", "101", "110", "101", "101");
        glyph('L', "100", "100", "100", "100", "111");
        glyph('M', "101", "111", "111", "101", "101");
        glyph('N', "101", "111", "111", "111", "101");
        glyph('O', "010", "101", "101", "101", "010");
        glyph('P', "110", "101", "110", "100", "100");
        glyph('Q', "010", "101", "101", "011", "001");
        glyph('R', "110", "101", "110", "101", "101");
        glyph('S', "011", "100", "010", "001", "110");
        glyph('T', "111", "010", "010", "010", "010");
        glyph('U', "101", "101", "101", "101", "111");
        glyph('V', "101", "101", "101", "101", "010");
        glyph('W', "101", "101", "111", "111", "101");
        glyph('X', "101", "101", "010", "101", "101");
        glyph('Y', "101", "101", "010", "010", "010");
        glyph('Z', "111", "001", "010", "100", "111");
        glyph('0', "111", "101", "101", "101", "111");
        glyph('1', "010", "110", "010", "010", "111");
        glyph('2', "110", "001", "010", "100", "111");
        glyph('3', "110", "001", "010", "001", "110");
        glyph('4', "101", "101", "111", "001", "001");
        glyph('5', "111", "100", "110", "001", "110");
        glyph('6', "011", "100", "110", "101", "010");
        glyph('7', "111", "001", "010", "010", "010");
        glyph('8', "010", "101", "010", "101", "010");
        glyph('9', "010", "101", "011", "001", "110");
        glyph(' ', "000", "000", "000", "000", "000");
        glyph('!', "010", "010", "010", "000", "010");
        glyph('"', "101", "101", "000", "000", "000");
        glyph('#', "101", "111", "101", "111", "101");
        glyph('$', "011", "110", "011", "110", "110");
        glyph('%', "101", "001", "010", "100", "101");
        glyph('&', "010", "101", "010", "101", "011");
        glyph('\'', "010", "010", "000", "000", "000");
        glyph('(', "001", "010", "010", "010", "001");
        glyph(')', "100", "010", "010", "010", "100");
        glyph('*', "000", "101", "010", "101", "000");
        glyph('+', "000", "010", "111", "010", "000");
        glyph(',', "000", "000", "000", "010", "100");
        glyph('-', "000", "000", "111", "000", "000");
        glyph('.', "000", "000", "000", "000", "010");
        glyph('/', "001", "001", "010", "100", "100");
        glyph(':', "000", "010", "000", "010", "000");
        glyph(';', "000", "010", "000", "010", "100");
        glyph('<', "001", "010", "100", "010", "001");
        glyph('=', "000", "111", "000", "111", "000");
        glyph('>', "100", "010", "001", "010", "100");
        glyph('?', "110", "001", "010", "000", "010");
        glyph('@', "010", "101", "111", "100", "011");
        glyph('[', "011", "010", "010", "010", "011");
        glyph('\\', "100", "100", "010", "001", "001");
        glyph(']', "110", "010", "010", "010", "110");
        glyph('^', "010", "101", "000", "000", "000");
        glyph('_', "000", "000", "000", "000", "111");
        glyph('`', "100", "010", "000", "000", "000");
        glyph('{', "001", "010", "110", "010", "001");
        glyph('|', "010", "010", "010", "010", "010");
        glyph('}', "100", "010", "011", "010", "100");
        glyph('~', "000", "010", "101", "000", "000");
    }

    private CompactTextRaster() {}

    private static void glyph(char ch, String... rows) { GLYPHS.put(ch, rows); }

    static String[] rowsFor(int codePoint) {
        char normalized = codePoint >= 0 && codePoint < 128
                ? Character.toUpperCase((char) codePoint) : '?';
        String[] rows = GLYPHS.get(normalized);
        return rows == null ? GLYPHS.get('?') : rows;
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
}
