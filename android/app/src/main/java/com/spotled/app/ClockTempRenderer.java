package com.spotled.app;

import java.time.ZonedDateTime;
import java.util.Locale;

/** Raster ClockTemp002-style status frame for the 64×16 SpotLED panel. */
final class ClockTempRenderer {
    static final int WIDTH = 64;
    static final int HEIGHT = 16;

    private ClockTempRenderer() { }

    static byte[] composeWeatherIcons(byte[] frame, byte[] moonIcon, byte[] weatherIcon) {
        int iconBytes = 8 * 8 * 3;
        if (frame == null || frame.length != WIDTH * HEIGHT * 3
                || moonIcon == null || moonIcon.length != iconBytes
                || weatherIcon == null || weatherIcon.length != iconBytes)
            throw new IllegalArgumentException("ClockTemp frame and 8x8 icon dimensions do not match");
        byte[] result = frame.clone();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int iconOffset = (y * 8 + x) * 3;
                int moonOffset = (y * WIDTH + WIDTH - 8 + x) * 3;
                int weatherOffset = ((y + 8) * WIDTH + WIDTH - 8 + x) * 3;
                System.arraycopy(moonIcon, iconOffset, result, moonOffset, 3);
                System.arraycopy(weatherIcon, iconOffset, result, weatherOffset, 3);
            }
        }
        return result;
    }

    static byte[] render(ZonedDateTime now, Float indoor, MqttPayloads.Weather weather,
                         Float speedMbps, boolean celsius) {
        byte[] rgb = new byte[WIDTH * HEIGHT * 3];
        drawText(rgb, String.format(Locale.ROOT, "%02d/%02d", now.getMonthValue(), now.getDayOfMonth()),
                0, 0, 0xffffffff);
        drawText(rgb, String.format(Locale.ROOT, "%d:%02d", now.getHour(), now.getMinute()),
                0, 9, 0xffffffff);
        drawText(rgb, indoor == null ? "--" : temperature(indoor, celsius), 22, 9,
                indoor == null ? 0xffffffff : 0xff00ff00);
        if (weather == null) {
            drawText(rgb, "--", 38, 9, 0xffffffff);
        } else {
            float outdoor = celsius ? (weather.fahrenheit - 32f) / 1.8f : weather.fahrenheit;
            drawText(rgb, temperature(outdoor, celsius), 38, 9, 0xffffffff);
            drawCondition(rgb, weather.condition);
        }
        if (speedMbps != null) drawSpeedBar(rgb, speedMbps);
        return rgb;
    }

    private static String temperature(float value, boolean celsius) {
        int rounded = (int) (value >= 0 ? value + 0.5f : value - 0.5f);
        return rounded + (celsius ? "C" : "F");
    }

    private static void drawText(byte[] rgb, String text, int left, int top, int argb) {
        if (text == null) return;
        for (int index = 0; index < text.length(); index++) {
            String[] rows = CompactTextRaster.rowsFor(text.charAt(index));
            int xStart = left + index * 4;
            for (int y = 0; y < rows.length; y++) {
                for (int x = 0; x < 3; x++) {
                    if (rows[y].charAt(x) == '1') pixel(rgb, xStart + x, top + y, argb);
                }
            }
        }
    }

    private static void drawCondition(byte[] rgb, String condition) {
        int left = 59, top = 0;
        if ("sunny".equals(condition) || "clear-night".equals(condition)) {
            int[][] rays = {{2,2},{2,0},{2,4},{0,2},{4,2},{1,1},{3,1},{1,3},{3,3}};
            for (int[] point : rays) pixel(rgb, left + point[0], top + point[1], 0xffffff00);
            return;
        }
        if ("lightning".equals(condition) || "lightning-rainy".equals(condition)) {
            for (int x = 0; x < 4; x++) {
                pixel(rgb, left + x, top + 1, 0xffaaaaaa);
                pixel(rgb, left + x, top + 2, 0xffaaaaaa);
            }
            pixel(rgb, left + 2, top + 3, 0xffffff00);
            pixel(rgb, left + 1, top + 4, 0xffffff00);
            pixel(rgb, left + 3, top + 3, 0xffffff00);
            return;
        }
        for (int x = 0; x < 5; x++) {
            pixel(rgb, left + x, top + 2, 0xff66ccff);
            pixel(rgb, left + x, top + 3, 0xff66ccff);
        }
        pixel(rgb, left + 1, top + 1, 0xff66ccff);
        pixel(rgb, left + 2, top, 0xff66ccff);
        pixel(rgb, left + 3, top + 1, 0xff66ccff);
        if ("rainy".equals(condition) || "pouring".equals(condition)) {
            pixel(rgb, left + 1, top + 4, 0xff3399ff);
            pixel(rgb, left + 3, top + 4, 0xff3399ff);
        } else if ("snowy".equals(condition) || "snowy-rainy".equals(condition)
                || "hail".equals(condition)) {
            pixel(rgb, left + 1, top + 4, 0xffffffff);
            pixel(rgb, left + 3, top + 4, 0xffffffff);
        }
    }

    private static void drawSpeedBar(byte[] rgb, float speed) {
        int speedWidth = WIDTH - 8;
        int end = Math.min(speedWidth - 1, Math.max(0,
                Math.round(Math.max(0, speed) / 1000f * (speedWidth - 1))));
        for (int x = 0; x <= end; x++) {
            float percent = x * 100f / (speedWidth - 1);
            int red = 255, green;
            if (percent <= 33.333f) green = Math.round(percent * 128f / 33.333f);
            else if (percent <= 66.666f) green = Math.round(128f + (percent - 33.333f) * 127f / 33.333f);
            else { red = Math.round(255f - (percent - 66.666f) * 255f / 33.334f); green = 255; }
            pixel(rgb, x, HEIGHT - 1, 0xff000000 | (red << 16) | (green << 8));
        }
    }

    private static void pixel(byte[] rgb, int x, int y, int argb) {
        if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) return;
        int offset = (y * WIDTH + x) * 3;
        rgb[offset] = (byte) (argb >> 16);
        rgb[offset + 1] = (byte) (argb >> 8);
        rgb[offset + 2] = (byte) argb;
    }
}
