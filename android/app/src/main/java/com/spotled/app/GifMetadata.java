package com.spotled.app;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Small streaming GIF block reader for original frame count and source duration. */
final class GifMetadata {
    final int width, height, frameCount;
    final long durationMs;

    private GifMetadata(int width, int height, int frameCount, long durationMs) {
        this.width = width; this.height = height; this.frameCount = frameCount; this.durationMs = durationMs;
    }

    static GifMetadata read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            byte[] header = new byte[6]; in.readFully(header);
            if (!((header[0] == 'G' && header[1] == 'I' && header[2] == 'F')
                    && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a'))
                return null;
            int width = readU16LE(in), height = readU16LE(in);
            int packed = in.readUnsignedByte();
            in.skipBytes(2); // background color index, pixel aspect ratio
            if ((packed & 0x80) != 0) skipFully(in, 3 * (1 << ((packed & 7) + 1)));
            int frames = 0, pendingDelayCs = 10;
            long durationMs = 0;
            while (true) {
                int marker = in.readUnsignedByte();
                if (marker == 0x3b) break;
                if (marker == 0x21) {
                    int label = in.readUnsignedByte();
                    if (label == 0xf9) {
                        int blockSize = in.readUnsignedByte();
                        byte[] control = new byte[blockSize]; in.readFully(control);
                        if (blockSize >= 3) {
                            int delay = (control[1] & 0xff) | ((control[2] & 0xff) << 8);
                            pendingDelayCs = delay == 0 ? 10 : delay;
                        }
                        in.readUnsignedByte(); // zero-size terminator
                    } else {
                        skipSubBlocks(in);
                    }
                } else if (marker == 0x2c) {
                    skipFully(in, 8); // image left/top/width/height
                    int imagePacked = in.readUnsignedByte();
                    if ((imagePacked & 0x80) != 0) skipFully(in, 3 * (1 << ((imagePacked & 7) + 1)));
                    in.readUnsignedByte(); // LZW minimum code size
                    skipSubBlocks(in);
                    frames++;
                    durationMs += Math.max(20, pendingDelayCs * 10L);
                    pendingDelayCs = 10;
                } else {
                    throw new IOException("Invalid GIF block marker.");
                }
            }
            if (frames < 1 || width < 1 || height < 1) throw new IOException("GIF has no valid image frames.");
            return new GifMetadata(width, height, frames, durationMs);
        } catch (EOFException exception) {
            throw new IOException("Truncated GIF data.", exception);
        }
    }

    private static int readU16LE(DataInputStream in) throws IOException {
        return in.readUnsignedByte() | in.readUnsignedByte() << 8;
    }

    private static void skipSubBlocks(DataInputStream in) throws IOException {
        while (true) {
            int length = in.readUnsignedByte();
            if (length == 0) return;
            skipFully(in, length);
        }
    }

    private static void skipFully(DataInputStream in, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            int skipped = in.skipBytes(remaining);
            if (skipped <= 0) { in.readByte(); skipped = 1; }
            remaining -= skipped;
        }
    }
}
