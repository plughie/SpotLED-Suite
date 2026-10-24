package com.spotled.app;

/** Tested row-major RGB rotation and fit operations for the 64×16 display. */
final class RgbTransforms {
    static final class Frame {
        final int width;
        final int height;
        final byte[] rgb;

        Frame(int width, int height, byte[] rgb) {
            this.width = width;
            this.height = height;
            this.rgb = rgb;
        }
    }

    private RgbTransforms() {}

    static Frame rotate(byte[] rgb, int width, int height, int degrees) {
        requireFrame(rgb, width, height);
        int rotation = ((degrees % 360) + 360) % 360;
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)
            throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270 degrees");
        int outWidth = rotation == 90 || rotation == 270 ? height : width;
        int outHeight = rotation == 90 || rotation == 270 ? width : height;
        byte[] output = new byte[rgb.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int outX, outY;
                if (rotation == 90) { outX = height - 1 - y; outY = x; }
                else if (rotation == 180) { outX = width - 1 - x; outY = height - 1 - y; }
                else if (rotation == 270) { outX = y; outY = width - 1 - x; }
                else { outX = x; outY = y; }
                int from = (y * width + x) * 3;
                int to = (outY * outWidth + outX) * 3;
                System.arraycopy(rgb, from, output, to, 3);
            }
        }
        return new Frame(outWidth, outHeight, output);
    }

    static Frame fit(byte[] rgb, int width, int height, int targetWidth,
                     int targetHeight, String mode) {
        return fit(rgb, width, height, targetWidth, targetHeight, mode, 1.0, 0.0, 0.0);
    }

    static Frame fit(byte[] rgb, int width, int height, int targetWidth,
                     int targetHeight, String mode, double zoom,
                     double panX, double panY) {
        requireFrame(rgb, width, height);
        if (targetWidth <= 0 || targetHeight <= 0)
            throw new IllegalArgumentException("Target dimensions must be positive");
        if (!mode.equals("contain") && !mode.equals("cover") && !mode.equals("stretch"))
            throw new IllegalArgumentException("Fit must be contain, cover, or stretch");
        if (zoom < 1 || zoom > 4 || panX < -1 || panX > 1 || panY < -1 || panY > 1)
            throw new IllegalArgumentException("Zoom must be 1×–4× and pan must be -1–1");

        double scale = mode.equals("stretch") ? 0 :
                (mode.equals("contain")
                        ? Math.min(targetWidth / (double) width, targetHeight / (double) height)
                        : Math.max(targetWidth / (double) width, targetHeight / (double) height));
        int scaledWidth = mode.equals("stretch") ? targetWidth : Math.max(1, (int) Math.round(width * scale));
        int scaledHeight = mode.equals("stretch") ? targetHeight : Math.max(1, (int) Math.round(height * scale));
        if (mode.equals("contain")) {
            scaledWidth = Math.min(targetWidth, scaledWidth);
            scaledHeight = Math.min(targetHeight, scaledHeight);
        } else if (mode.equals("cover")) {
            scaledWidth = Math.max(targetWidth, scaledWidth);
            scaledHeight = Math.max(targetHeight, scaledHeight);
        }
        scaledWidth = Math.max(1, (int) Math.floor(scaledWidth * zoom + 0.5));
        scaledHeight = Math.max(1, (int) Math.floor(scaledHeight * zoom + 0.5));

        byte[] output = new byte[targetWidth * targetHeight * 3]; // black padding
        int left = (int) Math.floor((targetWidth - scaledWidth) * (panX + 1) / 2 + 0.5);
        int top = (int) Math.floor((targetHeight - scaledHeight) * (panY + 1) / 2 + 0.5);
        for (int y = 0; y < targetHeight; y++) {
            int scaledY = y - top;
            if (scaledY < 0 || scaledY >= scaledHeight) continue;
            int sourceY = Math.min(height - 1, (int) ((scaledY + 0.5) * height / scaledHeight));
            for (int x = 0; x < targetWidth; x++) {
                int scaledX = x - left;
                if (scaledX < 0 || scaledX >= scaledWidth) continue;
                int sourceX = Math.min(width - 1, (int) ((scaledX + 0.5) * width / scaledWidth));
                int from = (sourceY * width + sourceX) * 3;
                int to = (y * targetWidth + x) * 3;
                System.arraycopy(rgb, from, output, to, 3);
            }
        }
        return new Frame(targetWidth, targetHeight, output);
    }

    static Frame[] verticalScroll(byte[] rgb, int width, int height) {
        requireFrame(rgb, width, height);
        int scaledHeight = Math.min(ImageImportPolicy.MAX_VERTICAL_SCROLL_HEIGHT, Math.max(16,
                (int) Math.floor(height * 64.0 / width + 0.5)));
        if (scaledHeight <= 16)
            return new Frame[] {fit(rgb, width, height, 64, 16, "contain")};
        Frame tall = fit(rgb, width, height, 64, scaledHeight, "stretch");
        int frameBytes = 64 * 16 * 3;
        int lastOffset = scaledHeight - 16;
        java.util.ArrayList<Frame> frames = new java.util.ArrayList<>();
        for (int y = 0; y <= lastOffset; y += 16)
            frames.add(new Frame(64, 16, java.util.Arrays.copyOfRange(
                    tall.rgb, y * 64 * 3, y * 64 * 3 + frameBytes)));
        if ((frames.size() - 1) * 16 != lastOffset)
            frames.add(new Frame(64, 16, java.util.Arrays.copyOfRange(
                    tall.rgb, lastOffset * 64 * 3, lastOffset * 64 * 3 + frameBytes)));
        return frames.toArray(new Frame[0]);
    }

    private static void requireFrame(byte[] rgb, int width, int height) {
        if (width <= 0 || height <= 0 || rgb == null || rgb.length != width * height * 3)
            throw new IllegalArgumentException("RGB bytes do not match positive frame dimensions");
    }
}
