package com.spotled.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Pure payload parsing for the Android-only MQTT bridge. */
final class MqttPayloads {
    static final class Weather {
        final float fahrenheit;
        final String condition;
        Weather(float fahrenheit, String condition) {
            this.fahrenheit = fahrenheit;
            this.condition = condition;
        }
    }

    static final class Alert {
        final String text;
        final int color;
        final long durationMs;
        final boolean hold;
        final boolean stack;
        Alert(String text, int color, long durationMs, boolean hold, boolean stack) {
            this.text = text;
            this.color = color;
            this.durationMs = durationMs;
            this.hold = hold;
            this.stack = stack;
        }
    }

    private MqttPayloads() { }

    static Alert parseAlert(byte[] payload) {
        try {
            JSONObject json = new JSONObject(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            Object textValue = json.opt("text");
            StringBuilder text = new StringBuilder();
            if (textValue instanceof String) text.append((String) textValue);
            else if (textValue instanceof JSONArray) {
                JSONArray items = (JSONArray) textValue;
                for (int i = 0; i < items.length(); i++) {
                    Object item = items.opt(i);
                    if (item instanceof String) text.append(item);
                    else if (item instanceof JSONObject) text.append(((JSONObject) item).optString("text", ""));
                }
            }
            String safe = sanitizeText(text.toString());
            if (safe.trim().isEmpty()) return null;
            long duration = json.optLong("durationMs", 7000);
            if (duration <= 0) duration = 7000;
            duration = Math.min(600_000, duration);
            return new Alert(safe, parseColor(json.opt("textColor")), duration,
                    json.optBoolean("hold", false), json.optBoolean("stack", true));
        } catch (JSONException | RuntimeException exception) {
            return null;
        }
    }

    static Weather parseHomeAssistantWeather(byte[] payload) {
        try {
            JSONObject json = new JSONObject(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            double fahrenheit = json.getDouble("temp");
            String condition = json.optString("cond", "unknown").trim().toLowerCase(java.util.Locale.ROOT);
            if (!Double.isFinite(fahrenheit) || fahrenheit < -150 || fahrenheit > 150) return null;
            return new Weather((float) fahrenheit, condition.isEmpty() ? "unknown" : condition);
        } catch (JSONException | RuntimeException exception) {
            return null;
        }
    }

    static Alert phoneNotification(String appName, String title, String body) {
        StringBuilder text = new StringBuilder();
        appendPart(text, appName);
        String heading = joinText(title, body);
        if (!heading.isEmpty()) {
            if (text.length() > 0) text.append(": ");
            text.append(heading);
        }
        String safe = sanitizeText(text.toString().replaceAll("\\s+", " ").trim());
        if (safe.isEmpty()) return null;
        return new Alert(safe, 0xffffffff, 7_000, false, true);
    }

    private static String joinText(String title, String body) {
        String safeTitle = title == null ? "" : title.trim();
        String safeBody = body == null ? "" : body.trim();
        if (safeTitle.isEmpty()) return safeBody;
        if (safeBody.isEmpty() || safeBody.equals(safeTitle)) return safeTitle;
        return safeTitle + " — " + safeBody;
    }

    private static void appendPart(StringBuilder text, String part) {
        if (part != null && !part.trim().isEmpty()) text.append(part.trim());
    }

    static Weather parseNoaaObservation(byte[] payload) {
        try {
            JSONObject properties = new JSONObject(new String(payload, java.nio.charset.StandardCharsets.UTF_8))
                    .getJSONObject("properties");
            double celsius = properties.getJSONObject("temperature").getDouble("value");
            String description = properties.optString("textDescription", "");
            if (!Double.isFinite(celsius) || celsius < -100 || celsius > 70) return null;
            return new Weather((float) (celsius * 1.8 + 32), noaaCondition(description));
        } catch (JSONException | RuntimeException exception) {
            return null;
        }
    }

    static float parseIndoorTemperature(byte[] payload, boolean celsiusDisplay) {
        try {
            double celsius = new JSONObject(new String(payload, java.nio.charset.StandardCharsets.UTF_8))
                    .getDouble("temperature");
            if (!Double.isFinite(celsius) || celsius < -100 || celsius > 100) return Float.NaN;
            return (float) (celsiusDisplay ? celsius : celsius * 1.8 + 32);
        } catch (JSONException | RuntimeException exception) {
            return Float.NaN;
        }
    }

    static Float parseSpeed(byte[] payload) {
        try {
            float speed = Float.parseFloat(new String(payload, java.nio.charset.StandardCharsets.UTF_8).trim());
            return Float.isFinite(speed) && speed >= 0 && speed <= 1_000_000 ? speed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static boolean isDismissTopic(String topic, String root) {
        return topic.equals(root + "/dismiss") || topic.startsWith(root + "/dismiss/");
    }

    static String sanitizeText(String text) {
        // The 7,280-byte receive budget fits 154 captured 47-byte native glyphs.
        StringBuilder safe = new StringBuilder(Math.min(text.length(), 154));
        for (int i = 0; i < text.length() && safe.length() < 154; i++) {
            char character = text.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) i++;
                safe.append('?');
            } else if (Character.isLowSurrogate(character)) safe.append('?');
            else safe.append(character);
        }
        return safe.toString();
    }

    static int parseColor(Object value) {
        try {
            if (value instanceof Number) return 0xff000000 | (((Number) value).intValue() & 0x00ffffff);
            if (value instanceof String) {
                String hex = ((String) value).trim().replace("#", "");
                if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0)
                        + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
                if (hex.length() == 6) return 0xff000000 | Integer.parseInt(hex, 16);
            }
            if (value instanceof JSONArray) {
                JSONArray rgb = (JSONArray) value;
                if (rgb.length() == 3) return ColorParts.pack(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2));
                if (rgb.length() == 4 && "HSV".equalsIgnoreCase(rgb.getString(0))) {
                    float hue = (float) (rgb.getDouble(1) % 360.0);
                    float saturation = (float) Math.max(0, Math.min(100, rgb.getDouble(2))) / 100f;
                    float brightness = (float) Math.max(0, Math.min(100, rgb.getDouble(3))) / 100f;
                    float[] hsv = {hue, saturation, brightness};
                    return 0xff000000 | android.graphics.Color.HSVToColor(hsv) & 0x00ffffff;
                }
            }
        } catch (JSONException | RuntimeException ignored) { }
        return 0xffffffff;
    }

    private static String noaaCondition(String value) {
        String text = value.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("thunder") || text.contains("lightning")) return "lightning-rainy";
        if (text.contains("snow") || text.contains("blizzard") || text.contains("flurr")) return "snowy";
        if (text.contains("rain") || text.contains("shower") || text.contains("drizzle"))
            return text.contains("heavy") || text.contains("downpour") ? "pouring" : "rainy";
        if (text.contains("fog") || text.contains("mist") || text.contains("haze")) return "fog";
        if (text.contains("partly") || text.contains("few clouds")) return "partlycloudy";
        if (text.contains("cloud") || text.contains("overcast")) return "cloudy";
        if (text.contains("clear") || text.contains("sunny") || text.contains("fair")) return "sunny";
        return "unknown";
    }

    private static final class ColorParts {
        static int pack(int red, int green, int blue) {
            return 0xff000000 | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
        }
        static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
    }
}
