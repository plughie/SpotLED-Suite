package com.spotled.app;

/** Bounds and EXIF coordinate rules shared by Android image import and its tests. */
final class ImageImportPolicy {
    static final long MAX_ENCODED_BYTES = 50L * 1024 * 1024;
    static final long MAX_DECODED_PIXELS = 4_000_000L;
    static final int MAX_VERTICAL_SCROLL_HEIGHT = 4096;

    private ImageImportPolicy() {}

    static int sampleSize(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Image dimensions are invalid or too large.");
        int sample = 1;
        while (ceilDiv(width, sample) * (long) ceilDiv(height, sample) > MAX_DECODED_PIXELS) {
            if (sample > (1 << 29)) throw new IllegalArgumentException("Image dimensions are too large.");
            sample <<= 1;
        }
        return sample;
    }

    static int[] orientedSize(int width, int height, int exifOrientation) {
        return exifOrientation >= 5 && exifOrientation <= 8
                ? new int[] {height, width} : new int[] {width, height};
    }

    /** Maps an input pixel coordinate to its EXIF-oriented output coordinate. */
    static int[] orientPixel(int x, int y, int width, int height, int orientation) {
        switch (orientation) {
            case 2: return new int[] {width - 1 - x, y};
            case 3: return new int[] {width - 1 - x, height - 1 - y};
            case 4: return new int[] {x, height - 1 - y};
            case 5: return new int[] {y, x};
            case 6: return new int[] {height - 1 - y, x};
            case 7: return new int[] {height - 1 - y, width - 1 - x};
            case 8: return new int[] {y, width - 1 - x};
            default: return new int[] {x, y};
        }
    }

    static int[] gifSampleIndices(int frameCount, int limit) {
        if (frameCount < 1 || limit < 1) throw new IllegalArgumentException("Frame counts must be positive.");
        int count = Math.min(frameCount, limit);
        int[] indices = new int[count];
        if (count == 1) { indices[0] = 0; return indices; }
        for (int i = 0; i < count; i++)
            indices[i] = (int) Math.round(i * (frameCount - 1.0) / (count - 1));
        return indices;
    }

    static int gifIntervalMs(long totalDurationMs, int sentFrameCount) {
        if (sentFrameCount < 1) throw new IllegalArgumentException("At least one frame must be sent.");
        long interval = Math.max(0, totalDurationMs) / sentFrameCount;
        return (int) Math.max(250, Math.min(1000, interval));
    }

    private static int ceilDiv(int n, int d) { return n / d + (n % d == 0 ? 0 : 1); }
}
