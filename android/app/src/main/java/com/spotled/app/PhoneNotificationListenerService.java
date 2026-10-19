package com.spotled.app;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Reads only notifications the user explicitly opted to forward from selected apps. */
public final class PhoneNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "PhoneNotificationListener";
    static final String PREF_FORWARD_ENABLED = "forward_phone_notifications";
    static final String PREF_FORWARD_PACKAGES = "phone_notification_packages";
    static final String ACTION_FORWARD_NOTIFICATION = "com.spotled.app.action.FORWARD_PHONE_NOTIFICATION";
    static final String PERMISSION_INTERNAL_NOTIFICATION =
            "com.spotled.app.permission.INTERNAL_NOTIFICATION";
    private String lastForwardedCompanionMqttError;

    static boolean hasNotificationAccess(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName expected = new ComponentName(context, PhoneNotificationListenerService.class);
        for (String value : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (expected.equals(component)) return true;
        }
        return false;
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        SharedPreferences preferences = getSharedPreferences(MqttStatusService.PREFS, MODE_PRIVATE);
        if (!preferences.getBoolean(PREF_FORWARD_ENABLED, false)
                || !preferences.getBoolean(MqttStatusService.PREF_ENABLED, false)) return;
        Set<String> selected = preferences.getStringSet(PREF_FORWARD_PACKAGES, Collections.emptySet());
        String sourcePackage = sbn.getPackageName();
        if (!selected.contains(sourcePackage)) return;

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;
        CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
        if (TextUtils.isEmpty(title)) title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(body)) body = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(body)) {
            CharSequence[] lines = notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) body = TextUtils.join(" ", lines);
        }
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(body)) return;
        boolean ownNotification = getPackageName().equals(sourcePackage);
        if (ownNotification && isMqttRecoveryStatus(title, body))
            lastForwardedCompanionMqttError = null;
        if (!shouldForwardNotification(sourcePackage, getPackageName(), sbn.isOngoing(),
                title, body)) return;
        String companionErrorKey = null;
        if (ownNotification) {
            companionErrorKey = companionMqttErrorKey(title, body);
            if (!shouldForwardCompanionMqttError(title, body,
                    lastForwardedCompanionMqttError)) return;
            lastForwardedCompanionMqttError = companionErrorKey;
            Log.i(TAG, "Forwarding Companion MQTT error category=" + companionErrorKey);
        }

        String appLabel;
        if (ownNotification) {
            appLabel = "";
            title = "";
            body = companionMqttErrorText(title, body, companionErrorKey);
        } else {
            appLabel = sourcePackage;
            try {
                appLabel = String.valueOf(getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(sourcePackage, 0)));
            } catch (Exception ignored) { }
        }

        Intent forward = new Intent(ACTION_FORWARD_NOTIFICATION)
                .setPackage(getPackageName())
                .putExtra("source_package", sourcePackage)
                .putExtra("source_label", appLabel)
                .putExtra("notification_title", title == null ? "" : title.toString())
                .putExtra("notification_body", body == null ? "" : body.toString());
        try {
            sendBroadcast(forward, PERMISSION_INTERNAL_NOTIFICATION);
            if (ownNotification) Log.i(TAG, "Companion MQTT error broadcast submitted");
        } catch (SecurityException exception) {
            Log.e(TAG, "Could not submit Companion MQTT error broadcast", exception);
        }
    }

    static boolean shouldForwardNotification(String sourcePackage, String ownPackage,
                                             boolean ongoing, CharSequence title,
                                             CharSequence body) {
        if (sourcePackage == null || sourcePackage.isEmpty()) return false;
        if (sourcePackage.equals(ownPackage)) return companionMqttErrorKey(title, body) != null;
        return !ongoing;
    }

    static boolean shouldForwardCompanionMqttError(CharSequence title, CharSequence body,
                                                   String lastForwardedErrorKey) {
        String errorKey = companionMqttErrorKey(title, body);
        return errorKey != null && !errorKey.equals(lastForwardedErrorKey);
    }

    static String companionMqttErrorKey(CharSequence title, CharSequence body) {
        String content = ((title == null ? "" : title.toString()) + " "
                + (body == null ? "" : body.toString())).toLowerCase(Locale.ROOT);
        if (content.contains("mqtt connection failed")
                || content.contains("mqtt disconnected; reconnecting")) return "connection";
        if (content.contains("mqtt topic subscription failed")) return "subscription";
        return null;
    }

    static String companionMqttErrorText(CharSequence title, CharSequence body, String errorKey) {
        String content = ((title == null ? "" : title.toString()) + " "
                + (body == null ? "" : body.toString())).toLowerCase(Locale.ROOT);
        if ("subscription".equals(errorKey)) return "MQTT TOPIC FAILED";
        if (content.contains("mqtt disconnected; reconnecting")) return "MQTT DISCONNECTED";
        if ("connection".equals(errorKey)) return "MQTT CONNECTION FAILED";
        return "";
    }

    static boolean isMqttRecoveryStatus(CharSequence title, CharSequence body) {
        String content = ((title == null ? "" : title.toString()) + " "
                + (body == null ? "" : body.toString())).toLowerCase(Locale.ROOT);
        return content.contains("connected to mqtt") || content.contains("reconnected to mqtt")
                || content.contains("running; connecting to mqtt");
    }
}
