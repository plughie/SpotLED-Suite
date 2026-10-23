package com.spotled.app;

import java.io.ByteArrayOutputStream;

/** Wire-format builders ported against the tested Python reference vectors. */
final class SpotLedProtocol {
    static final int SET_DATA_OBJECT = 0x8004;
    static final int SCREEN_WIDTH = 64;
    static final int SCREEN_HEIGHT = 16;
    static final int MAX_RECEIVE_BYTES = 7280;

    static final class Capabilities {
        final int width, height, colorType, maxFrames, receiveBytes;
        Capabilities(int width, int height, int colorType, int maxFrames, int receiveBytes) {
            this.width = width; this.height = height; this.colorType = colorType;
            this.maxFrames = maxFrames; this.receiveBytes = receiveBytes;
        }
    }

    static byte[] buildQuery(int queryId, int serial) {
        if (queryId < 0 || queryId > 255 || serial < 0 || serial > 0xffff)
            throw new IllegalArgumentException("Invalid query ID or serial");
        return new byte[] {4, (byte) queryId, (byte) (serial >> 8), (byte) serial};
    }

    static int parseScreenResponse(byte[] frame, int serial) {
        if (frame == null || frame.length < 14 || (frame[0] & 0xff) != frame.length
                || (frame[1] & 0xff) != 0x13 || u16(frame, 2) != serial)
            throw new IllegalArgumentException("Invalid screen-capability response");
        if (frame[4] != 0) throw new IllegalArgumentException("Display rejected screen-capability query");
        return (u16(frame, 5) << 16) | u16(frame, 7) << 0;
    }

    static Capabilities parseCapabilities(byte[] screen, byte[] animation, byte[] capacity,
                                          int screenSerial, int animationSerial, int capacitySerial) {
        int dimensions = parseScreenResponse(screen, screenSerial);
        if (!validSimpleResponse(animation, 0x1b, animationSerial, 9)
                || !validSimpleResponse(capacity, 0x15, capacitySerial, 9))
            throw new IllegalArgumentException("Invalid display-capability response");
        if (animation[4] != 0 || capacity[4] != 0)
            throw new IllegalArgumentException("Display rejected a capability query");
        return new Capabilities(dimensions >>> 16, dimensions & 0xffff, screen[9] & 0xff,
                u16(animation, 5), (int) u32(capacity, 5));
    }

    static void validateNativeText(Capabilities c, byte[][] payloads) {
        if (c.receiveBytes <= 0) throw new IllegalArgumentException("Display reports no receive capacity.");
        for (byte[] payload : payloads) if (payload.length > c.receiveBytes)
            throw new IllegalArgumentException("Text exceeds the display's live receive limit ("
                    + c.receiveBytes + " bytes); no content was sent.");
    }

    static void validateRgb(Capabilities c, int width, int height, int frameCount, int payloadBytes) {
        if (c.width != width || c.height != height)
            throw new IllegalArgumentException("Image is " + width + "×" + height
                    + " but this display reports " + c.width + "×" + c.height + ". No content was sent.");
        if (c.colorType != 0xff)
            throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                    "RGB images require color type 0xFF; display reports 0x%02X. No content was sent.", c.colorType));
        if (frameCount > c.maxFrames)
            throw new IllegalArgumentException("Animation exceeds the display's live frame limit ("
                    + c.maxFrames + "); no content was sent.");
        if (c.receiveBytes <= 0 || payloadBytes > c.receiveBytes)
            throw new IllegalArgumentException("Image transfer exceeds the display's live receive limit ("
                    + c.receiveBytes + " bytes); no content was sent.");
    }

    private static boolean validSimpleResponse(byte[] frame, int id, int serial, int minLength) {
        return frame != null && frame.length >= minLength && (frame[0] & 0xff) == frame.length
                && (frame[1] & 0xff) == id && u16(frame, 2) == serial;
    }

    private static int u16(byte[] b, int offset) {
        return ((b[offset] & 0xff) << 8) | (b[offset + 1] & 0xff);
    }
    private static long u32(byte[] b, int offset) {
        return ((long)(b[offset] & 0xff) << 24) | ((long)(b[offset + 1] & 0xff) << 16)
                | ((long)(b[offset + 2] & 0xff) << 8) | (b[offset + 3] & 0xffL);
    }

    private SpotLedProtocol() {}

    static int checksum(byte[] data, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) sum += data[i] & 0xff;
        return sum > 0xff ? (-sum) & 0xff : sum & 0xff;
    }

    static byte[] buildRgbAnimation(byte[] rgb, int width, int height, int intervalMs) {
        return buildRgbAnimation(rgb, width, height, intervalMs, 0);
    }

    static byte[] buildRgbAnimation(byte[] rgb, int width, int height, int intervalMs,
                                    int effectMode) {
        return buildRgbAnimation(new byte[][] {rgb}, width, height, intervalMs, effectMode);
    }

    static byte[] buildRgbAnimation(byte[][] frames, int width, int height, int intervalMs) {
        return buildRgbAnimation(frames, width, height, intervalMs, 0);
    }

    static byte[] buildRgbAnimation(byte[][] frames, int width, int height, int intervalMs,
                                    int effectMode) {
        if (frames == null || frames.length < 1 || frames.length > 0xffff)
            throw new IllegalArgumentException("Animation must contain at least one frame");
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Frame dimensions must be positive");
        for (byte[] frame : frames) {
            if (frame == null || frame.length != width * height * 3)
                throw new IllegalArgumentException("RGB bytes do not match frame dimensions");
        }
        if (intervalMs < 0 || intervalMs > 0xffff)
            throw new IllegalArgumentException("Frame interval must fit an unsigned 16-bit field");
        if (effectMode < 0 || effectMode > 7)
            throw new IllegalArgumentException("Effect must be in the range 0 through 7");

        ByteArrayOutputStream follow = new ByteArrayOutputStream();
        for (byte[] rgb : frames) {
            ByteArrayOutputStream frameContent = new ByteArrayOutputStream();
            putU16(frameContent, width);
            putU16(frameContent, height);
            frameContent.write(0x18); // 24-bit RGB
            append(frameContent, rgb);
            append(follow, object(0x60, frameContent.toByteArray()));
        }
        append(follow, object(7, new byte[] {0, (byte) (intervalMs >> 8), (byte) intervalMs}));
        append(follow, object(8, new byte[] {(byte) effectMode}));
        byte[] animation = objectWithFollow(11,
                new byte[] {(byte) (frames.length >> 8), (byte) frames.length}, follow.toByteArray());
        return wrapSetData(animation, 1);
    }

    static boolean isNativeTextEffect(int effectMode) {
        return effectMode >= 0 && effectMode <= 7;
    }

    /** Native firmware text transfers: WordStock font followed by CharacterString. */
    static byte[][] buildNativeTextTransfers(String text, int paletteIndex, int effectMode) {
        return buildNativeTextTransfers(text, paletteIndex, effectMode, 200);
    }

    static byte[][] buildNativeTextTransfers(String text, int paletteIndex, int effectMode,
                                             int timeMs) {
        return buildNativeTextTransfers(text, paletteIndex, effectMode, timeMs, null, 0);
    }

    static byte[][] buildRotatedNativeTextTransfers(String text, int paletteIndex,
                                                    int effectMode, int timeMs,
                                                    int glyphRotationDegrees) {
        if (glyphRotationDegrees != 90 && glyphRotationDegrees != 270)
            throw new IllegalArgumentException("Rotated native text must use 90 or 270 degrees");
        return buildNativeTextTransfers(text, paletteIndex, effectMode, timeMs, null,
                glyphRotationDegrees);
    }

    static byte[][] buildNativeTextTransfersSolidColor(String text, int argb,
                                                        int effectMode, int timeMs) {
        return buildNativeTextTransfers(text, 0, effectMode, timeMs, argb, 0);
    }

    private static byte[][] buildNativeTextTransfers(String text, int paletteIndex,
                                                     int effectMode, int timeMs,
                                                     Integer solidColor, int glyphRotationDegrees) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("Text must not be empty");
        if (text.length() > 160) throw new IllegalArgumentException("Text exceeds the 160-character device limit");
        if (!isNativeTextEffect(effectMode)) throw new IllegalArgumentException("Unknown native text effect");
        if (timeMs < 1 || timeMs > 0xffff)
            throw new IllegalArgumentException("Native text timing must be from 1 to 65,535 ms");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isSurrogate(ch))
                throw new IllegalArgumentException("Native marquee text supports BMP characters only");
        }

        ByteArrayOutputStream fonts = new ByteArrayOutputStream();
        // Match the Python sender's WordStock semantics: one font entry per character,
        // in the same order as CharacterString, including repeated characters.
        for (int i = 0; i < text.length(); i++)
            append(fonts, rotatedFontObject(text.charAt(i), glyphRotationDegrees));
        byte[] wordStock = objectWithFollow(5,
                new byte[] {(byte) (text.length() >> 8), (byte) text.length()}, fonts.toByteArray());

        ByteArrayOutputStream content = new ByteArrayOutputStream();
        putU16(content, text.length());
        content.write(1);
        ByteArrayOutputStream follow = new ByteArrayOutputStream();
        int[] codePoints = text.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            int color = solidColor == null
                    ? TextPalette.colorFor(paletteIndex, index, codePoints.length) : solidColor;
            append(follow, object(2, new byte[] {(byte) (color >> 16), (byte) (color >> 8), (byte) color}));
            int cp = codePoints[index];
            append(follow, object(3, new byte[] {(byte) (cp >> 8), (byte) cp}));
        }
        append(follow, object(7, new byte[] {0, (byte) (timeMs >> 8), (byte) timeMs}));
        append(follow, object(8, new byte[] {(byte) effectMode}));
        byte[] characterString = objectWithFollow(4, content.toByteArray(), follow.toByteArray());

        byte[] fontPayload = wrapSetData(wordStock, 1);
        byte[] textPayload = wrapSetData(characterString, 2);
        if (fontPayload.length > MAX_RECEIVE_BYTES || textPayload.length > MAX_RECEIVE_BYTES)
            throw new IllegalArgumentException("Native text exceeds the display's 7,280-byte transfer limit");
        return new byte[][] {fontPayload, textPayload};
    }

    private static void append(ByteArrayOutputStream target, byte[] bytes) {
        target.write(bytes, 0, bytes.length);
    }

    private static byte[] fontObject(char ch) {
        byte[] captured = CapturedPanelGlyphs.fontObject(ch);
        if (captured != null) return captured;
        String[] rows = CompactTextRaster.rowsFor(ch);
        byte[] content = new byte[40];
        content[0] = 1; // flag
        content[1] = 0;
        content[2] = 16; // glyph width
        content[3] = 0;
        content[4] = 16; // glyph height
        content[5] = (byte) (ch >> 8);
        content[6] = (byte) ch;
        content[7] = 0x20; // x offset used by the Python reference
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 3; x++) {
                if (rows[y].charAt(x) != '1') continue;
                // Scale each compact pixel into a 4x2 block inside the 16x16 glyph.
                int px = 2 + x * 4;
                int py = 3 + y * 2;
                for (int yy = py; yy < py + 2; yy++) {
                    int rowOffset = 8 + yy * 2;
                    int bits = ((content[rowOffset] & 0xff) << 8) | (content[rowOffset + 1] & 0xff);
                    for (int xx = px; xx < px + 4; xx++) bits |= 0x8000 >>> xx;
                    content[rowOffset] = (byte) (bits >> 8);
                    content[rowOffset + 1] = (byte) bits;
                }
            }
        }
        return object(13, content);
    }

    static byte[] nativeFontObject(char ch) { return fontObject(ch); }

    static byte[] rotatedFontObject(char ch, int degrees) {
        byte[] glyph = fontObject(ch);
        if (degrees == 0) return glyph;
        if (degrees != 90 && degrees != 270)
            throw new IllegalArgumentException("Glyph rotation must be 0, 90, or 270 degrees");
        byte[] rotated = glyph.clone();
        for (int y = 0; y < 16; y++) {
            rotated[14 + y * 2] = 0;
            rotated[15 + y * 2] = 0;
        }
        for (int y = 0; y < 16; y++) {
            int bits = ((glyph[14 + y * 2] & 0xff) << 8) | (glyph[15 + y * 2] & 0xff);
            for (int x = 0; x < 16; x++) {
                if ((bits & (0x8000 >>> x)) == 0) continue;
                int outX = degrees == 90 ? 15 - y : y;
                int outY = degrees == 90 ? x : 15 - x;
                int offset = 14 + outY * 2;
                int row = ((rotated[offset] & 0xff) << 8) | (rotated[offset + 1] & 0xff);
                row |= 0x8000 >>> outX;
                rotated[offset] = (byte) (row >> 8);
                rotated[offset + 1] = (byte) row;
            }
        }
        rotated[rotated.length - 1] = (byte) checksum(rotated, rotated.length - 1);
        return rotated;
    }

    static byte[] buildStart(int serial, int commandId, int totalLength) {
        byte[] packet = new byte[10];
        packet[0] = 10;
        packet[1] = 1;
        putU16(packet, 2, serial);
        putU16(packet, 4, commandId);
        putU32(packet, 6, totalLength);
        return packet;
    }

    static byte[] buildEnd(int serial, int commandId, int totalLength) {
        byte[] packet = buildStart(serial, commandId, totalLength);
        packet[1] = 3;
        return packet;
    }

    static byte[] buildNativeTextSetupPlaylist() {
        return buildCommandData(0x800c, new byte[9], 1);
    }

    static byte[] buildNativeTextSetupPixelMap() {
        return buildCommandData(0x8000, new byte[] {0, 0x10}, 3);
    }

    private static byte[] buildCommandData(int commandId, byte[] content, int serial) {
        byte[] packet = new byte[content.length + 11];
        putU32(packet, 0, packet.length);
        putU16(packet, 4, commandId);
        putU32(packet, 6, serial);
        System.arraycopy(content, 0, packet, 10, content.length);
        packet[packet.length - 1] = (byte) checksum(packet, packet.length - 1);
        return packet;
    }

    static byte[] buildTestPattern() {
        byte[] rgb = new byte[SCREEN_WIDTH * SCREEN_HEIGHT * 3];
        fillBlock(rgb, 1, 1, 3, 3, 255, 0, 0);
        fillBlock(rgb, SCREEN_WIDTH - 4, 1, 3, 3, 0, 255, 0);
        fillBlock(rgb, 1, SCREEN_HEIGHT - 4, 3, 3, 0, 0, 255);
        fillBlock(rgb, SCREEN_WIDTH - 4, SCREEN_HEIGHT - 4, 3, 3, 255, 255, 255);
        fillBlock(rgb, SCREEN_WIDTH / 2 - 2, SCREEN_HEIGHT / 2 - 2, 4, 4, 255, 0, 255);
        return rgb;
    }

    private static void fillBlock(byte[] rgb, int x, int y, int w, int h, int r, int g, int b) {
        for (int row = y; row < y + h; row++) {
            for (int col = x; col < x + w; col++) {
                int offset = (row * SCREEN_WIDTH + col) * 3;
                rgb[offset] = (byte) r;
                rgb[offset + 1] = (byte) g;
                rgb[offset + 2] = (byte) b;
            }
        }
    }

    private static byte[] wrapSetData(byte[] objectData, int serial) {
        byte[] header = new byte[15];
        putU32(header, 0, header.length);
        putU16(header, 4, SET_DATA_OBJECT);
        putU32(header, 6, serial);
        putU32(header, 10, objectData.length);
        header[14] = (byte) checksum(header, 14);
        return concat(header, objectData);
    }

    private static byte[] object(int id, byte[] content) {
        return objectWithFollow(id, content, new byte[0]);
    }

    private static byte[] objectWithFollow(int id, byte[] content, byte[] follow) {
        byte[] header = new byte[content.length + 7];
        putU32(header, 0, header.length);
        putU16(header, 4, id);
        System.arraycopy(content, 0, header, 6, content.length);
        header[header.length - 1] = (byte) checksum(header, header.length - 1);
        return concat(header, follow);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }

    private static void putU16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void putU32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >> 24);
        out[offset + 1] = (byte) (value >> 16);
        out[offset + 2] = (byte) (value >> 8);
        out[offset + 3] = (byte) value;
    }

    private static void putU16(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >> 8);
        out[offset + 1] = (byte) value;
    }

}
