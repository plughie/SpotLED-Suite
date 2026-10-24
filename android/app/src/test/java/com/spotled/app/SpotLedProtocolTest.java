package com.spotled.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class SpotLedProtocolTest {
    @Test public void imageImportBoundsAndExifOrientationMappingsAreCorrect() {
        assertEquals(1, ImageImportPolicy.sampleSize(64, 16));
        assertEquals(2, ImageImportPolicy.sampleSize(4000, 3000));
        assertEquals(32, ImageImportPolicy.sampleSize(40000, 30000));
        assertThrows(IllegalArgumentException.class, () -> ImageImportPolicy.sampleSize(0, 10));
        assertThrows(IllegalArgumentException.class, () -> ImageImportPolicy.sampleSize(50000, 50000));
        assertArrayEquals(new int[] {4000, 3000}, ImageImportPolicy.orientedSize(4000, 3000, 1));
        assertArrayEquals(new int[] {3000, 4000}, ImageImportPolicy.orientedSize(4000, 3000, 6));
        assertArrayEquals(new int[] {3, 0}, ImageImportPolicy.orientPixel(0, 0, 4, 3, 2));
        assertArrayEquals(new int[] {2, 0}, ImageImportPolicy.orientPixel(0, 0, 4, 3, 6));
        assertArrayEquals(new int[] {0, 3}, ImageImportPolicy.orientPixel(0, 0, 4, 3, 8));
        assertArrayEquals(new int[] {2, 3}, ImageImportPolicy.orientPixel(0, 0, 4, 3, 7));
    }

    @Test public void gifSamplingSpansTheSourceAndUsesBoundedWholeCycleTiming() {
        assertArrayEquals(new int[] {0}, ImageImportPolicy.gifSampleIndices(1, 2));
        assertArrayEquals(new int[] {0, 7}, ImageImportPolicy.gifSampleIndices(8, 2));
        assertArrayEquals(new int[] {0, 2, 3}, ImageImportPolicy.gifSampleIndices(4, 3));
        assertEquals(900, ImageImportPolicy.gifIntervalMs(1800, 2));
        assertEquals(250, ImageImportPolicy.gifIntervalMs(100, 2));
        assertEquals(1000, ImageImportPolicy.gifIntervalMs(4000, 2));
    }

    @Test public void gifSamplingMatchesSharedCrossPlatformVectors() throws Exception {
        JSONObject vectors;
        try (InputStream stream = getClass().getResourceAsStream("/gif_processing_vectors.json")) {
            if (stream == null) throw new AssertionError("GIF processing fixture was not packaged");
            vectors = new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        org.json.JSONArray cases = vectors.getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.getJSONObject(i);
            org.json.JSONArray indices = item.getJSONArray("indices");
            int[] expected = new int[indices.length()];
            for (int j = 0; j < indices.length(); j++) expected[j] = indices.getInt(j);
            assertArrayEquals(expected, ImageImportPolicy.gifSampleIndices(
                    item.getInt("source_frames"), item.getInt("limit")));
            assertEquals(item.getInt("interval_ms"), ImageImportPolicy.gifIntervalMs(
                    item.getLong("duration_ms"), expected.length));
        }
    }

    @Test public void trackedWeatherGifMetadataParserCountsFramesAndDelay() throws Exception {
        java.net.URL resource = getClass().getResource("/cloudy.gif");
        if (resource == null) throw new AssertionError("Canonical weather GIF test asset was not packaged");
        GifMetadata metadata = GifMetadata.read(new java.io.File(resource.toURI()));
        assertEquals(64, metadata.width);
        assertEquals(16, metadata.height);
        assertEquals(16, metadata.frameCount);
        assertEquals(3200, metadata.durationMs);
    }

    @Test public void liveCapabilitiesAreParsedAndIncompatibleDevicesRejected() {
        byte[] screen = new byte[] {14, 0x13, 0, 1, 0, 0, 64, 0, 16, (byte) 0xff, 8, 80, 4, 1};
        byte[] animation = new byte[] {9, 0x1b, 0, 2, 0, 0, 8, 0, (byte) 160};
        byte[] capacity = new byte[] {9, 0x15, 0, 3, 0, 0, 0, 0x1c, 0x70};
        SpotLedProtocol.Capabilities parsed = SpotLedProtocol.parseCapabilities(
                screen, animation, capacity, 1, 2, 3);
        assertEquals(64, parsed.width);
        assertEquals(16, parsed.height);
        assertEquals(0xff, parsed.colorType);
        assertEquals(8, parsed.maxFrames);
        assertEquals(7280, parsed.receiveBytes);
        SpotLedProtocol.validateRgb(parsed, 64, 16, 2, 7000);
        SpotLedProtocol.validateNativeText(parsed, new byte[][] {{1}, {2}});
        assertThrows(IllegalArgumentException.class, () -> SpotLedProtocol.validateRgb(parsed, 32, 16, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> SpotLedProtocol.validateRgb(parsed, 64, 16, 9, 7000));
        assertThrows(IllegalArgumentException.class, () -> SpotLedProtocol.validateRgb(parsed, 64, 16, 1, 7281));
        SpotLedProtocol.Capabilities wrongColor = new SpotLedProtocol.Capabilities(64, 16, 0x18, 8, 7280);
        assertThrows(IllegalArgumentException.class, () -> SpotLedProtocol.validateRgb(wrongColor, 64, 16, 1, 100));
        SpotLedProtocol.Capabilities smallBuffer = new SpotLedProtocol.Capabilities(64, 16, 0xff, 8, 100);
        assertThrows(IllegalArgumentException.class, () -> SpotLedProtocol.validateNativeText(smallBuffer, new byte[][] {{0}, new byte[101]}));
        assertArrayEquals(new byte[] {4, 0x12, 0, 1}, SpotLedProtocol.buildQuery(0x12, 1));
        assertThrows(IllegalArgumentException.class, () ->
                SpotLedProtocol.parseCapabilities(screen, animation, capacity, 9, 2, 3));
        assertThrows(IllegalArgumentException.class, () ->
                SpotLedProtocol.parseCapabilities(screen, animation, new byte[] {8, 0x15, 0, 3, 0, 0, 0, 1, 0}, 1, 2, 3));
    }

    private JSONObject sharedFixtures() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/spotled_protocol_vectors.json")) {
            if (stream == null) throw new AssertionError("Shared protocol fixture was not packaged");
            return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private JSONObject sharedGlyphData() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/spotled_glyphs.json")) {
            if (stream == null) throw new AssertionError("Canonical glyph data was not packaged");
            return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static byte[] unhex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    private static boolean contains(byte[] data, byte[] sequence) {
        outer: for (int i = 0; i <= data.length - sequence.length; i++) {
            for (int j = 0; j < sequence.length; j++)
                if (data[i + j] != sequence[j]) continue outer;
            return true;
        }
        return false;
    }

    @Test public void sharedRotationVectorsMatchFixture() throws Exception {
        JSONObject vector = sharedFixtures().getJSONObject("rotations_rgb");
        int width = vector.getInt("width");
        int height = vector.getInt("height");
        byte[] input = unhex(vector.getString("input_hex"));
        JSONObject expected = vector.getJSONObject("expected");
        for (int degrees : new int[] {0, 90, 180, 270}) {
            JSONObject result = expected.getJSONObject(Integer.toString(degrees));
            RgbTransforms.Frame actual = RgbTransforms.rotate(input, width, height, degrees);
            assertEquals(result.getInt("width"), actual.width);
            assertEquals(result.getInt("height"), actual.height);
            assertArrayEquals(unhex(result.getString("hex")), actual.rgb);
        }
    }

    @Test public void sharedNativeGlyphVectorsMatchFixture() throws Exception {
        JSONObject source = sharedGlyphData();
        JSONObject captured = source.getJSONObject("captured_panel16_hex");
        assertEquals(captured.length(), GeneratedGlyphs.CAPTURED_PANEL16_HEX.size());
        for (Iterator<String> codes = captured.keys(); codes.hasNext();) {
            String code = codes.next();
            int codePoint = Integer.parseInt(code);
            byte[] expected = unhex(captured.getString(code));
            assertArrayEquals(expected, GeneratedGlyphs.capturedPanel16(codePoint));
            assertArrayEquals(expected, SpotLedProtocol.nativeFontObject((char) codePoint));
        }
    }

    @Test public void sharedCompactGlyphTableMatchesCanonicalSource() throws Exception {
        JSONObject compact = sharedGlyphData().getJSONObject("compact_3x5");
        assertEquals(compact.length(), GeneratedGlyphs.COMPACT_3X5.size());
        for (Iterator<String> characters = compact.keys(); characters.hasNext();) {
            String character = characters.next();
            org.json.JSONArray rows = compact.getJSONArray(character);
            String[] expected = new String[rows.length()];
            for (int i = 0; i < rows.length(); i++) expected[i] = rows.getString(i);
            assertArrayEquals(expected, GeneratedGlyphs.compactRowsFor(character.charAt(0)));
        }
    }

    @Test public void sharedBlackFramePacketMatchesFixtureDigest() throws Exception {
        JSONObject vector = sharedFixtures().getJSONObject("black_frame_64x16_1000ms");
        byte[] payload = SpotLedProtocol.buildRgbAnimation(
                new byte[vector.getInt("width") * vector.getInt("height") * 3],
                vector.getInt("width"), vector.getInt("height"), vector.getInt("interval_ms"),
                vector.getInt("effect_mode"));
        assertEquals(vector.getInt("payload_length"), payload.length);
        assertEquals(vector.getString("payload_sha256"), sha256(payload));
        byte[] prefix = unhex(vector.getString("payload_prefix_hex"));
        assertArrayEquals(prefix, java.util.Arrays.copyOf(payload, prefix.length));
    }

    @Test public void nativeBmpFallbackUsesQuestionGlyphAndPreservesCodePoint() throws Exception {
        JSONObject vector = sharedFixtures().getJSONObject("native_text_unicode");
        char character = (char) vector.getInt("bmp_fallback_codepoint");
        byte[] glyph = SpotLedProtocol.nativeFontObject(character);
        assertArrayEquals(unhex(vector.getString("glyph_object_hex")), glyph);
        assertEquals(vector.getInt("bmp_fallback_codepoint"), u16(glyph, 11));
        assertArrayEquals(java.util.Arrays.copyOfRange(SpotLedProtocol.nativeFontObject('?'), 14, 46),
                java.util.Arrays.copyOfRange(glyph, 14, 46));
        byte[] textTransfer = SpotLedProtocol.buildNativeTextTransfers(character + "", 0, 3)[1];
        assertTrue(contains(textTransfer, unhex(vector.getString("character_text_object_hex"))));
        assertThrows(IllegalArgumentException.class,
                () -> SpotLedProtocol.buildNativeTextTransfers(
                        vector.getString("non_bmp_example"), 0, 3));
    }

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
        assertEquals("http://192.0.2.42/api/v1/device",
                MqttStatusService.normalizeIndoorUrl("192.0.2.42"));
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

    @Test public void lowercaseDescendersReachTheLastCellRow() {
        for (char ch : new char[] {'g', 'j', 'p', 'q', 'y'}) {
            byte[] glyph = SpotLedProtocol.nativeFontObject(ch);
            assertTrue(glyph[44] != 0 || glyph[45] != 0);
            assertTrue(glyph[42] != 0 || glyph[43] != 0);
            assertEquals(SpotLedProtocol.checksum(glyph, 46), glyph[46] & 0xff);
            assertEquals(ch, (char) (((glyph[11] & 0xff) << 8) | (glyph[12] & 0xff)));
            int firstInkRow = 0;
            while (firstInkRow < 16 && glyph[14 + firstInkRow * 2] == 0
                    && glyph[15 + firstInkRow * 2] == 0) firstInkRow++;
            assertEquals(ch == 'j' ? 1 : ch == 'g' ? 3 : 4, firstInkRow);
            if (ch == 'g') assertEquals(0x0300,
                    ((glyph[40] & 0xff) << 8) | (glyph[41] & 0xff));
            if (ch == 'g') assertEquals(0x0600,
                    ((glyph[42] & 0xff) << 8) | (glyph[43] & 0xff));
            if (ch == 'g') assertEquals(0x0c00,
                    ((glyph[44] & 0xff) << 8) | (glyph[45] & 0xff));
        }
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

    @Test public void imageRotationDoesNotControlNativeTextAndPortraitKeepsNativeScrolling() {
        assertTrue(TextOutputMode.useNativeText(false, 0));
        assertFalse(TextOutputMode.useNativeText(true, 0));
        assertFalse(TextOutputMode.useNativeText(false, 90));
        assertFalse(TextOutputMode.useNativeText(true, 90));
        assertTrue(TextOutputMode.useNativeText(false, 270));
        assertTrue(TextOutputMode.useNativeText(true, 270));
        CompactTextRaster.PanelFrames frames = CompactTextRaster.renderForPanel("HELLO", 0, 270);
        assertEquals(64, frames.preview.width);
        assertEquals(16, frames.preview.height);
        assertEquals(64, frames.wire.width);
        assertEquals(16, frames.wire.height);
        assertArrayEquals(frames.preview.rgb, frames.wire.rgb);
        assertFalse(java.util.Arrays.equals(CompactTextRaster.renderForPanel("HELLO", 0, 0)
                .preview.rgb, frames.preview.rgb));
        byte[] nativeGlyph = SpotLedProtocol.nativeFontObject('A');
        byte[] landscapeCell = new byte[16 * 16 * 3];
        for (int y = 0; y < 16; y++) {
            int bits = ((nativeGlyph[14 + y * 2] & 0xff) << 8)
                    | (nativeGlyph[15 + y * 2] & 0xff);
            for (int x = 0; x < 16; x++) if ((bits & (0x8000 >>> x)) != 0) {
                int offset = (y * 16 + x) * 3;
                landscapeCell[offset] = landscapeCell[offset + 1] = landscapeCell[offset + 2] = (byte) 255;
            }
        }
        byte[] expectedRotatedCell = RgbTransforms.rotate(landscapeCell, 16, 16, 270).rgb;
        byte[] actualRotatedCell = new byte[16 * 16 * 3];
        byte[] panel = CompactTextRaster.renderForPanel("A", 0, 270).preview.rgb;
        for (int y = 0; y < 16; y++)
            System.arraycopy(panel, y * 64 * 3, actualRotatedCell, y * 16 * 3, 16 * 3);
        assertArrayEquals(expectedRotatedCell, actualRotatedCell);
        byte[][] scrolled = SpotLedProtocol.buildRotatedNativeTextTransfers("HELLO", 0,
                3, 200, 270);
        assertEquals(15 + 9 + 5 * 47, scrolled[0].length);
        byte[] expectedO = SpotLedProtocol.rotatedFontObject('O', 270);
        assertArrayEquals(expectedO, java.util.Arrays.copyOfRange(scrolled[0],
                15 + 9 + 4 * 47, 15 + 9 + 5 * 47));
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

    @Test public void zoomAndPanSelectOppositeEdgesOfTheViewport() {
        byte[] source = new byte[] {(byte) 255, 0, 0, 0, 0, (byte) 255};
        RgbTransforms.Frame left = RgbTransforms.fit(source, 2, 1, 2, 1,
                "stretch", 2, -1, 0);
        RgbTransforms.Frame right = RgbTransforms.fit(source, 2, 1, 2, 1,
                "stretch", 2, 1, 0);
        assertArrayEquals(new byte[] {(byte) 255, 0, 0, (byte) 255, 0, 0}, left.rgb);
        assertArrayEquals(new byte[] {0, 0, (byte) 255, 0, 0, (byte) 255}, right.rgb);
    }

    @Test public void zoomAndPanSelectOppositeEdgesOfTheImageViewport() {
        byte[] source = new byte[] {(byte) 255, 0, 0, 0, 0, (byte) 255};
        RgbTransforms.Frame left = RgbTransforms.fit(source, 2, 1, 2, 1,
                "stretch", 2, -1, 0);
        RgbTransforms.Frame right = RgbTransforms.fit(source, 2, 1, 2, 1,
                "stretch", 2, 1, 0);
        assertArrayEquals(new byte[] {(byte) 255, 0, 0, (byte) 255, 0, 0}, left.rgb);
        assertArrayEquals(new byte[] {0, 0, (byte) 255, 0, 0, (byte) 255}, right.rgb);
    }

    @Test public void verticalScrollProducesAdjacentTopToBottomViewports() {
        byte[] source = new byte[16 * 32 * 3];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 16; x++) {
            int offset = (y * 16 + x) * 3;
            if (y < 16) source[offset] = (byte) 255;
            else source[offset + 2] = (byte) 255;
        }
        RgbTransforms.Frame[] frames = RgbTransforms.verticalScroll(source, 16, 32);
        assertEquals(8, frames.length);
        assertEquals(64 * 16 * 3, frames[0].rgb.length);
        assertEquals((byte) 255, frames[0].rgb[0]);
        assertEquals((byte) 255, frames[frames.length - 1].rgb[2]);
        byte[] animation = SpotLedProtocol.buildRgbAnimation(
                new byte[][] {frames[0].rgb, frames[1].rgb}, 64, 16, 250, 1);
        assertTrue(indexOf(animation, new byte[] {0, 0, 0, 8, 0, 8, 1, 17}) >= 0);
    }

    private static int indexOf(byte[] bytes, byte[] sequence) {
        for (int start = 0; start <= bytes.length - sequence.length; start++) {
            int index = 0;
            while (index < sequence.length && bytes[start + index] == sequence[index]) index++;
            if (index == sequence.length) return start;
        }
        return -1;
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
