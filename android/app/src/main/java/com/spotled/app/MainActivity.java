package com.spotled.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import java.io.InputStream;
import java.util.List;

/** Android app for previewing and sending text, still images, and sampled GIF animations. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 41;
    private static final int REQUEST_IMAGE = 42;
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
        connect.setOnClickListener(view -> requestBluetoothPermissionsThenScan());
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
                new String[] {"Slow (300 ms)", "Normal (200 ms)", "Fast (100 ms)"}));
        speedSpinner.setSelection(nativeTextSpeedPosition(textTimeMs(preferences)));
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

        Button about = new Button(this);
        about.setText("About SpotLED Companion");
        content.addView(about);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(content)
                .setPositiveButton("Done", null)
                .setNegativeButton("Cancel", (ignored, which) -> ignored.dismiss())
                .create();
        about.setOnClickListener(view -> {
            dialog.dismiss();
            showAboutDialog();
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String selectedTheme = themeSpinner.getSelectedItemPosition() == 1 ? "light"
                            : themeSpinner.getSelectedItemPosition() == 2 ? "dark" : "system";
                    boolean themeChanged = !selectedTheme.equals(storedTheme);
                    int textTimeMs = nativeTextTimeForPosition(speedSpinner.getSelectedItemPosition());
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

    private static int nativeTextTimeForPosition(int position) {
        if (position == 0) return 300;
        if (position == 2) return 100;
        return 200;
    }

    private static int nativeTextSpeedPosition(int timeMs) {
        if (timeMs <= 150) return 2;
        if (timeMs >= 250) return 0;
        return 1;
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
                        + "with contributions from clavo and OpenAI Codex.")
                .setPositiveButton("Close", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void requestBluetoothPermissionsThenScan() {
        String[] needed = Build.VERSION.SDK_INT >= 31
                ? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
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
        sendRgbContent(bitmapToRgb(source), source.getWidth(), source.getHeight(), effectMode);
    }

    private void sendGif(Movie movie) {
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

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
