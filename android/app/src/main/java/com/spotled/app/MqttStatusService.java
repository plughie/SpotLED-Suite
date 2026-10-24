package com.spotled.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** User-started Android foreground service for MQTT alerts and ClockTemp status. */
public final class MqttStatusService extends Service {
    private static final String TAG = "MqttStatusService";
    static final String ACTION_START = "com.spotled.app.action.START_STATUS";
    static final String ACTION_STOP = "com.spotled.app.action.STOP_STATUS";
    static final String PREFS = "spotled_settings";
    static final String PREF_ENABLED = "status_service_enabled";
    static final String PREF_BROKER = "mqtt_broker_host";
    static final String PREF_PORT = "mqtt_broker_port";
    static final String PREF_TLS = "mqtt_tls";
    static final String PREF_USERNAME = "mqtt_username";
    static final String PREF_PASSWORD = "mqtt_password";
    static final String PREF_NOTIFY_TOPIC = "mqtt_notify_topic";
    static final String PREF_WEATHER_SOURCE = "mqtt_weather_source";
    static final String PREF_NOAA_STATION = "noaa_weather_station";
    static final String PREF_HA_WEATHER_TOPIC = "mqtt_weather_topic";
    static final String PREF_SPEED_TOPIC = "mqtt_speed_topic";
    static final String PREF_INDOOR_URL = "clocktemp_indoor_url";
    static final String PREF_TIMEZONE = "clock_timezone";
    static final String PREF_WEATHER_UNIT = "weather_unit";
    private static final String PREF_MQTT_CLIENT_ID = "mqtt_client_id";
    private static final String CHANNEL_ID = "spotled_status_service";
    private static final int NOTIFICATION_ID = 1701;
    private static final int MAX_PENDING_ALERTS = 16;
    private static final int MAX_DISPLAY_BACKLOG = 32;
    private static final Pattern STATION_ID = Pattern.compile("[A-Z0-9]{3,5}");
    private static final String PREF_LAST_DEVICE_NAME = "last_device_name";
    private static final String PREF_LAST_DEVICE_ADDRESS = "last_device_address";
    private static final String PREF_TEXT_TIME_MS = "text_time_ms";

    private final Object stateLock = new Object();
    private final ArrayDeque<MqttPayloads.Alert> pendingAlerts = new ArrayDeque<>();
    private ScheduledExecutorService timers;
    private ExecutorService network;
    private ExecutorService mqttConnector;
    private ThreadPoolExecutor displayQueue;
    private MqttClient mqtt;
    private SpotLedBleClient ble;
    private SharedPreferences preferences;
    private String notifyTopic;
    private String weatherTopic;
    private String speedTopic;
    private String weatherSource;
    private String station;
    private String indoorUrl;
    private String timezoneName;
    private String temperatureUnit;
    private MqttPayloads.Weather currentWeather;
    private Float indoorTemperature;
    private Float speedMbps;
    private MqttPayloads.Alert activeAlert;
    private long alertGeneration;
    private volatile int pendingAlertCount;
    private volatile int droppedAlerts;
    private volatile int droppedDisplayTasks;
    private volatile long weatherUpdatedAtMillis;
    private final AtomicBoolean clockRefreshRequested = new AtomicBoolean();
    private final AtomicBoolean clockRefreshTaskScheduled = new AtomicBoolean();
    private volatile boolean stopping;
    private boolean phoneNotificationReceiverRegistered;
    private final BroadcastReceiver phoneNotificationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (intent == null
                    || !PhoneNotificationListenerService.ACTION_FORWARD_NOTIFICATION.equals(intent.getAction())) return;
            String packageName = intent.getStringExtra("source_package");
            boolean forwardingEnabled = preferences.getBoolean(
                    PhoneNotificationListenerService.PREF_FORWARD_ENABLED, false);
            boolean serviceEnabled = preferences.getBoolean(PREF_ENABLED, false);
            if ("com.spotled.app".equals(packageName))
                Log.i(TAG, "Companion alert received; stopping=" + stopping
                        + " displayQueueReady=" + (displayQueue != null)
                        + " forwardingEnabled=" + forwardingEnabled
                        + " serviceEnabled=" + serviceEnabled);
            if (stopping || displayQueue == null
                    || !shouldForwardPhoneNotifications(serviceEnabled, forwardingEnabled)) return;
            Set<String> selected = preferences.getStringSet(
                    PhoneNotificationListenerService.PREF_FORWARD_PACKAGES, Collections.emptySet());
            if (packageName == null || !selected.contains(packageName)) {
                if ("com.spotled.app".equals(packageName))
                    Log.w(TAG, "Companion alert ignored because it is not in the saved app selection");
                return;
            }
            MqttPayloads.Alert alert = MqttPayloads.phoneNotification(
                    intent.getStringExtra("source_label"),
                    intent.getStringExtra("notification_title"),
                    intent.getStringExtra("notification_body"));
            if (alert == null) {
                if ("com.spotled.app".equals(packageName))
                    Log.w(TAG, "Companion alert contained no displayable text");
                return;
            }
            executeDisplay(() -> {
                Log.i(TAG, "Phone notification alert queued from " + packageName);
                enqueueAlert(alert);
            });
        }
    };

    static boolean shouldConnectMqtt(String weatherSource, String brokerHost, String notificationTopic) {
        return brokerHost != null && !brokerHost.trim().isEmpty()
                && ("homeassistant".equals(weatherSource)
                || (notificationTopic != null && !notificationTopic.trim().isEmpty()));
    }

    static boolean shouldForwardPhoneNotifications(boolean serviceEnabled,
                                                   boolean forwardingEnabled) {
        return serviceEnabled && forwardingEnabled;
    }

    static String normalizeIndoorUrl(String value) throws MalformedURLException {
        String address = value == null ? "" : value.trim();
        if (address.isEmpty()) return "";
        if (address.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            if (!address.matches("(?i)^https?://.*"))
                throw new MalformedURLException("TC001 URL must use HTTP or HTTPS");
        } else address = "http://" + address;
        URL parsed = new URL(address);
        if (!"http".equalsIgnoreCase(parsed.getProtocol())
                && !"https".equalsIgnoreCase(parsed.getProtocol()))
            throw new MalformedURLException("TC001 URL must use HTTP or HTTPS");
        if (parsed.getPath().isEmpty() || "/".equals(parsed.getPath()))
            return parsed.getProtocol() + "://" + parsed.getAuthority() + "/api/v1/device";
        return address;
    }

    @Override public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannel();
        IntentFilter filter = new IntentFilter(PhoneNotificationListenerService.ACTION_FORWARD_NOTIFICATION);
        registerPhoneNotificationReceiver(filter);
        phoneNotificationReceiverRegistered = true;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerPhoneNotificationReceiver(IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(phoneNotificationReceiver, filter,
                    PhoneNotificationListenerService.PERMISSION_INTERNAL_NOTIFICATION,
                    null, android.content.Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(phoneNotificationReceiver, filter,
                    PhoneNotificationListenerService.PERMISSION_INTERNAL_NOTIFICATION, null);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            preferences.edit().putBoolean(PREF_ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("Starting"));
        if (timers == null) initialize();
        return START_STICKY;
    }

    private void initialize() {
        String host = preferences.getString(PREF_BROKER, "").trim();
        String portText = preferences.getString(PREF_PORT, "1883").trim();
        notifyTopic = preferences.getString(PREF_NOTIFY_TOPIC, "").trim();
        weatherSource = preferences.getString(PREF_WEATHER_SOURCE, "noaa");
        station = preferences.getString(PREF_NOAA_STATION, "").trim().toUpperCase(Locale.ROOT);
        weatherTopic = preferences.getString(PREF_HA_WEATHER_TOPIC, "").trim();
        speedTopic = preferences.getString(PREF_SPEED_TOPIC, "").trim();
        try { indoorUrl = normalizeIndoorUrl(preferences.getString(PREF_INDOOR_URL, "")); }
        catch (MalformedURLException exception) {
            stopWithError("The TC001 indoor-temperature address or URL is invalid.");
            return;
        }
        timezoneName = preferences.getString(PREF_TIMEZONE, "").trim();
        temperatureUnit = preferences.getString(PREF_WEATHER_UNIT, "F");
        if (checkSelfPermission(android.Manifest.permission.INTERNET)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopWithError("Allow Network access for SPOTLED Companion in Android app settings.");
            return;
        }
        if ("noaa".equals(weatherSource) && !station.isEmpty() && !STATION_ID.matcher(station).matches()) {
            stopWithError("NOAA station ID must be 3–5 letters or digits.");
            return;
        }
        if ("homeassistant".equals(weatherSource) && weatherTopic.isEmpty()) {
            stopWithError("Enter the Home Assistant weather topic in Settings.");
            return;
        }
        if (!"noaa".equals(weatherSource) && !"homeassistant".equals(weatherSource)) {
            stopWithError("Choose NOAA or Home Assistant as the weather source.");
            return;
        }
        boolean mqttEnabled = shouldConnectMqtt(weatherSource, host, notifyTopic);
        if ("homeassistant".equals(weatherSource) && host.isEmpty()) {
            stopWithError("Enter the MQTT broker for Home Assistant weather.");
            return;
        }
        if (!notifyTopic.isEmpty() && host.isEmpty()) {
            stopWithError("Enter the MQTT broker to receive AWTRIX notifications.");
            return;
        }
        if (!"F".equals(temperatureUnit) && !"C".equals(temperatureUnit)) temperatureUnit = "F";
        try { if (!timezoneName.isEmpty()) ZoneId.of(timezoneName); }
        catch (RuntimeException exception) {
            stopWithError("The clock timezone is invalid. Check Settings.");
            return;
        }
        int port = 1883;
        if (mqttEnabled) {
            try { port = Integer.parseInt(portText); }
            catch (NumberFormatException exception) {
                stopWithError("The MQTT port must be a number.");
                return;
            }
            if (port < 1 || port > 65535) {
                stopWithError("The MQTT port must be between 1 and 65535.");
                return;
            }
        }
        String deviceName = preferences.getString(PREF_LAST_DEVICE_NAME, "");
        String deviceAddress = preferences.getString(PREF_LAST_DEVICE_ADDRESS, "");
        if (deviceAddress.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(deviceAddress)) {
            stopWithError("Find and select a SpotLED display before starting the service.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopWithError("Allow Nearby devices permission before starting the status service.");
            return;
        }

        timers = Executors.newScheduledThreadPool(2, runnable -> daemon(runnable, "spotled-status-timer"));
        network = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "spotled-status-network"));
        mqttConnector = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "spotled-mqtt-connection"));
        displayQueue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_DISPLAY_BACKLOG),
                runnable -> daemon(runnable, "spotled-status-display"));
        ble = new SpotLedBleClient(getApplicationContext(), new SpotLedBleClient.Listener() {
            @Override public void onStatus(String message) {
                updateStatus(message);
            }
            @Override public void onDevices(List<SpotLedBleClient.FoundDevice> devices) { }
        });
        if (!ble.restoreSelectedDevice(deviceName, deviceAddress)) {
            stopWithError("Could not restore the saved SpotLED. Re-select it in the app.");
            return;
        }
        if (mqttEnabled) connectMqtt(host, port);
        executeDisplay(this::showClockFrame);
        scheduleClockAndIndoor();
        if ("noaa".equals(weatherSource)) scheduleNoaaRefresh(0);
        updateStatus(mqttEnabled ? "Running; connecting to MQTT" : "Running NOAA clock/weather without MQTT");
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void connectMqtt(String host, int port) {
        mqttConnector.execute(() -> {
            MqttClient candidate = null;
            try {
                boolean tls = preferences.getBoolean(PREF_TLS, false);
                String uri = (tls ? "ssl://" : "tcp://") + host + ":" + port;
                String clientId = preferences.getString(PREF_MQTT_CLIENT_ID, "");
                if (clientId.isEmpty()) {
                    clientId = "spotled-companion-" + UUID.randomUUID().toString().substring(0, 8);
                    preferences.edit().putString(PREF_MQTT_CLIENT_ID, clientId).apply();
                }
                candidate = new MqttClient(uri, clientId, new MemoryPersistence());
                MqttClient client = candidate;
                mqtt = client;
                client.setCallback(new MqttCallbackExtended() {
                    @Override public void connectComplete(boolean reconnect, String serverURI) {
                        subscribeToTopics(client);
                        updateStatus(reconnect ? "Reconnected to MQTT" : "Connected to MQTT");
                    }
                    @Override public void connectionLost(Throwable cause) {
                        updateStatus("MQTT disconnected; reconnecting");
                    }
                    @Override public void messageArrived(String topic, MqttMessage message) {
                        if (timers != null && !stopping)
                            timers.execute(() -> handleMessage(topic, message.getPayload()));
                    }
                    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                });
                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(false);
                options.setKeepAliveInterval(30);
                options.setConnectionTimeout(10);
                String username = preferences.getString(PREF_USERNAME, "");
                if (!username.isEmpty()) options.setUserName(username);
                String password = preferences.getString(PREF_PASSWORD, "");
                if (!password.isEmpty()) options.setPassword(password.toCharArray());
                client.connect(options);
            } catch (Exception exception) {
                if (candidate != null) {
                    try { candidate.close(); } catch (MqttException ignored) { }
                    if (mqtt == candidate) mqtt = null;
                }
                String reason = exception instanceof MqttException
                        ? "reason " + ((MqttException) exception).getReasonCode()
                        : exception.getClass().getSimpleName();
                Log.w(TAG, "MQTT connection failed (" + reason + "): " + exception.getMessage(), exception);
                updateStatus("MQTT connection failed (" + reason + "); retrying");
                if (timers != null && !stopping)
                    timers.schedule(() -> connectMqtt(host, port), 10, TimeUnit.SECONDS);
            }
        });
    }

    private void subscribeToTopics(MqttClient client) {
        try {
            List<String> topics = new ArrayList<>();
            if (!notifyTopic.isEmpty()) {
                topics.add(notifyTopic);
                topics.add(notifyTopic + "/dismiss");
                topics.add(notifyTopic + "/dismiss/+");
            }
            if ("homeassistant".equals(weatherSource)) {
                topics.add(weatherTopic);
                if (!speedTopic.isEmpty()) topics.add(speedTopic);
            }
            int[] qos = new int[topics.size()];
            client.subscribe(topics.toArray(new String[0]), qos);
        } catch (MqttException exception) {
            updateStatus("Connected, but MQTT topic subscription failed");
        }
    }

    private void handleMessage(String topic, byte[] payload) {
        if ("homeassistant".equals(weatherSource) && topic.equals(weatherTopic)) {
            MqttPayloads.Weather weather = MqttPayloads.parseHomeAssistantWeather(payload);
            if (weather != null) {
                currentWeather = weather;
                weatherUpdatedAtMillis = System.currentTimeMillis();
                enqueueClockRefresh();
            }
            return;
        }
        if ("homeassistant".equals(weatherSource) && !speedTopic.isEmpty() && topic.equals(speedTopic)) {
            Float speed = MqttPayloads.parseSpeed(payload);
            if (speed != null) {
                speedMbps = speed;
                enqueueClockRefresh();
            }
            return;
        }
        if (!notifyTopic.isEmpty() && MqttPayloads.isDismissTopic(topic, notifyTopic)) {
            executeDisplay(this::dismissAlert);
            return;
        }
        if (!notifyTopic.isEmpty() && topic.equals(notifyTopic)) {
            MqttPayloads.Alert alert = MqttPayloads.parseAlert(payload);
            if (alert != null) executeDisplay(() -> enqueueAlert(alert));
        }
    }

    private void scheduleClockAndIndoor() {
        refreshIndoorTemperature();
        scheduleNextClockMinute();
    }

    private void scheduleNextClockMinute() {
        if (timers == null || stopping) return;
        long delay = 60_000 - (System.currentTimeMillis() % 60_000);
        timers.schedule(() -> {
            refreshIndoorTemperature();
            enqueueClockRefresh();
            scheduleNextClockMinute();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void scheduleNoaaRefresh(long delayMs) {
        if (timers == null || stopping) return;
        timers.schedule(() -> {
            refreshNoaaWeather();
            scheduleNoaaRefresh(millisecondsUntilNextQuarterHour());
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private long millisecondsUntilNextQuarterHour() {
        ZonedDateTime now = now();
        int nextQuarter = ((now.getMinute() / 15) + 1) * 15;
        ZonedDateTime next = now.withSecond(0).withNano(0);
        if (nextQuarter == 60) next = next.plusHours(1).withMinute(0);
        else next = next.withMinute(nextQuarter);
        return Math.max(1000, java.time.Duration.between(now, next).toMillis());
    }

    private ZonedDateTime now() {
        ZoneId zone = timezoneName.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(timezoneName);
        return ZonedDateTime.now(zone);
    }

    private void refreshIndoorTemperature() {
        if (indoorUrl.isEmpty() || network == null || stopping) return;
        network.execute(() -> {
            byte[] response = httpGet(indoorUrl, "application/json", 5_000,
                    "TC001 temperature unavailable; retaining last value");
            if (response == null) return;
            float temperature = MqttPayloads.parseIndoorTemperature(response, "C".equals(temperatureUnit));
            if (Float.isFinite(temperature)) {
                indoorTemperature = temperature;
                enqueueClockRefresh();
            }
        });
    }

    private void refreshNoaaWeather() {
        if (station.isEmpty() || network == null || stopping) return;
        network.execute(() -> {
            byte[] response = httpGet("https://api.weather.gov/stations/" + station
                    + "/observations/latest", "application/geo+json", 10_000,
                    "NOAA weather unavailable; retaining last value");
            if (response == null) return;
            MqttPayloads.Weather parsed = MqttPayloads.parseNoaaObservation(response);
            if (parsed != null) {
                currentWeather = parsed;
                weatherUpdatedAtMillis = System.currentTimeMillis();
                enqueueClockRefresh();
            }
        });
    }

    private byte[] httpGet(String address, String accept, int timeoutMs, String failureStatus) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("User-Agent",
                    "SpotLED-Companion/0.17.2 (https://github.com/plughie/SpotLED-Suite)");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int total = 0, count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 64_000) return null;
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } catch (Exception exception) {
            String reason = exception.getClass().getSimpleName();
            Log.w(TAG, failureStatus + " (" + reason + "): " + exception.getMessage(), exception);
            updateStatus(failureStatus + " (" + reason + ")");
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void enqueueClockRefresh() {
        if (displayQueue == null || stopping) return;
        clockRefreshRequested.set(true);
        if (!clockRefreshTaskScheduled.compareAndSet(false, true)) return;
        if (!executeDisplay(() -> {
            try {
                do {
                    clockRefreshRequested.set(false);
                    if (activeAlert == null) showClockFrame();
                } while (clockRefreshRequested.get() && !stopping);
            } finally {
                clockRefreshTaskScheduled.set(false);
                if (clockRefreshRequested.get() && !stopping) enqueueClockRefresh();
            }
        })) clockRefreshTaskScheduled.set(false);
    }

    private void enqueueAlert(MqttPayloads.Alert alert) {
        if (alert.stack && activeAlert != null) {
            if (pendingAlerts.size() >= MAX_PENDING_ALERTS) {
                pendingAlerts.removeFirst();
                droppedAlerts++;
                Log.w(TAG, "Pending alert queue full; dropping oldest alert (total=" + droppedAlerts + ")");
            }
            pendingAlerts.addLast(alert);
            pendingAlertCount = pendingAlerts.size();
            return;
        }
        if (!alert.stack) {
            pendingAlerts.clear();
            pendingAlertCount = 0;
            activeAlert = null;
            alertGeneration++;
        }
        showAlert(alert);
    }

    private void showAlert(MqttPayloads.Alert alert) {
        activeAlert = alert;
        long generation = ++alertGeneration;
        if (ble != null && ble.hasSelectedDevice()) {
            ble.sendNativeTextSolidColor(alert.text, alert.color, 6,
                    preferences.getInt(PREF_TEXT_TIME_MS, 200));
            if (!waitForTransfer()) {
                Log.w(TAG, "Alert transfer failed");
                updateStatus("MQTT alert transfer failed");
            } else {
                Log.i(TAG, "Alert transfer acknowledged");
                updateStatus("MQTT alert acknowledged; check the display");
            }
        }
        if (!alert.hold && timers != null)
            timers.schedule(() -> executeDisplay(() -> finishAlert(generation)),
                    alert.durationMs, TimeUnit.MILLISECONDS);
    }

    private void dismissAlert() {
        if (activeAlert == null) return;
        activeAlert = null;
        alertGeneration++;
        showNextOrClock();
    }

    private void finishAlert(long generation) {
        if (activeAlert == null || generation != alertGeneration) return;
        activeAlert = null;
        showNextOrClock();
    }

    private void showNextOrClock() {
        if (!pendingAlerts.isEmpty()) {
            MqttPayloads.Alert next = pendingAlerts.removeFirst();
            pendingAlertCount = pendingAlerts.size();
            showAlert(next);
        }
        else showClockFrame();
    }

    private void showClockFrame() {
        if (ble == null || !ble.hasSelectedDevice()) return;
        boolean celsius = "C".equals(temperatureUnit);
        ZonedDateTime timestamp = now();
        byte[] frame = ClockTempRenderer.render(timestamp, indoorTemperature, currentWeather,
                "homeassistant".equals(weatherSource) ? speedMbps : null, celsius);
        byte[][] frames = WeatherIconAnimation.createFrames(getApplicationContext(), timestamp,
                currentWeather == null ? null : currentWeather.condition, frame);
        if (frames.length > 1) {
            // The display treats a multi-frame animation as a brief playback and then
            // restores the previously stored frame. Prime that frame with the same
            // moon/weather composition so the icons remain visible after playback.
            ble.sendRgbFrame(frames[0], ClockTempRenderer.WIDTH, ClockTempRenderer.HEIGHT,
                    0, 1000);
            if (!waitForTransfer()) {
                updateStatus("Clock/weather base frame transfer failed");
                return;
            }
            ble.sendRgbAnimation(frames, ClockTempRenderer.WIDTH, ClockTempRenderer.HEIGHT, 500);
        } else {
            ble.sendRgbFrame(frames[0], ClockTempRenderer.WIDTH, ClockTempRenderer.HEIGHT,
                    0, 1000);
        }
        if (!waitForTransfer()) updateStatus("Clock/weather transfer failed");
        else updateStatus("Clock/weather update acknowledged; check the display");
    }

    private boolean waitForTransfer() {
        long deadline = System.currentTimeMillis() + 45_000;
        while (ble != null && ble.isTransferInProgress() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return false; }
        }
        return ble != null && !ble.isTransferInProgress() && ble.wasLastTransferAcknowledged();
    }

    private boolean executeDisplay(Runnable task) {
        ThreadPoolExecutor queue = displayQueue;
        if (queue == null || stopping) return false;
        try {
            queue.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            droppedDisplayTasks++;
            Log.w(TAG, "Display backlog full; dropped event (" + queueMetrics() + ")");
            updateStatus("Display queue busy; one event was dropped");
            return false;
        }
    }

    private String queueMetrics() {
        int backlog = displayQueue == null ? 0 : displayQueue.getQueue().size();
        String freshness = weatherUpdatedAtMillis == 0 ? "unknown"
                : Math.max(0, (System.currentTimeMillis() - weatherUpdatedAtMillis) / 60_000) + "m ago";
        return "alerts " + pendingAlertCount + "/" + MAX_PENDING_ALERTS
                + ", backlog " + backlog + "/" + MAX_DISPLAY_BACKLOG
                + ", drops " + (droppedAlerts + droppedDisplayTasks)
                + ", weather " + freshness;
    }

    private void updateStatus(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void stopWithError(String message) {
        preferences.edit().putBoolean(PREF_ENABLED, false).apply();
        updateStatus(message);
        stopSelf();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "SpotLED time, weather and MQTT", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Status and connection state for the user-started SpotLED service.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String content) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, MqttStatusService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("SPOTLED Companion status service")
                .setContentText(content)
                .setSubText(queueMetrics())
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                        "Stop", stopIntent).build());
        if (Build.VERSION.SDK_INT >= 31)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    @Override public void onDestroy() {
        stopping = true;
        if (phoneNotificationReceiverRegistered) {
            unregisterReceiver(phoneNotificationReceiver);
            phoneNotificationReceiverRegistered = false;
        }
        if (preferences != null) preferences.edit().putBoolean(PREF_ENABLED, false).apply();
        if (timers != null) timers.shutdownNow();
        if (network != null) network.shutdownNow();
        if (mqttConnector != null) mqttConnector.shutdownNow();
        if (displayQueue != null) displayQueue.shutdownNow();
        if (mqtt != null) {
            try { if (mqtt.isConnected()) mqtt.disconnectForcibly(0, 1000); }
            catch (MqttException ignored) { }
            try { mqtt.close(); } catch (MqttException ignored) { }
        }
        if (ble != null) ble.close();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
