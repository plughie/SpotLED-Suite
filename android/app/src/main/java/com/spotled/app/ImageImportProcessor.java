package com.spotled.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.util.concurrent.CancellationException;

/** Performs bounded image/GIF reads, orientation, fit and panel rasterization off the UI thread. */
final class ImageImportProcessor {
    static final class Result {
        final byte[][] frames;
        final int intervalMs;
        final boolean animated;
        final int originalFrameCount;

        Result(byte[][] frames, int intervalMs, boolean animated, int originalFrameCount) {
            this.frames = frames; this.intervalMs = intervalMs; this.animated = animated;
            this.originalFrameCount = originalFrameCount;
        }
    }

    private ImageImportProcessor() {}

    static Result process(Context context, Uri uri, int rotationDegrees, String fitMode) throws Exception {
        checkCancelled();
        File temp = File.createTempFile("spotled-image-", ".bin", context.getCacheDir());
        try {
            copyBounded(context.getContentResolver(), uri, temp);
            checkCancelled();
            GifMetadata gifMetadata = GifMetadata.read(temp);
            Movie movie;
            try (InputStream stream = new FileInputStream(temp)) { movie = Movie.decodeStream(stream); }
            if (movie != null && movie.duration() > 0)
                return processGif(movie, gifMetadata, rotationDegrees, fitMode);
            return processStill(temp, rotationDegrees, fitMode);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void copyBounded(ContentResolver resolver, Uri uri, File target) throws Exception {
        long advertisedSize = advertisedSize(resolver, uri);
        if (advertisedSize > ImageImportPolicy.MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Image file exceeds the 50 MB import limit.");
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IllegalArgumentException("Could not open selected image.");
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled();
                total += count;
                if (total > ImageImportPolicy.MAX_ENCODED_BYTES)
                    throw new IllegalArgumentException("Image file exceeds the 50 MB import limit.");
                output.write(buffer, 0, count);
            }
        }
    }

    private static long advertisedSize(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : -1;
        } catch (Exception ignored) { return -1; }
    }

    private static Result processStill(File file, int rotationDegrees, String fitMode) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = ImageImportPolicy.sampleSize(bounds.outWidth, bounds.outHeight);
        int orientation;
        try (InputStream stream = new FileInputStream(file)) {
            orientation = new ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        }
        checkCancelled();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (decoded == null) throw new IllegalArgumentException("Unsupported or unreadable image.");
        if ((long) decoded.getWidth() * decoded.getHeight() > ImageImportPolicy.MAX_DECODED_PIXELS) {
            decoded.recycle();
            throw new IllegalArgumentException("Decoded image exceeds the 4 MP processing limit.");
        }
        Bitmap oriented = applyExif(decoded, orientation);
        if (oriented != decoded) decoded.recycle();
        try {
            return new Result(new byte[][] {rasterize(oriented, rotationDegrees, fitMode)}, 500, false, 1);
        } finally { oriented.recycle(); }
    }

    private static Bitmap applyExif(Bitmap source, int orientation) {
        if (orientation < ExifInterface.ORIENTATION_FLIP_HORIZONTAL
                || orientation > ExifInterface.ORIENTATION_ROTATE_270) return source;
        int w = source.getWidth(), h = source.getHeight();
        float[] values;
        switch (orientation) {
            case 2: values = new float[] {-1, 0, w, 0, 1, 0, 0, 0, 1}; break;
            case 3: values = new float[] {-1, 0, w, 0, -1, h, 0, 0, 1}; break;
            case 4: values = new float[] {1, 0, 0, 0, -1, h, 0, 0, 1}; break;
            case 5: values = new float[] {0, 1, 0, 1, 0, 0, 0, 0, 1}; break;
            case 6: values = new float[] {0, -1, h, 1, 0, 0, 0, 0, 1}; break;
            case 7: values = new float[] {0, -1, h, -1, 0, w, 0, 0, 1}; break;
            case 8: values = new float[] {0, 1, 0, -1, 0, w, 0, 0, 1}; break;
            default: return source;
        }
        Matrix matrix = new Matrix();
        matrix.setValues(values);
        return Bitmap.createBitmap(source, 0, 0, w, h, matrix, false);
    }

    private static Result processGif(Movie movie, GifMetadata metadata,
                                     int rotationDegrees, String fitMode) {
        int width = movie.width(), height = movie.height();
        if (width <= 0 || height <= 0
                || (long) width * height > ImageImportPolicy.MAX_DECODED_PIXELS)
            throw new IllegalArgumentException("GIF dimensions exceed the 4 MP processing limit.");
        if (metadata != null && ((long) metadata.width * metadata.height > ImageImportPolicy.MAX_DECODED_PIXELS))
            throw new IllegalArgumentException("GIF dimensions exceed the 4 MP processing limit.");
        int duration = Math.max(1, metadata == null ? movie.duration() : (int) Math.min(Integer.MAX_VALUE, metadata.durationMs));
        int sourceFrameCount = metadata == null ? 2 : metadata.frameCount;
        int[] sampleIndices = ImageImportPolicy.gifSampleIndices(sourceFrameCount, 2);
        byte[][] frames = new byte[sampleIndices.length][];
        for (int i = 0; i < frames.length; i++) {
            checkCancelled();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.BLACK);
                movie.setTime(sampleIndices[i] == 0 ? 0 : Math.max(0, duration - 1));
                movie.draw(canvas, 0, 0);
                frames[i] = rasterize(bitmap, rotationDegrees, fitMode);
            } finally { bitmap.recycle(); }
        }
        int interval = ImageImportPolicy.gifIntervalMs(duration, frames.length);
        return new Result(frames, interval, sourceFrameCount > 1, sourceFrameCount);
    }

    private static byte[] rasterize(Bitmap bitmap, int rotationDegrees, String fitMode) {
        checkCancelled();
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        byte[] rgb = new byte[argb.length * 3];
        for (int i = 0, offset = 0; i < argb.length; i++) {
            int color = argb[i], alpha = Color.alpha(color);
            rgb[offset++] = (byte) ((Color.red(color) * alpha + 127) / 255);
            rgb[offset++] = (byte) ((Color.green(color) * alpha + 127) / 255);
            rgb[offset++] = (byte) ((Color.blue(color) * alpha + 127) / 255);
        }
        RgbTransforms.Frame rotated = RgbTransforms.rotate(rgb, width, height, rotationDegrees);
        return RgbTransforms.fit(rotated.rgb, rotated.width, rotated.height,
                SpotLedProtocol.SCREEN_WIDTH, SpotLedProtocol.SCREEN_HEIGHT, fitMode).rgb;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Image import cancelled.");
    }
}
