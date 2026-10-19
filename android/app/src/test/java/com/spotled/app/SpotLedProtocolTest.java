package com.spotled.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class SpotLedProtocolTest {
    @Test public void textSpeedPresetsCoverFiveIntervals() {
        int[] expected = {300, 250, 200, 150, 100};
        String[] labels = TextSpeedSettings.labels();
        assertEquals(expected.length, labels.length);
        for (int position = 0; position < expected.length; position++) {
            assertEquals(expected[position], TextSpeedSettings.millisecondsForPosition(position));
            assertEquals(position, TextSpeedSettings.positionForMilliseconds(expected[position]));
        }
        assertThrows(IllegalArgumentException.class,
                () -> TextSpeedSettings.millisecondsForPosition(expected.length));
    }

    @Test public void matchesSpotLedNamePrefixAndRejectsUnrelatedDevices() {
        assertTrue(SpotLedBleClient.matchesSpotLedName("SpotLED_Example"));
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

    @Test public void mqttNotificationPayloadPreservesTextColorAndQueueOptions() {
        String payload = "{\"text\":[\"Door \",{\"text\":\"bell 🔔\"}],"
                + "\"textColor\":\"#12AB34\",\"durationMs\":9000,\"hold\":true,\"stack\":false}";
        MqttPayloads.Alert alert = MqttPayloads.parseAlert(payload.getBytes(StandardCharsets.UTF_8));
        assertEquals("Door bell ?", alert.text);
        assertEquals(0xff12ab34, alert.color);
        assertEquals(9000, alert.durationMs);
        assertTrue(alert.hold);
        assertFalse(alert.stack);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) longText.append('x');
        MqttPayloads.Alert bounded = MqttPayloads.parseAlert(
                ("{\"text\":\"" + longText + "\"}").getBytes(StandardCharsets.UTF_8));
        assertEquals(154, bounded.text.length());
    }

    @Test public void mqttWeatherSourcesNormalizeNoaaAndHomeAssistantValues() {
        String noaaPayload = "{\"properties\":{\"temperature\":{\"value\":10},"
                + "\"textDescription\":\"Light Rain\"}}}";
        MqttPayloads.Weather noaa = MqttPayloads.parseNoaaObservation(
                noaaPayload.getBytes(StandardCharsets.UTF_8));
        MqttPayloads.Weather ha = MqttPayloads.parseHomeAssistantWeather(
                "{\"temp\":72.5,\"cond\":\"partlycloudy\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(50f, noaa.fahrenheit, 0.01f);
        assertEquals("rainy", noaa.condition);
        assertEquals(72.5f, ha.fahrenheit, 0.01f);
        assertEquals("partlycloudy", ha.condition);
    }

    @Test public void selectedPhoneNotificationsBecomeBoundedScrollingAlerts() {
        MqttPayloads.Alert alert = MqttPayloads.phoneNotification("Messages", "Duv", "Hello there");
        assertEquals("Messages: Duv — Hello there", alert.text);
        assertEquals(0xffffffff, alert.color);
        assertEquals(7_000, alert.durationMs);
        assertTrue(alert.stack);
        assertEquals(154, MqttPayloads.phoneNotification("App", "", "x".repeat(200)).text.length());
        assertEquals(null, MqttPayloads.phoneNotification("", "", "  \n "));
    }

    @Test public void companionNotificationForwardingAllowsOnlyMqttFailures() {
        String ownPackage = "com.spotled.app";
        assertTrue(PhoneNotificationListenerService.shouldForwardNotification(ownPackage,
                ownPackage, true, "SPOTLED Companion status service",
                "MQTT connection failed (reason 32103); retrying"));
        assertTrue(PhoneNotificationListenerService.shouldForwardNotification(ownPackage,
                ownPackage, true, "SPOTLED Companion status service",
                "MQTT disconnected; reconnecting"));
        assertTrue(PhoneNotificationListenerService.shouldForwardNotification(ownPackage,
                ownPackage, true, "SPOTLED Companion status service",
                "Connected, but MQTT topic subscription failed"));
        assertFalse(PhoneNotificationListenerService.shouldForwardNotification(ownPackage,
                ownPackage, true, "SPOTLED Companion status service", "Connected to MQTT"));
        assertFalse(PhoneNotificationListenerService.shouldForwardNotification(ownPackage,
                ownPackage, true, "SPOTLED Companion status service",
                "Clock/weather update acknowledged; check the display"));
        assertFalse(PhoneNotificationListenerService.shouldForwardNotification("other.app",
                ownPackage, true, "Ongoing", "Routine notification"));
        assertTrue(PhoneNotificationListenerService.shouldForwardNotification("other.app",
                ownPackage, false, "Messages", "Hello"));
        assertEquals("connection", PhoneNotificationListenerService.companionMqttErrorKey(
                "Status", "MQTT disconnected; reconnecting"));
        assertEquals("subscription", PhoneNotificationListenerService.companionMqttErrorKey(
                "Status", "Connected, but MQTT topic subscription failed"));
        assertEquals("MQTT CONNECTION FAILED",
                PhoneNotificationListenerService.companionMqttErrorText("Status",
                        "MQTT connection failed (reason 32103); retrying", "connection"));
        assertEquals("MQTT DISCONNECTED",
                PhoneNotificationListenerService.companionMqttErrorText("Status",
                        "MQTT disconnected; reconnecting", "connection"));
        assertEquals("MQTT TOPIC FAILED",
                PhoneNotificationListenerService.companionMqttErrorText("Status",
                        "Connected, but MQTT topic subscription failed", "subscription"));
        assertFalse(PhoneNotificationListenerService.shouldForwardCompanionMqttError(
                "Status", "MQTT connection failed; retrying", "connection"));
        assertTrue(PhoneNotificationListenerService.shouldForwardCompanionMqttError(
                "Status", "MQTT connection failed; retrying", null));
        assertTrue(PhoneNotificationListenerService.isMqttRecoveryStatus(
                "SPOTLED Companion status service", "Reconnected to MQTT"));
        assertTrue(PhoneNotificationListenerService.isMqttRecoveryStatus(
                "SPOTLED Companion status service", "Running; connecting to MQTT"));
    }

    @Test public void androidClockFrameIs64By16AndSpeedBarIsOptional() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 18, 15, 5, 0, 0,
                ZoneId.of("America/Los_Angeles"));
        MqttPayloads.Weather weather = new MqttPayloads.Weather(68, "sunny");
        byte[] noSpeed = ClockTempRenderer.render(now, 70f, weather, null, false);
        byte[] speed = ClockTempRenderer.render(now, 70f, weather, 500f, false);
        assertEquals(64 * 16 * 3, noSpeed.length);
        assertFalse(java.util.Arrays.equals(noSpeed, speed));
        for (int i = 15 * 64 * 3; i < 16 * 64 * 3; i++) assertEquals(0, noSpeed[i]);
    }

    @Test public void clockFrameReservesRightEdgeForStackedWeatherIcons() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 18, 15, 5, 0, 0,
                ZoneId.of("America/Los_Angeles"));
        MqttPayloads.Weather weather = new MqttPayloads.Weather(68, "sunny");
        byte[] base = ClockTempRenderer.render(now, 70f, weather, 1000f, false);
        int speedEnd = (15 * 64 + 55) * 3;
        int iconColumn = (15 * 64 + 56) * 3;
        assertArrayEquals(new byte[] {0, (byte) 255, 0},
                java.util.Arrays.copyOfRange(base, speedEnd, speedEnd + 3));
        assertArrayEquals(new byte[] {0, 0, 0},
                java.util.Arrays.copyOfRange(base, iconColumn, iconColumn + 3));

        byte[] moon = new byte[8 * 8 * 3];
        byte[] condition = new byte[8 * 8 * 3];
        java.util.Arrays.fill(moon, (byte) 0x11);
        java.util.Arrays.fill(condition, (byte) 0x22);
        byte[] composed = ClockTempRenderer.composeWeatherIcons(base, moon, condition);
        int topIcon = (0 * 64 + 56) * 3;
        int bottomIcon = (8 * 64 + 56) * 3;
        assertArrayEquals(new byte[] {0x11, 0x11, 0x11},
                java.util.Arrays.copyOfRange(composed, topIcon, topIcon + 3));
        assertArrayEquals(new byte[] {0x22, 0x22, 0x22},
                java.util.Arrays.copyOfRange(composed, bottomIcon, bottomIcon + 3));
    }

    @Test public void wetterMoonCalculationAndWeatherIconAliasesMatchAwtrix() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 18, 12, 0, 0, 0,
                ZoneId.of("America/Los_Angeles"));
        assertEquals(2320, WeatherIconAnimation.moonIconId(now));
        assertEquals("lightning.gif", WeatherIconAnimation.weatherAssetName("lightning"));
        assertEquals("lightning-rainy.gif", WeatherIconAnimation.weatherAssetName("lightning-rainy"));
        assertEquals("unknown.gif", WeatherIconAnimation.weatherAssetName("other-condition"));
    }

    @Test public void mqttAlertCanUseExactRgbAndProminentScrollEffect() {
        byte[] text = SpotLedProtocol.buildNativeTextTransfersSolidColor(
                "A", 0xff12ab34, 6, 200)[1];
        assertEquals(0x12, text[31] & 0xff);
        assertEquals(0xab, text[32] & 0xff);
        assertEquals(0x34, text[33] & 0xff);
        assertEquals(6, text[text.length - 2] & 0xff);
    }

    @Test public void noaaClockWeatherCanRunWithAllMqttSettingsBlank() {
        assertFalse(MqttStatusService.shouldConnectMqtt("noaa", "", ""));
        assertFalse(MqttStatusService.shouldConnectMqtt("noaa", "broker.local", ""));
        assertTrue(MqttStatusService.shouldConnectMqtt("noaa", "broker.local", "awtrix/cmd/notify"));
        assertTrue(MqttStatusService.shouldConnectMqtt("homeassistant", "broker.local", ""));
        assertFalse(MqttStatusService.shouldConnectMqtt("homeassistant", "", ""));
    }

    @Test public void tc001AddressExpandsToExpectedTemperatureEndpoint() throws Exception {
        assertEquals("http://192.168.8.99/api/v1/device",
                MqttStatusService.normalizeIndoorUrl("192.168.8.99"));
        assertEquals("http://sensor.local/api/v1/device",
                MqttStatusService.normalizeIndoorUrl("http://sensor.local/api/v1/device"));
        assertThrows(java.net.MalformedURLException.class,
                () -> MqttStatusService.normalizeIndoorUrl("ftp://sensor.local"));
    }

    @Test public void allEightSupportedEffectsUseNativeTextAndUnknownEffectIsRejected() {
        for (int effect = 0; effect <= 7; effect++)
            assertTrue(SpotLedProtocol.isNativeTextEffect(effect));
        assertFalse(SpotLedProtocol.isNativeTextEffect(8));
    }

    @Test public void nativeMarqueeUsesCapturedPythonGlyphsForWideLetters() {
        byte[] w = SpotLedProtocol.nativeFontObject('W');
        byte[] n = SpotLedProtocol.nativeFontObject('N');
        assertEquals(47, w.length);
        assertEquals(47, n.length);
        assertEquals('W', u16(w, 11));
        assertEquals('N', u16(n, 11));
        assertEquals(SpotLedProtocol.checksum(w, 46), w[46] & 0xff);
        assertEquals(SpotLedProtocol.checksum(n, 46), n[46] & 0xff);
        assertFalse(java.util.Arrays.equals(w, n));
    }

    @Test public void capitalINativeGlyphHasSerifs() {
        byte[] glyph = SpotLedProtocol.nativeFontObject('I');
        assertEquals(47, glyph.length);
        assertEquals(0, u16(glyph, 14));
        assertEquals(0, u16(glyph, 16));
        assertEquals(0x1ff0, u16(glyph, 18));
        assertEquals(0x0100, u16(glyph, 20));
        assertEquals(0x0100, u16(glyph, 38));
        assertEquals(0x1ff0, u16(glyph, 40));
        assertEquals(0, u16(glyph, 42));
        assertEquals(0, u16(glyph, 44));
        assertEquals(SpotLedProtocol.checksum(glyph, 46), glyph[46] & 0xff);
    }

    @Test public void nativeMarqueeMatchesPerCharacterFontOrderAndColoredCharacterString() {
        byte[][] transfers = SpotLedProtocol.buildNativeTextTransfers("ABA", 1, 3);
        byte[] wordStock = transfers[0];
        byte[] characterString = transfers[1];
        assertEquals(1, u32(wordStock, 6));
        assertEquals(5, u16(wordStock, 19));
        assertEquals(3, u16(wordStock, 21)); // Python sends one FontObject per text character
        assertEquals(13, u16(wordStock, 28));
        assertEquals('A', u16(wordStock, 35));
        assertEquals('B', u16(wordStock, 82));
        assertEquals('A', u16(wordStock, 129));
        assertEquals(4, u16(characterString, 19));
        assertEquals(3, u16(characterString, 21));
        assertEquals(1, characterString[23] & 0xff);
        assertEquals(2, u16(characterString, 29));
        assertEquals(255, characterString[31] & 0xff);
        assertEquals(0, characterString[32] & 0xff);
        assertEquals(0, characterString[33] & 0xff);
        assertEquals(3, u16(characterString, 39));
        assertEquals('A', u16(characterString, 41));
        assertEquals(8, u16(characterString, characterString.length - 4));
        assertEquals(3, characterString[characterString.length - 2] & 0xff);
        assertEquals(SpotLedProtocol.checksum(wordStock, 14), wordStock[14] & 0xff);
        assertEquals(SpotLedProtocol.checksum(characterString, 14), characterString[14] & 0xff);
    }

    @Test public void nativeTextTimingIsEncodedInCharacterStringTimeObject() {
        byte[] normal = SpotLedProtocol.buildNativeTextTransfers("A", 0, 3, 200)[1];
        byte[] slow = SpotLedProtocol.buildNativeTextTransfers("A", 0, 3, 300)[1];
        int timeOffset = normal.length - 18;
        assertEquals(7, u16(normal, timeOffset + 4));
        assertEquals(200, u16(normal, timeOffset + 7));
        assertEquals(300, u16(slow, timeOffset + 7));
        assertThrows(IllegalArgumentException.class,
                () -> SpotLedProtocol.buildNativeTextTransfers("A", 0, 3, 0));
    }

    @Test public void rasterTextTimingIsEncodedInFrameAnimationTimeObject() {
        byte[] frame = new byte[] {(byte) 255, (byte) 255, (byte) 255};
        byte[] slow = SpotLedProtocol.buildRgbAnimation(frame, 1, 1, 300, 3);
        byte[] fast = SpotLedProtocol.buildRgbAnimation(frame, 1, 1, 100, 3);
        assertEquals(300, u16(slow, slow.length - 11));
        assertEquals(100, u16(fast, fast.length - 11));
    }

    @Test public void nativeTextSetupPacketsMatchPythonCommandFraming() {
        byte[] playlist = SpotLedProtocol.buildNativeTextSetupPlaylist();
        byte[] pixelMap = SpotLedProtocol.buildNativeTextSetupPixelMap();
        assertEquals(20, playlist.length);
        assertEquals(0x800c, u16(playlist, 4));
        assertEquals(1, u32(playlist, 6));
        assertEquals(13, pixelMap.length);
        assertEquals(0x8000, u16(pixelMap, 4));
        assertEquals(3, u32(pixelMap, 6));
        assertEquals(0x10, u16(pixelMap, 10));
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

    @Test public void nativeTextIsDefaultAtZeroDegreesAndRasterCanBeForced() {
        assertTrue(TextOutputMode.useNativeText(false, 0));
        assertFalse(TextOutputMode.useNativeText(true, 0));
        assertFalse(TextOutputMode.useNativeText(false, 90));
        assertFalse(TextOutputMode.useNativeText(false, 180));
        assertFalse(TextOutputMode.useNativeText(false, 270));
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
