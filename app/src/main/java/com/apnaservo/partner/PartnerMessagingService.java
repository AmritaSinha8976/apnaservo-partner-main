package com.apnaservo.partner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PartnerMessagingService extends FirebaseMessagingService {
    private static final String TAG = "PartnerBookingPush";
    private static final int MAX_BANNER_BYTES = 3 * 1024 * 1024;
    private static final int MAX_BANNER_SIDE_PX = 1024;
    private static final String PREFS = "apna_servo_partner_v3";
    private static final String KEY_FCM_TOKEN = "fcm_token";
    private static final String KEY_LOGGED_IN = "logged_in";
    private static final String KEY_PUSH_ACTION_TYPE = "push_action_type";
    private static final String KEY_PUSH_ACTION_ID = "push_action_id";
    private static final String KEY_PUSH_NOTIFICATION_ID = "push_notification_id";
    private static final String KEY_SEEN_NOTIFICATION_IDS = "seen_admin_notification_ids";
    private static final String KEY_SKILL = "partner_skill";
    private static final String KEY_SERVICE_ID = "partner_service_id";
    private static final String CHANNEL_REQUESTS = PartnerBookingActionReceiver.CHANNEL_BOOKING_REQUESTS;
    private static final String CHANNEL_GENERAL = "partner_general_notifications_v1";
    private static final String CHANNEL_ADMIN = "partner_admin_announcements";
    private static final String CHANNEL_BONUSES = "partner_bonus_updates";

    @Override
    public void onNewToken(String token) {
        Log.i(TAG, "FCM token refreshed");
        securePrefs().edit().putString(KEY_FCM_TOKEN, token == null ? "" : token).apply();
        if (securePrefs().getBoolean(KEY_LOGGED_IN, false)) {
            new BackendClient(this).saveFcmToken(token == null ? "" : token);
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        if (!securePrefs().getBoolean(KEY_LOGGED_IN, false)) {
            return;
        }
        Map<String, String> data = message.getData();
        String targetApp = valueAny(data, "", "targetApp", "target_app", "app", "recipientRole", "recipient_role");
        if (!isPartnerTarget(targetApp)) {
            return;
        }
        String notificationId = valueAny(data, "", "notificationId", "notification_id", "push_notification_id", "id");
        String bookingId = valueAny(data, "", "bookingId", "booking_id", "bookingCode", "booking_code");
        String status = valueAny(data, "", "status", "bookingStatus", "booking_status");
        String type = valueAny(data, "", "type", "event", "notificationType", "notification_type");
        String category = valueAny(data, "", "category", "notificationCategory", "notification_category");
        String requestExpiresAt = valueAny(data, "", "requestExpiresAt", "request_expires_at", "expiresAt", "expires_at");
        String bookingService = valueAny(data, "", "serviceId", "service_id", "serviceCategory", "service_category", "serviceType", "service_type", "serviceName", "service_name");
        String actionType = valueAny(data, "", "actionType", "action_type", "push_action_type", "click_action", "screen", "route");
        String actionId = valueAny(data, "", "actionId", "action_id", "push_action_id", "targetId", "target_id");
        actionType = normalizeActionType(actionType, type, category, status, bookingId, actionId);
        if ("CANCEL_BOOKING_NOTIFICATION".equalsIgnoreCase(actionType)) {
            cancelBookingNotification(bookingId);
            return;
        }
        if (("cancelled".equalsIgnoreCase(status) || "expired".equalsIgnoreCase(status)) && !bookingId.isEmpty()) {
            cancelBookingNotification(bookingId);
            return;
        }
        if (actionId.isEmpty() && isBookingAction(actionType)) {
            actionId = bookingId;
        }
        if (bookingId.isEmpty() && isBookingAction(actionType)) {
            bookingId = actionId;
        }
        String imageUrl = valueAny(data, "", "imageUrl", "image_url", "bannerImageUrl", "banner_image_url");
        String title = message.getNotification() == null
                ? valueAny(data, "", "title", "notificationTitle", "notification_title")
                : message.getNotification().getTitle();
        String body = message.getNotification() == null
                ? valueAny(data, "", "body", "message", "notificationBody", "notification_body")
                : message.getNotification().getBody();
        title = readableTitle(title, actionType, status, category, type);
        body = readableBody(body, actionType, status, bookingId, type);
        boolean bookingRequest = isBookingRequest(category, type, status, actionType);
        Log.i(TAG, "FCM received type=" + safe(type) + " booking=" + safe(bookingId)
                + " bookingRequest=" + bookingRequest);
        if (bookingRequest && !notificationId.isEmpty() && alreadyShown(notificationId)) return;
        // Incoming requests are already securely targeted by the backend. Do
        // not drop a valid killed-state push because local service preferences
        // are missing/stale. Keep the legacy check only for non-request updates.
        if (!bookingRequest && isBookingAction(actionType)
                && !bookingService.isEmpty() && !matchesSavedService(bookingService)) {
            return;
        }
        if (bookingRequest) {
            String serviceName = valueAny(data, bookingService, "serviceName", "service_name", "serviceTitle", "service_title");
            String distance = valueAny(data, "Nearby", "distance", "distanceLabel", "distance_label", "distanceKm", "distance_km");
            title = "New Booking Received";
            body = (serviceName.isEmpty() ? "Service request" : serviceName) + "\n" + formatDistance(distance);
        }
        showNotification(title, body, bookingId, actionType, actionId, notificationId, imageUrl,
                bookingRequest, requestExpiresAt);
    }

    private void showNotification(
            String title,
            String body,
            String bookingId,
            String actionType,
            String actionId,
            String notificationId,
            String imageUrl,
            boolean bookingRequest,
            String requestExpiresAt
    ) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        ensureChannels(manager);
        String channelId = channelFor(actionType, bookingId, bookingRequest);

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(KEY_PUSH_ACTION_TYPE, actionType);
        intent.putExtra(KEY_PUSH_ACTION_ID, actionId);
        intent.putExtra(KEY_PUSH_NOTIFICATION_ID, notificationId);
        // Booking alerts use one deterministic notification slot per booking.
        // Socket/FCM retries therefore replace instead of duplicating alerts,
        // and accepted/cancelled/expired events can reliably remove the slot.
        int requestCode = !bookingId.isEmpty()
                ? Math.abs(bookingId.hashCode())
                : (notificationId.isEmpty() ? (int) System.currentTimeMillis() : Math.abs(notificationId.hashCode()));
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_partner_dash_bell)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setCategory(bookingRequest ? Notification.CATEGORY_MESSAGE : Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_MAX)
                .setOnlyAlertOnce(true)
                .setDefaults(Notification.DEFAULT_VIBRATE);
        long timeoutMillis = millisUntil(requestExpiresAt);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bookingRequest && timeoutMillis > 0L) {
            builder.setTimeoutAfter(timeoutMillis);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && bookingRequest) {
            builder.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.incoming_booking_alert));
        }
        if (!bookingId.isEmpty() && bookingRequest) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectBookingPendingIntent(bookingId, requestCode + 11));
            builder.addAction(android.R.drawable.ic_menu_send, "Accept", bookingActionPendingIntent(bookingId, true, requestCode + 12));
        }
        Bitmap banner = downloadBanner(imageUrl);
        try {
            if (banner != null) {
                builder.setStyle(new Notification.BigPictureStyle().bigPicture(banner).setSummaryText(body));
            }
            manager.notify(requestCode, builder.build());
            Log.i(TAG, "Notification displayed booking=" + safe(bookingId));
        } catch (RuntimeException ignored) {
            // Notification permission, malformed OEM payloads, or a shutting-down
            // notification service must never crash the app process.
        } finally {
            if (banner != null && !banner.isRecycled()) banner.recycle();
        }
    }

    private long millisUntil(String isoTime) {
        if (isoTime == null || isoTime.trim().isEmpty()) return 0L;
        try {
            String normalized = isoTime.trim();
            java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat(
                    normalized.contains(".") ? "yyyy-MM-dd'T'HH:mm:ss.SSSX" : "yyyy-MM-dd'T'HH:mm:ssX",
                    Locale.US);
            parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date expires = parser.parse(normalized);
            return expires == null ? 0L : Math.max(1L, expires.getTime() - System.currentTimeMillis());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void ensureChannels(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        manager.createNotificationChannel(bookingChannel());
        manager.createNotificationChannel(channel(CHANNEL_GENERAL, "General notifications", "Booking status, payment and account updates"));
        manager.createNotificationChannel(channel(CHANNEL_ADMIN, "Admin announcements", "Important partner announcements"));
        manager.createNotificationChannel(channel(CHANNEL_BONUSES, "Bonus updates", "Partner bonus and earnings promotions"));
    }

    @android.annotation.SuppressLint("NewApi")
    private NotificationChannel channel(String id, String name, String description) {
        NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(description);
        return channel;
    }

    @android.annotation.SuppressLint("NewApi")
    private NotificationChannel bookingChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_REQUESTS,
                "Incoming booking requests", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Immediate booking alerts with the ApnaServo booking alert sound");
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        channel.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.incoming_booking_alert), attributes);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 250, 120, 250});
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        return channel;
    }

    private PendingIntent rejectBookingPendingIntent(String bookingId, int requestCode) {
        return bookingActionPendingIntent(bookingId, false, requestCode);
    }

    private PendingIntent bookingActionPendingIntent(String bookingId, boolean accept, int requestCode) {
        Intent intent = new Intent(this, PartnerBookingActionReceiver.class);
        intent.setAction(accept ? PartnerBookingActionReceiver.ACTION_ACCEPT_BOOKING : PartnerBookingActionReceiver.ACTION_REJECT_BOOKING);
        intent.putExtra(PartnerBookingActionReceiver.EXTRA_BOOKING_ID, bookingId);
        return PendingIntent.getBroadcast(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private String formatDistance(String raw) {
        String value = safe(raw).trim();
        if (value.isEmpty() || "Nearby".equalsIgnoreCase(value)) return "Nearby service request";
        if (value.toLowerCase(Locale.ROOT).contains("km") || value.toLowerCase(Locale.ROOT).contains("away")) {
            return value.toLowerCase(Locale.ROOT).contains("away") ? value : value + " away";
        }
        try { return String.format(Locale.getDefault(), "%.1f km away", Double.parseDouble(value)); }
        catch (Exception ignored) { return value; }
    }

    private PendingIntent openBookingPendingIntent(String bookingId, int requestCode) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(KEY_PUSH_ACTION_TYPE, "OPEN_PARTNER_BOOKING");
        intent.putExtra(KEY_PUSH_ACTION_ID, bookingId);
        return PendingIntent.getActivity(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void cancelBookingNotification(String bookingId) {
        if (bookingId == null || bookingId.trim().isEmpty()) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(Math.abs(bookingId.hashCode()));
    }

    private String channelFor(String actionType, String bookingId, boolean bookingRequest) {
        if ("OPEN_OFFERS".equalsIgnoreCase(actionType)) return CHANNEL_BONUSES;
        if (bookingRequest) {
            return CHANNEL_REQUESTS;
        }
        return CHANNEL_GENERAL;
    }

    private boolean isPartnerTarget(String targetApp) {
        if (targetApp == null || targetApp.trim().isEmpty()) return true;
        String normalized = targetApp.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("PARTNER") || normalized.equals("ALL");
    }

    private boolean matchesSavedService(String incoming) {
        SharedPreferences prefs = securePrefs();
        String saved = ServiceRouting.canonicalServiceId(prefs.getString(KEY_SERVICE_ID, ""));
        if (saved.isEmpty()) {
            String csv = prefs.getString(KEY_SKILL, "");
            saved = ServiceRouting.canonicalServiceId(csv.contains(",") ? csv.substring(0, csv.indexOf(',')) : csv);
        }
        String received = ServiceRouting.canonicalServiceId(incoming);
        return !saved.isEmpty() && !received.isEmpty() && ServiceRouting.matchesAssignment(saved, received);
    }

    private String normalizeService(String value) {
        String clean = safe(value).toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (clean.equals("ac_repair") || clean.equals("air_conditioner") || clean.equals("ac_service")) return "ac";
        if (clean.equals("plumber") || clean.equals("plumbing_service")) return "plumbing";
        if (clean.equals("electric") || clean.equals("electrical")) return "electrician";
        if (clean.equals("appliance")) return "appliances";
        if (clean.equals("cleaning_services")) return "cleaning";
        if (clean.equals("dry_clean") || clean.equals("dry_cleaning")) return "laundry";
        if (clean.equals("ro_service") || clean.equals("water_purifier")) return "ro";
        return clean;
    }

    private String normalizeActionType(String actionType, String type, String category, String status, String bookingId, String actionId) {
        String normalized = actionType == null ? "" : actionType.trim().toUpperCase(Locale.ROOT);
        String typeValue = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String categoryValue = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        String statusValue = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "NONE".equals(normalized)) {
            if (typeValue.contains("chat") || categoryValue.equals("chat")) return "OPEN_BOOKING_CHAT";
            if (categoryValue.contains("support") || typeValue.contains("support")) return "OPEN_SUPPORT";
            if (categoryValue.contains("payment") || statusValue.equals("amount_pending") || typeValue.contains("payment")) return "OPEN_PAYMENT";
            if (!safe(bookingId).isEmpty() || !safe(actionId).isEmpty() || typeValue.startsWith("booking:") || categoryValue.startsWith("booking")) {
                return "OPEN_PARTNER_BOOKING";
            }
            return "OPEN_PARTNER_HOME";
        }
        if ("BOOKING_CHAT".equals(normalized) || "CHAT".equals(normalized)) return "OPEN_BOOKING_CHAT";
        if ("BOOKING".equals(normalized) || "BOOKING_DETAIL".equals(normalized) || "CURRENT_BOOKING".equals(normalized)) return "OPEN_PARTNER_BOOKING";
        if ("PAYMENT".equals(normalized)) return "OPEN_PAYMENT";
        return normalized;
    }

    private boolean isBookingAction(String actionType) {
        String normalized = actionType == null ? "" : actionType.trim().toUpperCase(Locale.ROOT);
        return "OPEN_PARTNER_BOOKING".equals(normalized)
                || "OPEN_BOOKING".equals(normalized)
                || "OPEN_BOOKING_CHAT".equals(normalized)
                || "OPEN_PAYMENT".equals(normalized);
    }

    private boolean isBookingRequest(String category, String type, String status, String actionType) {
        String categoryValue = safe(category).toLowerCase(Locale.ROOT);
        String typeValue = safe(type).toLowerCase(Locale.ROOT);
        String statusValue = safe(status).toLowerCase(Locale.ROOT);
        String action = safe(actionType).toUpperCase(Locale.ROOT);
        return ("OPEN_PARTNER_BOOKING".equals(action) || "OPEN_BOOKING".equals(action))
                && !"OPEN_BOOKING_CHAT".equals(action)
                && (categoryValue.contains("booking_request")
                || categoryValue.contains("emergency_booking")
                || typeValue.contains("new_request")
                || typeValue.contains("sent_to_partner")
                || "sent_to_partner".equals(statusValue)
                || "pending".equals(statusValue));
    }

    private String readableTitle(String title, String actionType, String status, String category, String type) {
        String clean = title == null ? "" : title.trim();
        if (!clean.isEmpty() && !"ApnaServo Partner".equalsIgnoreCase(clean) && !"ApnaServo".equalsIgnoreCase(clean) && !"Notification".equalsIgnoreCase(clean)) {
            return clean;
        }
        String action = safe(actionType).toUpperCase(Locale.ROOT);
        String state = safe(status).toLowerCase(Locale.ROOT);
        String categoryValue = safe(category).toLowerCase(Locale.ROOT);
        String typeValue = safe(type).toLowerCase(Locale.ROOT);
        if ("OPEN_BOOKING_CHAT".equals(action) || typeValue.contains("chat") || categoryValue.equals("chat")) return "Customer message";
        if ("OPEN_PAYMENT".equals(action) || "amount_pending".equals(state)) return "Payment verification";
        if (categoryValue.contains("booking_request") || "sent_to_partner".equals(state) || "pending".equals(state)) return "New booking request";
        if ("accepted".equals(state)) return "Booking accepted";
        if ("cancelled".equals(state)) return "Booking cancelled";
        if ("completed".equals(state)) return "Booking completed";
        if ("OPEN_PARTNER_BOOKING".equals(action) || "OPEN_BOOKING".equals(action)) return "Booking update";
        return "ApnaServo Partner update";
    }

    private String readableBody(String body, String actionType, String status, String bookingId, String type) {
        String clean = body == null ? "" : body.trim();
        if (!clean.isEmpty() && !"Open the app to view the latest update".equalsIgnoreCase(clean) && !"New update received".equalsIgnoreCase(clean)) {
            return clean;
        }
        String action = safe(actionType).toUpperCase(Locale.ROOT);
        String state = safe(status).toLowerCase(Locale.ROOT);
        String code = safe(bookingId).isEmpty() ? "this booking" : "booking " + bookingId;
        if ("OPEN_BOOKING_CHAT".equals(action) || safe(type).toLowerCase(Locale.ROOT).contains("chat")) return "Tap to reply to the customer chat.";
        if ("OPEN_PAYMENT".equals(action) || "amount_pending".equals(state)) return "Tap to verify payment for " + code + ".";
        if ("sent_to_partner".equals(state) || "pending".equals(state)) return "Tap to view, accept, or reject " + code + ".";
        if ("cancelled".equals(state)) return "The customer cancelled " + code + ".";
        return "Tap to open " + code + ".";
    }

    private String valueAny(Map<String, String> data, String fallback, String... keys) {
        if (data == null || keys == null) return fallback;
        for (String key : keys) {
            String result = data.get(key);
            if (result != null && !result.trim().isEmpty()) return result.trim();
        }
        return fallback;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private Bitmap downloadBanner(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("https://")) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_BANNER_BYTES) return null;
            byte[] encoded = readBounded(connection, MAX_BANNER_BYTES);
            if (encoded == null || encoded.length == 0) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_BANNER_SIDE_PX);
            return BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private byte[] readBounded(HttpURLConnection connection, int maxBytes) throws Exception {
        try (java.io.InputStream input = connection.getInputStream();
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) return null;
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private int sampleSize(int width, int height, int maxSide) {
        int sample = 1;
        while (width / sample > maxSide || height / sample > maxSide) {
            sample *= 2;
        }
        return sample;
    }

    private boolean alreadyShown(String notificationId) {
        String raw = securePrefs().getString(KEY_SEEN_NOTIFICATION_IDS, "");
        Set<String> ids = new LinkedHashSet<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String id : raw.split(",")) {
                if (!id.trim().isEmpty()) ids.add(id.trim());
            }
        }
        if (ids.contains(notificationId)) return true;
        ids.add(notificationId);
        while (ids.size() > 100) ids.remove(ids.iterator().next());
        securePrefs().edit().putString(KEY_SEEN_NOTIFICATION_IDS, String.join(",", ids)).apply();
        return false;
    }

    private SharedPreferences securePrefs() {
        try {
            String keyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    PREFS,
                    keyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception ignored) {
            return getSharedPreferences(PREFS, MODE_PRIVATE);
        }
    }

    private String value(Map<String, String> data, String key, String fallback) {
        if (data == null) return fallback;
        String result = data.get(key);
        return result == null || result.trim().isEmpty() ? fallback : result.trim();
    }
}
