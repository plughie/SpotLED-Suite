package com.spotled.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;

import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds a calm two-frame 8x8 weather animation with a date-correct moon above it. */
final class WeatherIconAnimation {
    private static final int ICON_LEFT = 56;
    private static final int ICON_SIZE = 8;
    private static final int FRAME_SAMPLE_MS = 50;

    private WeatherIconAnimation() { }

    static int moonIconId(ZonedDateTime now) {
        int month = now.getMonthValue();
        int a = (14 - month) / 12;
        int year = now.getYear() + 4800 - a;
        int adjustedMonth = month + 12 * a - 3;
        double phase = (now.getDayOfMonth() + (153 * adjustedMonth + 2) / 5
                + 365 * year + year / 4 - year / 100 + year / 400 - 2483595)
                / 29.53058867;
        phase -= Math.floor(phase);
        if (phase < 0.03 || phase >= 0.97) return 2318;
        if (phase < 0.22) return 2321;
        if (phase < 0.28) return 2320;
        if (phase < 0.48) return 2319;
        if (phase < 0.52) return 2314;
        if (phase < 0.72) return 2315;
        if (phase < 0.78) return 2316;
        return 2317;
    }

    static String weatherAssetName(String condition) {
        if (condition == null) return "unknown.gif";
        String name = condition.trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case "sunny": case "clear-night": case "partlycloudy": case "cloudy":
            case "fog": case "hail": case "lightning": case "lightning-rainy":
            case "pouring": case "rainy": case "snowy": case "snowy-rainy":
            case "windy": case "windy-variant": case "exceptional":
                return name + ".gif";
            default: return "unknown.gif";
        }
    }

    static byte[][] createFrames(Context context, ZonedDateTime now, String condition,
                                 byte[] staticFrame) {
        Movie weatherMovie = loadMovie(context, "weather-icons/" + weatherAssetName(condition));
        Movie moonMovie = loadMovie(context, "weather-icons/moon_" + moonIconId(now) + ".gif");
        if (weatherMovie == null || moonMovie == null) return new byte[][] {staticFrame};

        byte[] moon = iconAt(moonMovie, 0, 0);
        int duration = Math.max(1, weatherMovie.duration());
        int step = Math.max(FRAME_SAMPLE_MS, duration / 32);
        List<byte[]> samples = new ArrayList<>();
        for (int time = 0; time < duration; time += step)
            samples.add(iconAt(weatherMovie, time, ICON_SIZE));
        if (samples.isEmpty()) samples.add(iconAt(weatherMovie, 0, ICON_SIZE));

        int first = 0, best = 0, bestDifference = 0;
        for (int index = 1; index < samples.size(); index++) {
            int difference = difference(samples.get(first), samples.get(index));
            if (difference > bestDifference) {
                best = index;
                bestDifference = difference;
            }
        }
        byte[] firstFrame = ClockTempRenderer.composeWeatherIcons(
                staticFrame, moon, samples.get(first));
        if (best == first) return new byte[][] {firstFrame};
        byte[] distinctFrame = ClockTempRenderer.composeWeatherIcons(
                staticFrame, moon, samples.get(best));
        return new byte[][] {firstFrame, distinctFrame};
    }

    private static Movie loadMovie(Context context, String assetName) {
        try (InputStream input = context.getAssets().open(assetName)) {
            return Movie.decodeStream(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] iconAt(Movie movie, int timeMs, int top) {
        Bitmap bitmap = Bitmap.createBitmap(64, 16, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        movie.setTime(timeMs);
        movie.draw(canvas, 0, 0);
        int[] argb = new int[64 * 16];
        bitmap.getPixels(argb, 0, 64, 0, 0, 64, 16);
        bitmap.recycle();

        byte[] rgb = new byte[ICON_SIZE * ICON_SIZE * 3];
        for (int y = 0; y < ICON_SIZE; y++) {
            for (int x = 0; x < ICON_SIZE; x++) {
                int color = argb[(top + y) * 64 + ICON_LEFT + x];
                int target = (y * ICON_SIZE + x) * 3;
                rgb[target] = (byte) Color.red(color);
                rgb[target + 1] = (byte) Color.green(color);
                rgb[target + 2] = (byte) Color.blue(color);
            }
        }
        return rgb;
    }

    private static int difference(byte[] first, byte[] second) {
        int score = 0;
        for (int index = 0; index < first.length; index++)
            score += Math.abs((first[index] & 0xff) - (second[index] & 0xff));
        return score;
    }
}
