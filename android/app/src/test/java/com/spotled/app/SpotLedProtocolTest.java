package com.spotled.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpotLedProtocolTest {
    @Test public void matchesSpotLedNamePrefixAndRejectsUnrelatedDevices() {
        assertTrue(SpotLedBleClient.matchesSpotLedName("SpotLED_AB12"));
        assertTrue(SpotLedBleClient.matchesSpotLedName("SpotLED_OriginalContributor"));
        assertFalse(SpotLedBleClient.matchesSpotLedName("AnotherLED_451D"));
        assertFalse(SpotLedBleClient.matchesSpotLedName(null));
    }

    @Test public void connectionRetryIsBoundedToOneRetry() {
        assertTrue(SpotLedBleClient.shouldRetryConnection(1));
        assertFalse(SpotLedBleClient.shouldRetryConnection(2));
    }

    @Test public void textPaletteHasSolidColorsAndRainbowGradient() {
        assertEquals(0xffffffff, TextPalette.colorFor(0, 0, 3));
        assertEquals(0xffff0000, TextPalette.colorFor(1, 1, 3));
        assertEquals(0xff00ff00, TextPalette.colorFor(4, 1, 3));
        assertEquals(0xffff0000, TextPalette.colorFor(8, 0, 4));
        assertTrue(TextPalette.colorFor(8, 0, 4) != TextPalette.colorFor(8, 1, 4));
        assertTrue(TextPalette.colorFor(8, 1, 4) != TextPalette.colorFor(8, 2, 4));
    }

    @Test public void compactTextBMatchesPythonGlyphAndUsesOnePixelAdvanceGap() {
        assertArrayEquals(new String[] {"110", "101", "110", "101", "110"},
                CompactTextRaster.rowsFor('B'));
        RgbTransforms.Frame upper = CompactTextRaster.render("B", 0);
        RgbTransforms.Frame lower = CompactTextRaster.render("b", 0);
        assertEquals(3, upper.width);
        assertEquals(5, upper.height);
        assertEquals(10, countLitPixels(upper.rgb));
        assertArrayEquals(upper.rgb, lower.rgb);
        assertEquals(23, CompactTextRaster.render("HYBRID", 0).width);
    }

    @Test public void compactTextUnsupportedCharactersUseQuestionMarkGlyph() {
        assertArrayEquals(CompactTextRaster.rowsFor('?'), CompactTextRaster.rowsFor('é'));
    }

    @Test public void matchesPythonRgbFrameAnimationPacketPrefix() {
        byte[] pixels = new byte[] {(byte) 255, 0, 0, 0, (byte) 255, 0};
        byte[] packet = SpotLedProtocol.buildRgbAnimation(pixels, 2, 1, 200);
        assertEquals(60, packet.length);
        assertEquals(0x8004, u16(packet, 4));
        assertEquals(1, u32(packet, 6));
        assertEquals(9, u32(packet, 15));
        assertEquals(11, u16(packet, 19));
        assertEquals(1, u16(packet, 21));
        assertEquals(0x60, u16(packet, 28));
        assertEquals(2, u16(packet, 30));
        assertEquals(1, u16(packet, 32));
        assertEquals(0x18, packet[34] & 0xff);
        assertEquals(SpotLedProtocol.checksum(packet, 14), packet[14] & 0xff);
        assertEquals(checksumRange(packet, 15, 8), packet[23] & 0xff);
        assertEquals(checksumRange(packet, 24, 17), packet[41] & 0xff);
    }

    @Test public void encodesTwoRgbFramesWithSharedSafeInterval() {
        byte[] first = new byte[2 * 1 * 3];
        byte[] second = new byte[] {1, 2, 3, 4, 5, 6};
        byte[] packet = SpotLedProtocol.buildRgbAnimation(
                new byte[][] {first, second}, 2, 1, 500);
        assertEquals(2, u16(packet, 21));
        assertEquals(0x60, u16(packet, 28));
        assertEquals(0x60, u16(packet, 46));
        assertEquals(500, u16(packet, 67));
        assertEquals(0x010203040506L, rgbAsLong(packet, 53));
        assertEquals(78, packet.length);
    }

    @Test public void twoFullScreenFramesFitKnownDeviceReceiveCapacity() {
        byte[][] frames = new byte[][] {
                new byte[64 * 16 * 3], new byte[64 * 16 * 3]
        };
        byte[] packet = SpotLedProtocol.buildRgbAnimation(frames, 64, 16, 500);
        assertTrue(packet.length <= 7280);
        assertEquals(2, u16(packet, 21));
    }

    @Test public void startAndEndPacketsUseSameHeaderExceptCommandId() {
        byte[] start = SpotLedProtocol.buildStart(0x1234, 0x8004, 0x01020304);
        byte[] end = SpotLedProtocol.buildEnd(0x1234, 0x8004, 0x01020304);
        assertEquals(1, start[1] & 0xff);
        assertEquals(3, end[1] & 0xff);
        assertEquals(0x1234, u16(start, 2));
        assertEquals(0x8004, u16(end, 4));
        assertEquals(0x01020304, u32(end, 6));
    }

    @Test public void rejectsFrameLengthMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> SpotLedProtocol.buildRgbAnimation(new byte[3], 2, 1, 500));
    }

    @Test public void encodesKnownTextEffectInSpecialEffectsObject() {
        byte[] frame = new byte[6];
        byte[] scroll = SpotLedProtocol.buildRgbAnimation(frame, 2, 1, 500, 6);
        byte[] laser = SpotLedProtocol.buildRgbAnimation(frame, 2, 1, 500, 7);
        assertEquals(8, u16(scroll, 56));
        assertEquals(6, scroll[58] & 0xff);
        assertEquals(7, laser[58] & 0xff);
        assertEquals(0, SpotLedProtocol.buildRgbAnimation(frame, 2, 1, 500)[58] & 0xff);
    }

    @Test public void rejectsUnverifiedEffectlessEncoding() {
        assertThrows(IllegalArgumentException.class,
                () -> SpotLedProtocol.buildRgbAnimation(new byte[6], 2, 1, 500, -1));
    }

    @Test public void rejectsUnknownEffectId() {
        assertThrows(IllegalArgumentException.class,
                () -> SpotLedProtocol.buildRgbAnimation(new byte[6], 2, 1, 500, 8));
    }

    @Test public void clockwiseRotationMatchesPythonCornerOrder() {
        byte[] rgb = labeledPixels(2, 3, 1, 2, 3, 4, 5, 6);
        RgbTransforms.Frame rotated = RgbTransforms.rotate(rgb, 2, 3, 90);
        assertEquals(3, rotated.width);
        assertEquals(2, rotated.height);
        assertPixelLabels(rotated.rgb, 5, 3, 1, 6, 4, 2);
    }

    @Test public void containMayLeaveBlackPaddingWhereasStretchFillsPanel() {
        byte[] source = solidPixels(16, 64, 230, 20, 10);
        RgbTransforms.Frame contained = RgbTransforms.fit(source, 16, 64, 64, 16, "contain");
        RgbTransforms.Frame stretched = RgbTransforms.fit(source, 16, 64, 64, 16, "stretch");
        assertEquals(64 * 16 * 3, contained.rgb.length);
        assertEquals(64 * 16 * 3, stretched.rgb.length);
        assertTrue(countBlackPixels(contained.rgb) > 0);
        assertEquals(0, countBlackPixels(stretched.rgb));
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int u32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static long rgbAsLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 6; i++) value = (value << 8) | (bytes[offset + i] & 0xffL);
        return value;
    }

    private static int checksumRange(byte[] bytes, int offset, int length) {
        byte[] range = new byte[length];
        System.arraycopy(bytes, offset, range, 0, length);
        return SpotLedProtocol.checksum(range, range.length);
    }

    private static byte[] labeledPixels(int width, int height, int... labels) {
        byte[] pixels = new byte[width * height * 3];
        for (int i = 0; i < labels.length; i++) {
            pixels[i * 3] = (byte) labels[i];
            pixels[i * 3 + 1] = 0;
            pixels[i * 3 + 2] = 0;
        }
        return pixels;
    }

    private static void assertPixelLabels(byte[] pixels, int... labels) {
        for (int i = 0; i < labels.length; i++) assertEquals(labels[i], pixels[i * 3] & 0xff);
    }

    private static byte[] solidPixels(int width, int height, int red, int green, int blue) {
        byte[] pixels = new byte[width * height * 3];
        for (int i = 0; i < width * height; i++) {
            pixels[i * 3] = (byte) red;
            pixels[i * 3 + 1] = (byte) green;
            pixels[i * 3 + 2] = (byte) blue;
        }
        return pixels;
    }

    private static int countBlackPixels(byte[] pixels) {
        int count = 0;
        for (int i = 0; i < pixels.length; i += 3)
            if (pixels[i] == 0 && pixels[i + 1] == 0 && pixels[i + 2] == 0) count++;
        return count;
    }

    private static int countLitPixels(byte[] pixels) {
        int count = 0;
        for (int i = 0; i < pixels.length; i += 3)
            if (pixels[i] != 0 || pixels[i + 1] != 0 || pixels[i + 2] != 0) count++;
        return count;
    }
}
