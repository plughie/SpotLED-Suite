package com.spotled.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Android app for previewing and sending text, still images, and sampled GIF animations. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 41;
    private static final int REQUEST_IMAGE = 42;
    private static final int REQUEST_STATUS_PERMISSIONS = 43;
    private static final String PREFS_NAME = "spotled_settings";
    private static final String PREF_THEME = "theme";
    private static final String PREF_RASTER_TEXT = "raster_text";
    private static final String PREF_IMAGE_ROTATION = "image_rotation";
    private static final String PREF_IMAGE_FIT = "image_fit";
    private static final String PREF_LAST_DEVICE_NAME = "last_device_name";
    private static final String PREF_LAST_DEVICE_ADDRESS = "last_device_address";
    private static final String PREF_TEXT_TIME_MS = "text_time_ms";
    private static final String PREF_NATIVE_TEXT_TIME_MS_LEGACY = "native_text_time_ms";
    private TextView statusView;
    private EditText textInput;
    private Spinner rotationSpinner;
    private Spinner fitSpinner;
    private Spinner textEffectSpinner;
    private Spinner textColorSpinner;
    private ImageView preview;
    private SpotLedBleClient ble;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private Bitmap[] animatedPreviewFrames;
    private int animatedPreviewIntervalMs;
    private int animatedPreviewFrameIndex;
    private boolean activityResumed;
    private boolean previewAnimationScheduled;
    private TextView notificationAccessStatus;
    private Runnable sendAfterDeviceSelection;
    private final Runnable previewAnimationTick = new Runnable() {
        @Override public void run() {
            previewAnimationScheduled = false;
            if (!activityResumed || preview == null || animatedPreviewFrames == null
                    || animatedPreviewFrames.length < 2) return;
            animatedPreviewFrameIndex = (animatedPreviewFrameIndex + 1)
                    % animatedPreviewFrames.length;
            preview.setImageBitmap(animatedPreviewFrames[animatedPreviewFrameIndex]);
            startAnimatedPreview();
        }
    };

    @Override protected void onCreate(Bundle state) {
        applyStoredTheme();
        super.onCreate(state);
        ble = new SpotLedBleClient(this, new SpotLedBleClient.Listener() {
            @Override public void onStatus(String message) {
                if (statusView != null) statusView.setText(message);
                if (message.startsWith("Display not found.")
                        || message.contains("scan failed")
                        || message.contains("permission is missing")
                        || message.startsWith("Turn on Bluetooth")
                        || message.startsWith("Bluetooth LE scanning is unavailable"))
                    sendAfterDeviceSelection = null;
            }

            @Override public void onDevices(List<SpotLedBleClient.FoundDevice> devices) {
                showDevicePicker(devices);
            }
        });
        buildUi();
        restoreRememberedDevice();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(24));
        scroll.addView(page);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("SPOTLED Companion");
        title.setTextSize(22);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton settings = new ImageButton(this);
        settings.setImageResource(R.drawable.ic_settings_gear);
        settings.setContentDescription("Settings");
        TypedValue selectableBackground = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                selectableBackground, true) && selectableBackground.resourceId != 0)
            settings.setBackgroundResource(selectableBackground.resourceId);
        else settings.setBackgroundColor(Color.TRANSPARENT);
        settings.setOnClickListener(view -> showSettingsDialog());
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));

        statusView = new TextView(this);
        statusView.setText("Find and select a SpotLED_* display to begin.");
        statusView.setTextSize(15);
        statusView.setPadding(0, dp(10), 0, dp(12));
        page.addView(statusView);

        Button connect = new Button(this);
        connect.setText("Find and select display");
        connect.setOnClickListener(view -> {
            if (isStatusServiceEnabled()) {
                statusView.setText("Stop the time/weather/MQTT service before using the display manually.");
                return;
            }
            requestBluetoothPermissionsThenScan();
        });
        page.addView(connect);

        TextView label = new TextView(this);
        label.setText("Text");
        label.setTextSize(16);
        label.setPadding(0, dp(18), 0, dp(4));
        page.addView(label);

        textInput = new EditText(this);
        textInput.setSingleLine(true);
        textInput.setHint("Enter a short message");
        page.addView(textInput);

        rotationSpinner = new Spinner(this);
        rotationSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[] {"0°", "90° clockwise", "180°", "270° clockwise"}));
        restoreAndRememberSpinner(rotationSpinner, PREF_IMAGE_ROTATION, 4);
        page.addView(rotationSpinner);

        fitSpinner = new Spinner(this);
        fitSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[] {"Contain (preserve all, may add padding)",
                        "Cover (fill, crop edges)", "Stretch (fill, distort)"}));
        restoreAndRememberSpinner(fitSpinner, PREF_IMAGE_FIT, 3);
        page.addView(fitSpinner);

        TextView effectLabel = new TextView(this);
        effectLabel.setText("Text effect");
        effectLabel.setTextSize(16);
        effectLabel.setPadding(0, dp(8), 0, dp(2));
        page.addView(effectLabel);
        textEffectSpinner = new Spinner(this);
        textEffectSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[] {"Slide", "Scroll up", "Scroll down", "Scroll left",
                        "Scroll right", "Snow", "Scroll", "Laser"}));
        page.addView(textEffectSpinner);

        TextView colorLabel = new TextView(this);
        colorLabel.setText("Text color");
        colorLabel.setTextSize(16);
        colorLabel.setPadding(0, dp(8), 0, dp(2));
        page.addView(colorLabel);
        textColorSpinner = new Spinner(this);
        textColorSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, TextPalette.NAMES));
        page.addView(textColorSpinner);

        Button sendText = new Button(this);
        sendText.setText("Preview and send text");
        sendText.setOnClickListener(view -> sendTextFrame());
        page.addView(sendText);

        Button chooseImage = new Button(this);
        chooseImage.setText("Choose and send image");
        chooseImage.setOnClickListener(view -> openImagePicker());
        page.addView(chooseImage);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(Color.BLACK);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160));
        previewParams.topMargin = dp(16);
        page.addView(preview, previewParams);

        TextView note = new TextView(this);
        note.setText("The preview is 64×16. GIF previews animate the same two sampled frames sent to the display at a safe pace, limited by the display's packet size. Transfers replace the display. BLE connects only during a send; an acknowledgment confirms receipt, not rendering.");
        note.setTextSize(13);
        note.setPadding(0, dp(12), 0, 0);
        page.addView(note);

        setContentView(scroll);
    }

    private void applyStoredTheme() {
        String theme = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_THEME, "system");
        if ("light".equals(theme)) setTheme(R.style.AppThemeLight);
        else if ("dark".equals(theme)) setTheme(R.style.AppThemeDark);
        else setTheme(R.style.AppThemeSystem);
    }

    private void showSettingsDialog() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView themeLabel = new TextView(this);
        themeLabel.setText("Appearance");
        themeLabel.setTextSize(16);
        content.addView(themeLabel);
        Spinner themeSpinner = new Spinner(this);
        themeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[] {"System", "Light", "Dark"}));
        String storedTheme = preferences.getString(PREF_THEME, "system");
        themeSpinner.setSelection("light".equals(storedTheme) ? 1 : "dark".equals(storedTheme) ? 2 : 0);
        content.addView(themeSpinner);

        TextView speedLabel = new TextView(this);
        speedLabel.setText("Text speed");
        speedLabel.setTextSize(16);
        speedLabel.setPadding(0, dp(16), 0, 0);
        content.addView(speedLabel);
        Spinner speedSpinner = new Spinner(this);
        speedSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                TextSpeedSettings.labels()));
        speedSpinner.setSelection(TextSpeedSettings.positionForMilliseconds(textTimeMs(preferences)));
        content.addView(speedSpinner);

        Switch rasterSwitch = new Switch(this);
        rasterSwitch.setText("Use raster text");
        rasterSwitch.setTextSize(16);
        rasterSwitch.setPadding(0, dp(16), 0, dp(4));
        rasterSwitch.setChecked(preferences.getBoolean(PREF_RASTER_TEXT, false));
        content.addView(rasterSwitch);

        TextView rasterHelp = new TextView(this);
        rasterHelp.setText("Off uses the display's native font for normal text. On sends text as pixels; rotation also requires raster text.");
        rasterHelp.setTextSize(13);
        content.addView(rasterHelp);

        Button statusService = new Button(this);
        statusService.setText("Time, weather & MQTT service…");
        content.addView(statusService);

        Button about = new Button(this);
        about.setText("About SpotLED Companion");
        content.addView(about);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(content)
                .setPositiveButton("Done", null)
                .setNegativeButton("Cancel", (ignored, which) -> ignored.dismiss())
                .create();
        statusService.setOnClickListener(view -> {
            dialog.dismiss();
            showStatusServiceDialog();
        });
        about.setOnClickListener(view -> {
            dialog.dismiss();
            showAboutDialog();
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String selectedTheme = themeSpinner.getSelectedItemPosition() == 1 ? "light"
                            : themeSpinner.getSelectedItemPosition() == 2 ? "dark" : "system";
                    boolean themeChanged = !selectedTheme.equals(storedTheme);
                    int textTimeMs = TextSpeedSettings.millisecondsForPosition(
                            speedSpinner.getSelectedItemPosition());
                    preferences.edit()
                            .putString(PREF_THEME, selectedTheme)
                            .putBoolean(PREF_RASTER_TEXT, rasterSwitch.isChecked())
                            .putInt(PREF_TEXT_TIME_MS, textTimeMs)
                            .apply();
                    dialog.dismiss();
                    if (themeChanged) recreate();
                }));
        dialog.show();
    }

    private void showStatusServiceDialog() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        ScrollView scroller = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        scroller.addView(form);
        scroller.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(330)));

        Switch enabled = new Switch(this);
        enabled.setText("Run Android time/weather/MQTT service");
        enabled.setChecked(preferences.getBoolean(MqttStatusService.PREF_ENABLED, false));
        form.addView(enabled);
        TextView explanation = new TextView(this);
        explanation.setText("Android-only foreground service. It uses the selected SpotLED while active; manual sends are paused to prevent BLE contention. MQTT credentials are kept in app-private settings; use TLS on untrusted networks. This feature is intentionally outside the planned iOS app contract.");
        explanation.setTextSize(13);
        explanation.setPadding(0, dp(4), 0, dp(12));
        form.addView(explanation);

        EditText host = serviceField(form, "MQTT broker", "Host name or IP (optional for NOAA)",
                preferences.getString(MqttStatusService.PREF_BROKER, ""),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI, false);
        EditText port = serviceField(form, "MQTT port", "1883 by default",
                preferences.getString(MqttStatusService.PREF_PORT, "1883"),
                android.text.InputType.TYPE_CLASS_NUMBER, false);
        EditText username = serviceField(form, "MQTT username (optional)", "",
                preferences.getString(MqttStatusService.PREF_USERNAME, ""),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, false);
        EditText password = serviceField(form, "MQTT password (optional)", "",
                preferences.getString(MqttStatusService.PREF_PASSWORD, ""),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
        EditText notifyTopic = serviceField(form, "AWTRIX notification topic (optional)",
                "Example: awtrix/cmd/notify",
                preferences.getString(MqttStatusService.PREF_NOTIFY_TOPIC, ""),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI, true);

        Switch forwardPhoneNotifications = new Switch(this);
        forwardPhoneNotifications.setText("Forward selected Android notifications");
        forwardPhoneNotifications.setChecked(preferences.getBoolean(
                PhoneNotificationListenerService.PREF_FORWARD_ENABLED, false));
        form.addView(forwardPhoneNotifications);
        TextView forwardingExplanation = new TextView(this);
        forwardingExplanation.setText("Selected apps' notification text will scroll across the SpotLED while this service is running. Choose apps, then grant Notification Access in Android Settings.");
        forwardingExplanation.setTextSize(13);
        forwardingExplanation.setPadding(0, dp(2), 0, dp(6));
        form.addView(forwardingExplanation);
        Set<String> pendingForwardPackages = new HashSet<>(preferences.getStringSet(
                PhoneNotificationListenerService.PREF_FORWARD_PACKAGES, Collections.emptySet()));
        TextView selectedAppsSummary = new TextView(this);
        updateSelectedAppsSummary(selectedAppsSummary, pendingForwardPackages.size());
        form.addView(selectedAppsSummary);
        Button chooseApps = new Button(this);
        chooseApps.setText("Choose apps to forward");
        form.addView(chooseApps);
        chooseApps.setOnClickListener(view -> showPhoneNotificationAppPicker(
                pendingForwardPackages, selectedAppsSummary));
        notificationAccessStatus = new TextView(this);
        updateNotificationAccessStatus();
        form.addView(notificationAccessStatus);
        Button notificationAccess = new Button(this);
        notificationAccess.setText("Grant notification access");
        notificationAccess.setOnClickListener(view -> openNotificationAccessSettings());
        form.addView(notificationAccess);

        Switch tls = new Switch(this);
        tls.setText("Use MQTT over TLS");
        tls.setChecked(preferences.getBoolean(MqttStatusService.PREF_TLS, false));
        form.addView(tls);

        addServiceLabel(form, "Weather source");
        Spinner source = new Spinner(this);
        source.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[] {"NOAA / NWS", "Home Assistant"}));
        source.setSelection("homeassistant".equals(preferences.getString(
                MqttStatusService.PREF_WEATHER_SOURCE, "noaa")) ? 1 : 0);
        form.addView(source);
        EditText station = serviceField(form, "NOAA station ID", "Example: KSQL",
                preferences.getString(MqttStatusService.PREF_NOAA_STATION, ""),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, false);
        EditText weatherTopic = serviceField(form, "Home Assistant weather topic",
                "Retained JSON topic with temp and cond",
                preferences.getString(MqttStatusService.PREF_HA_WEATHER_TOPIC, ""),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI, true);
        EditText speedTopic = serviceField(form, "Home Assistant speed topic (optional)",
                "Optional Mbps topic",
                preferences.getString(MqttStatusService.PREF_SPEED_TOPIC, ""),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI, true);
        String savedIndoorUrl = preferences.getString(MqttStatusService.PREF_INDOOR_URL, "");
        try { savedIndoorUrl = MqttStatusService.normalizeIndoorUrl(savedIndoorUrl); }
        catch (java.net.MalformedURLException ignored) { }
        EditText indoorUrl = serviceField(form, "TC001 address or URL (optional)",
                "IP only is OK; /api/v1/device is added automatically", savedIndoorUrl,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI, true);
        EditText timezone = serviceField(form, "Clock timezone", "Blank uses phone timezone",
                preferences.getString(MqttStatusService.PREF_TIMEZONE, ""),
                android.text.InputType.TYPE_CLASS_TEXT, false);
        addServiceLabel(form, "Temperature units");
        Spinner unit = new Spinner(this);
        unit.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[] {"Fahrenheit", "Celsius"}));
        unit.setSelection("C".equals(preferences.getString(MqttStatusService.PREF_WEATHER_UNIT, "F")) ? 1 : 0);
        form.addView(unit);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Time, weather & MQTT")
                .setView(scroller)
                .setPositiveButton("Apply", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Stop service", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                boolean run = enabled.isChecked();
                String selectedSource = source.getSelectedItemPosition() == 1 ? "homeassistant" : "noaa";
                boolean haSource = selectedSource.equals("homeassistant");
                boolean notificationsConfigured = !serviceText(notifyTopic).isEmpty();
                boolean mqttNeeded = haSource || notificationsConfigured;
                boolean forwardNotifications = forwardPhoneNotifications.isChecked();
                if (forwardNotifications && !run) {
                    Toast.makeText(this, "Enable the foreground service to forward phone notifications.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (forwardNotifications && pendingForwardPackages.isEmpty()) {
                    Toast.makeText(this, "Choose at least one app to forward.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (run && mqttNeeded && serviceText(host).isEmpty()) {
                    Toast.makeText(this, "Enter the MQTT broker for the selected MQTT features.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (run && selectedSource.equals("noaa") && serviceText(station).isEmpty()) {
                    Toast.makeText(this, "Enter a NOAA weather station ID.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (run && haSource
                        && serviceText(weatherTopic).isEmpty()) {
                    Toast.makeText(this, "Enter the Home Assistant weather topic.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (run && mqttNeeded) {
                    try {
                        int mqttPort = Integer.parseInt(serviceText(port));
                        if (mqttPort < 1 || mqttPort > 65535) throw new NumberFormatException();
                    } catch (NumberFormatException exception) {
                        Toast.makeText(this, "Enter an MQTT port between 1 and 65535.", Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                String normalizedIndoorUrl;
                try { normalizedIndoorUrl = MqttStatusService.normalizeIndoorUrl(serviceText(indoorUrl)); }
                catch (java.net.MalformedURLException exception) {
                    Toast.makeText(this, "Enter a TC001 IP address or an HTTP(S) URL.", Toast.LENGTH_LONG).show();
                    return;
                }
                preferences.edit()
                        .putString(MqttStatusService.PREF_BROKER, serviceText(host))
                        .putString(MqttStatusService.PREF_PORT, serviceText(port))
                        .putString(MqttStatusService.PREF_USERNAME, username.getText().toString())
                        .putString(MqttStatusService.PREF_PASSWORD, password.getText().toString())
                        .putString(MqttStatusService.PREF_NOTIFY_TOPIC, serviceText(notifyTopic))
                        .putBoolean(MqttStatusService.PREF_TLS, tls.isChecked())
                        .putString(MqttStatusService.PREF_WEATHER_SOURCE, selectedSource)
                        .putString(MqttStatusService.PREF_NOAA_STATION, serviceText(station).toUpperCase(java.util.Locale.ROOT))
                        .putString(MqttStatusService.PREF_HA_WEATHER_TOPIC, serviceText(weatherTopic))
                        .putString(MqttStatusService.PREF_SPEED_TOPIC, serviceText(speedTopic))
                        .putString(MqttStatusService.PREF_INDOOR_URL, normalizedIndoorUrl)
                        .putString(MqttStatusService.PREF_TIMEZONE, serviceText(timezone))
                        .putString(MqttStatusService.PREF_WEATHER_UNIT, unit.getSelectedItemPosition() == 1 ? "C" : "F")
                        .putBoolean(PhoneNotificationListenerService.PREF_FORWARD_ENABLED, forwardNotifications)
                        .putStringSet(PhoneNotificationListenerService.PREF_FORWARD_PACKAGES,
                                new HashSet<>(pendingForwardPackages))
                        .putBoolean(MqttStatusService.PREF_ENABLED, run)
                        .apply();
                dialog.dismiss();
                if (run) startStatusServiceWithPermissions();
                else stopStatusService();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                enabled.setChecked(false);
                preferences.edit().putBoolean(MqttStatusService.PREF_ENABLED, false).apply();
                dialog.dismiss();
                stopStatusService();
            });
        });
        dialog.show();
        android.view.ViewGroup.LayoutParams scrollerParams = scroller.getLayoutParams();
        if (scrollerParams != null) {
            scrollerParams.height = dp(420);
            scroller.setLayoutParams(scrollerParams);
        }
        if (dialog.getWindow() != null) {
            int dialogHeight = getResources().getDisplayMetrics().heightPixels - dp(100);
            dialog.getWindow().setLayout(dialog.getWindow().getAttributes().width, dialogHeight);
        }
    }

    private static final class ForwardableApp {
        final String packageName;
        final String label;
        ForwardableApp(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private void showPhoneNotificationAppPicker(Set<String> selectedPackages, TextView summary) {
        android.content.pm.PackageManager packageManager = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launchers = packageManager.queryIntentActivities(launcherIntent, 0);
        ArrayList<ForwardableApp> apps = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo info : launchers) {
            if (info.activityInfo == null || info.activityInfo.packageName.equals(getPackageName())
                    || !seenPackages.add(info.activityInfo.packageName)) continue;
            String label = String.valueOf(info.loadLabel(packageManager));
            apps.add(new ForwardableApp(info.activityInfo.packageName, label));
        }
        Collections.sort(apps, Comparator.comparing((ForwardableApp app) -> app.label,
                String.CASE_INSENSITIVE_ORDER).thenComparing(app -> app.packageName));
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps were found.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            checked[i] = selectedPackages.contains(apps.get(i).packageName);
        }
        new AlertDialog.Builder(this)
                .setTitle("Forward notifications from")
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save selection", (dialog, which) -> {
                    selectedPackages.clear();
                    for (int i = 0; i < apps.size(); i++)
                        if (checked[i]) selectedPackages.add(apps.get(i).packageName);
                    updateSelectedAppsSummary(summary, selectedPackages.size());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateSelectedAppsSummary(TextView view, int count) {
        view.setText(count == 0 ? "No apps selected" : count + (count == 1 ? " app selected" : " apps selected"));
        view.setTextSize(14);
    }

    private void updateNotificationAccessStatus() {
        if (notificationAccessStatus == null) return;
        notificationAccessStatus.setText(PhoneNotificationListenerService.hasNotificationAccess(this)
                ? "Android Notification Access: enabled"
                : "Android Notification Access: not enabled");
        notificationAccessStatus.setTextSize(14);
    }

    private void openNotificationAccessSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException exception) {
            Toast.makeText(this, "Could not open Android Notification Access settings.", Toast.LENGTH_LONG).show();
        }
    }

    private EditText serviceField(LinearLayout form, String labelText, String hint, String value,
                                  int inputType, boolean multiline) {
        addServiceLabel(form, labelText);
        EditText field = new EditText(this);
        field.setSingleLine(!multiline);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(16);
        field.setInputType(multiline
                ? inputType | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : inputType);
        if (multiline) {
            field.setMinLines(2);
            field.setMaxLines(3);
            field.setHorizontallyScrolling(false);
            field.setGravity(Gravity.TOP | Gravity.START);
        }
        form.addView(field);
        return field;
    }

    private static String serviceText(EditText field) {
        return field.getText().toString().replace("\n", "").replace("\r", "").trim();
    }

    private void addServiceLabel(LinearLayout form, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(15);
        label.setPadding(0, dp(10), 0, dp(2));
        form.addView(label);
    }

    private boolean isStatusServiceEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(MqttStatusService.PREF_ENABLED, false);
    }

    private void startStatusServiceWithPermissions() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            preferences.edit().putBoolean(MqttStatusService.PREF_ENABLED, false).apply();
            new AlertDialog.Builder(this).setTitle("Allow Network access")
                    .setMessage("Android is blocking Network access for SPOTLED Companion. NOAA weather, TC001 temperature, and MQTT all need it.")
                    .setPositiveButton("Open app settings", (dialog, which) -> {
                        Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null));
                        startActivity(settings);
                    })
                    .setNegativeButton("Not now", null)
                    .show();
            return;
        }
        if (preferences.getString(PREF_LAST_DEVICE_ADDRESS, "").isEmpty()) {
            preferences.edit().putBoolean(MqttStatusService.PREF_ENABLED, false).apply();
            new AlertDialog.Builder(this).setTitle("Select a display first")
                    .setMessage("Use Find and select display, then enable the status service.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        List<String> needed = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQUEST_STATUS_PERMISSIONS);
            return;
        }
        Intent start = new Intent(this, MqttStatusService.class).setAction(MqttStatusService.ACTION_START);
        try {
            startForegroundService(start);
            preferences.edit().putBoolean(MqttStatusService.PREF_ENABLED, true).apply();
            Toast.makeText(this, "Time/weather/MQTT service started.", Toast.LENGTH_SHORT).show();
            if (preferences.getBoolean(PhoneNotificationListenerService.PREF_FORWARD_ENABLED, false)
                    && !PhoneNotificationListenerService.hasNotificationAccess(this))
                openNotificationAccessSettings();
        } catch (RuntimeException exception) {
            preferences.edit().putBoolean(MqttStatusService.PREF_ENABLED, false).apply();
            Toast.makeText(this, "Could not start the status service: " + exception.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopStatusService() {
        stopService(new Intent(this, MqttStatusService.class));
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(MqttStatusService.PREF_ENABLED, false).apply();
    }

    private static int textTimeMs(SharedPreferences preferences) {
        return preferences.getInt(PREF_TEXT_TIME_MS,
                preferences.getInt(PREF_NATIVE_TEXT_TIME_MS_LEGACY, 200));
    }

    private void restoreAndRememberSpinner(Spinner spinner, String preferenceKey, int optionCount) {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int selection = preferences.getInt(preferenceKey, 0);
        if (selection < 0 || selection >= optionCount) selection = 0;
        spinner.setSelection(selection);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                    int position, long id) {
                preferences.edit().putInt(preferenceKey, position).apply();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("About SpotLED Companion")
                .setMessage("SpotLED Suite is a Bug-Mag.net project by DuvTheDove, "
                        + "with contributions from clavo and OpenAI Codex. "
                        + "Weather icons by Hank the Tank (Wetter.ax), used with permission.")
                .setPositiveButton("Close", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void requestBluetoothPermissionsThenScan() {
        String[] needed = Build.VERSION.SDK_INT >= 31
                ? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        for (String permission : needed) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(needed, REQUEST_PERMISSIONS);
                return;
            }
        }
        ble.scanForDevices();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_STATUS_PERMISSIONS) {
            boolean granted = results.length > 0;
            for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) startStatusServiceWithPermissions();
            else {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(MqttStatusService.PREF_ENABLED, false).apply();
                Toast.makeText(this, "Bluetooth and notification permissions are needed for the service.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean granted = results.length > 0;
            for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) ble.scanForDevices();
            else {
                sendAfterDeviceSelection = null;
                statusView.setText("Bluetooth permission is required to find the display.");
            }
        }
    }

    private void showDevicePicker(List<SpotLedBleClient.FoundDevice> devices) {
        if (devices.isEmpty()) return;
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) labels[i] = devices.get(i).label();
        new AlertDialog.Builder(this)
                .setTitle("Choose a SpotLED display")
                .setItems(labels, (dialog, which) -> {
                    SpotLedBleClient.FoundDevice selected = devices.get(which);
                    ble.selectDevice(selected);
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(PREF_LAST_DEVICE_NAME, selected.name)
                            .putString(PREF_LAST_DEVICE_ADDRESS, selected.address)
                            .apply();
                    Runnable pendingSend = sendAfterDeviceSelection;
                    sendAfterDeviceSelection = null;
                    if (pendingSend != null) pendingSend.run();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    sendAfterDeviceSelection = null;
                    dialog.dismiss();
                })
                .setOnCancelListener(dialog -> sendAfterDeviceSelection = null)
                .show();
    }

    private boolean restoreRememberedDevice() {
        if (ble.hasSelectedDevice()) return true;
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String name = preferences.getString(PREF_LAST_DEVICE_NAME, "");
        String address = preferences.getString(PREF_LAST_DEVICE_ADDRESS, "");
        return ble.restoreSelectedDevice(name, address);
    }

    private void sendOrPromptForDisplay(Runnable sendAction, String previewStatus) {
        if (ble.hasSelectedDevice() || restoreRememberedDevice()) {
            sendAction.run();
            return;
        }
        statusView.setText(previewStatus);
        new AlertDialog.Builder(this)
                .setTitle("No display selected")
                .setMessage("Find and select a SpotLED now? Your preview will remain on screen, and the content will send after you choose a display.")
                .setPositiveButton("Find display", (dialog, which) -> {
                    sendAfterDeviceSelection = sendAction;
                    requestBluetoothPermissionsThenScan();
                })
                .setNegativeButton("Not now", (dialog, which) -> {
                    sendAfterDeviceSelection = null;
                    statusView.setText(previewStatus);
                })
                .setOnCancelListener(dialog -> sendAfterDeviceSelection = null)
                .show();
    }

    private void sendTextFrame() {
        if (requireStatusServiceStopped()) return;
        String message = textInput.getText().toString();
        if (message.trim().isEmpty()) {
            statusView.setText("Enter some text first.");
            return;
        }
        int effectMode = textEffectSpinner.getSelectedItemPosition();
        int textTimeMs = textTimeMs(getSharedPreferences(PREFS_NAME, MODE_PRIVATE));
        boolean rasterText = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_RASTER_TEXT, false);
        int rotationDegrees = rotationSpinner.getSelectedItemPosition() * 90;
        if (TextOutputMode.useNativeText(rasterText, rotationDegrees)) {
            clearAnimatedPreview();
            preview.setImageBitmap(renderNativeTextPreview(message, textColorSpinner.getSelectedItemPosition()));
            sendOrPromptForDisplay(
                    () -> ble.sendNativeText(message, textColorSpinner.getSelectedItemPosition(),
                            effectMode, textTimeMs),
                    "Native-font preview ready.");
            return;
        }
        RgbTransforms.Frame raster = CompactTextRaster.render(
                message, textColorSpinner.getSelectedItemPosition());
        sendRgbContent(raster.rgb, raster.width, raster.height, effectMode, textTimeMs);
    }

    private Bitmap renderNativeTextPreview(String text, int paletteIndex) {
        int[] pixels = new int[SpotLedProtocol.SCREEN_WIDTH * SpotLedProtocol.SCREEN_HEIGHT];
        int[] codePoints = text.codePoints().toArray();
        int xStart = 0;
        for (int index = 0; index < codePoints.length && xStart < SpotLedProtocol.SCREEN_WIDTH; index++) {
            byte[] glyph = SpotLedProtocol.nativeFontObject((char) codePoints[index]);
            int color = TextPalette.colorFor(paletteIndex, index, codePoints.length);
            for (int y = 0; y < 16; y++) {
                int rowOffset = 14 + y * 2;
                int bits = ((glyph[rowOffset] & 0xff) << 8) | (glyph[rowOffset + 1] & 0xff);
                for (int x = 0; x < 16 && xStart + x < SpotLedProtocol.SCREEN_WIDTH; x++) {
                    if ((bits & (0x8000 >>> x)) != 0)
                        pixels[y * SpotLedProtocol.SCREEN_WIDTH + xStart + x] = color;
                }
            }
            xStart += 16;
        }
        return Bitmap.createBitmap(pixels, SpotLedProtocol.SCREEN_WIDTH,
                SpotLedProtocol.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888);
    }

    private void openImagePicker() {
        if (requireStatusServiceStopped()) return;
        if (Build.VERSION.SDK_INT >= 33) {
            Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
            picker.setType("image/*");
            try {
                startActivityForResult(picker, REQUEST_IMAGE);
                return;
            } catch (android.content.ActivityNotFoundException ignored) {
                // Fall through to the document picker on devices without the system photo picker.
            }
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (InputStream gifInput = getContentResolver().openInputStream(uri)) {
            Movie movie = Movie.decodeStream(gifInput);
            if (movie != null && movie.duration() > 0) {
                sendGif(movie);
                return;
            }
        } catch (Exception exception) {
            statusView.setText("Could not decode selected image: " + exception.getMessage());
            return;
        }
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) throw new IllegalArgumentException("Unsupported or unreadable image.");
            sendBitmap(bitmap, 0);
        } catch (Exception exception) {
            statusView.setText("Could not open image: " + exception.getMessage());
        }
    }

    private void sendBitmap(Bitmap source, int effectMode) {
        if (requireStatusServiceStopped()) return;
        sendRgbContent(bitmapToRgb(source), source.getWidth(), source.getHeight(), effectMode);
    }

    private void sendGif(Movie movie) {
        if (requireStatusServiceStopped()) return;
        int sourceWidth = movie.width();
        int sourceHeight = movie.height();
        if (sourceWidth <= 0 || sourceHeight <= 0 || (long) sourceWidth * sourceHeight > 4_000_000L) {
            statusView.setText("GIF dimensions are invalid or too large to process safely.");
            return;
        }
        int duration = Math.max(1, movie.duration());
        int sampleTime = Math.min(duration - 1, duration / 2);
        Bitmap first = renderMovieFrame(movie, 0, sourceWidth, sourceHeight);
        Bitmap second = renderMovieFrame(movie, sampleTime, sourceWidth, sourceHeight);
        if (first == null || second == null) {
            statusView.setText("Could not decode GIF frames.");
            return;
        }

        RgbTransforms.Frame firstFrame = transformImage(first);
        RgbTransforms.Frame secondFrame = transformImage(second);
        int intervalMs = Math.max(250, Math.min(1000, duration / 2));
        setAnimatedPreviewFrames(
                bitmapFromRgb(firstFrame.rgb, firstFrame.width, firstFrame.height),
                bitmapFromRgb(secondFrame.rgb, secondFrame.width, secondFrame.height),
                intervalMs);
        // The tested receive buffer only fits two 64x16 RGB frames. Clamp timing to
        // 250 ms/frame so GIFs with very short source delays do not flash rapidly.
        sendOrPromptForDisplay(
                () -> ble.sendRgbAnimation(new byte[][] {firstFrame.rgb, secondFrame.rgb},
                        SpotLedProtocol.SCREEN_WIDTH, SpotLedProtocol.SCREEN_HEIGHT, intervalMs),
                "Animated GIF preview ready.");
    }

    private Bitmap renderMovieFrame(Movie movie, int timeMs, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        movie.setTime(timeMs);
        movie.draw(canvas, 0, 0);
        return bitmap;
    }

    private RgbTransforms.Frame transformImage(Bitmap source) {
        RgbTransforms.Frame oriented = RgbTransforms.rotate(
                bitmapToRgb(source), source.getWidth(), source.getHeight(),
                rotationSpinner.getSelectedItemPosition() * 90);
        String[] modes = {"contain", "cover", "stretch"};
        return RgbTransforms.fit(oriented.rgb, oriented.width, oriented.height,
                SpotLedProtocol.SCREEN_WIDTH, SpotLedProtocol.SCREEN_HEIGHT,
                modes[fitSpinner.getSelectedItemPosition()]);
    }

    private void sendRgbContent(byte[] sourceRgb, int sourceWidth, int sourceHeight, int effectMode) {
        sendRgbContent(sourceRgb, sourceWidth, sourceHeight, effectMode, 500);
    }

    private void sendRgbContent(byte[] sourceRgb, int sourceWidth, int sourceHeight,
                                int effectMode, int frameIntervalMs) {
        if (requireStatusServiceStopped()) return;
        int rotation = rotationSpinner.getSelectedItemPosition() * 90;
        RgbTransforms.Frame fitted = transformImage(
                bitmapFromRgb(sourceRgb, sourceWidth, sourceHeight));
        Bitmap previewBitmap = bitmapFromRgb(fitted.rgb, fitted.width, fitted.height);
        clearAnimatedPreview();
        preview.setImageBitmap(previewBitmap);
        sendOrPromptForDisplay(
                () -> ble.sendRgbFrame(fitted.rgb, fitted.width, fitted.height,
                        effectMode, frameIntervalMs),
                effectMode == 0 ? "Image preview ready." : "Text preview ready.");
    }

    private byte[] bitmapToRgb(Bitmap bitmap) {
        int[] argb = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(argb, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        byte[] rgb = new byte[argb.length * 3];
        for (int i = 0, offset = 0; i < argb.length; i++) {
            rgb[offset++] = (byte) Color.red(argb[i]);
            rgb[offset++] = (byte) Color.green(argb[i]);
            rgb[offset++] = (byte) Color.blue(argb[i]);
        }
        return rgb;
    }

    private Bitmap bitmapFromRgb(byte[] rgb, int width, int height) {
        int[] argb = new int[width * height];
        for (int i = 0, offset = 0; i < argb.length; i++) {
            argb[i] = Color.rgb(rgb[offset++] & 0xff, rgb[offset++] & 0xff, rgb[offset++] & 0xff);
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }

    private void setAnimatedPreviewFrames(Bitmap first, Bitmap second, int intervalMs) {
        clearAnimatedPreview();
        animatedPreviewFrames = new Bitmap[] {first, second};
        animatedPreviewIntervalMs = Math.max(250, Math.min(1000, intervalMs));
        animatedPreviewFrameIndex = 0;
        preview.setImageBitmap(first);
        startAnimatedPreview();
    }

    private void startAnimatedPreview() {
        if (!activityResumed || previewAnimationScheduled || animatedPreviewFrames == null
                || animatedPreviewFrames.length < 2) return;
        previewAnimationScheduled = true;
        previewHandler.postDelayed(previewAnimationTick, animatedPreviewIntervalMs);
    }

    private void stopAnimatedPreview() {
        previewHandler.removeCallbacks(previewAnimationTick);
        previewAnimationScheduled = false;
    }

    private void clearAnimatedPreview() {
        stopAnimatedPreview();
        animatedPreviewFrames = null;
        animatedPreviewFrameIndex = 0;
    }

    @Override protected void onResume() {
        super.onResume();
        updateNotificationAccessStatus();
        activityResumed = true;
        startAnimatedPreview();
    }

    @Override protected void onPause() {
        activityResumed = false;
        stopAnimatedPreview();
        super.onPause();
    }

    @Override protected void onDestroy() {
        clearAnimatedPreview();
        if (ble != null) ble.close();
        super.onDestroy();
    }

    private boolean requireStatusServiceStopped() {
        if (!isStatusServiceEnabled()) return false;
        if (statusView != null)
            statusView.setText("Stop the time/weather/MQTT service in Settings before sending manual content.");
        return true;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
