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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Reads only notifications the user explicitly opted to forward from selected apps. */
public final class PhoneNotificationListenerService extends NotificationListenerService {
    static final String PREF_FORWARD_ENABLED = "forward_phone_notifications";
    static final String PREF_FORWARD_PACKAGES = "phone_notification_packages";
    static final String ACTION_FORWARD_NOTIFICATION = "com.spotled.app.action.FORWARD_PHONE_NOTIFICATION";
    static final String PERMISSION_INTERNAL_NOTIFICATION =
            "com.spotled.app.permission.INTERNAL_NOTIFICATION";

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
        if (sbn == null || sbn.isOngoing() || getPackageName().equals(sbn.getPackageName())) return;
        SharedPreferences preferences = getSharedPreferences(MqttStatusService.PREFS, MODE_PRIVATE);
        if (!preferences.getBoolean(PREF_FORWARD_ENABLED, false)
                || !preferences.getBoolean(MqttStatusService.PREF_ENABLED, false)) return;
        Set<String> selected = preferences.getStringSet(PREF_FORWARD_PACKAGES, Collections.emptySet());
        if (!selected.contains(sbn.getPackageName())) return;

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

        String appLabel = sbn.getPackageName();
        try {
            appLabel = String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(sbn.getPackageName(), 0)));
        } catch (Exception ignored) { }

        Intent forward = new Intent(ACTION_FORWARD_NOTIFICATION)
                .setPackage(getPackageName())
                .putExtra("source_package", sbn.getPackageName())
                .putExtra("source_label", appLabel)
                .putExtra("notification_title", title == null ? "" : title.toString())
                .putExtra("notification_body", body == null ? "" : body.toString());
        sendBroadcast(forward, PERMISSION_INTERNAL_NOTIFICATION);
    }
}
