package com.spotled.app;

import java.io.ByteArrayOutputStream;

/** Wire-format builders ported against the tested Python reference vectors. */
final class SpotLedProtocol {
    static final int SET_DATA_OBJECT = 0x8004;
    static final int SCREEN_WIDTH = 64;
    static final int SCREEN_HEIGHT = 16;

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
            frameContent.writeBytes(rgb);
            follow.writeBytes(object(0x60, frameContent.toByteArray()));
        }
        follow.writeBytes(object(7, new byte[] {0, (byte) (intervalMs >> 8), (byte) intervalMs}));
        follow.writeBytes(object(8, new byte[] {(byte) effectMode}));
        byte[] animation = objectWithFollow(11,
                new byte[] {(byte) (frames.length >> 8), (byte) frames.length}, follow.toByteArray());
        return wrapSetData(animation, 1);
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

    private static void putU16(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >> 8);
        out[offset + 1] = (byte) value;
    }

    private static void putU32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >> 24);
        out[offset + 1] = (byte) (value >> 16);
        out[offset + 2] = (byte) (value >> 8);
        out[offset + 3] = (byte) value;
    }
}
