package com.apnaservo.partner;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import org.json.JSONObject;

/** Executes notification actions against the backend source of truth. */
public class PartnerBookingActionReceiver extends BroadcastReceiver {
    private static final String PREFS = "apna_servo_partner_v3";
    private static final String ACTION_PREFS = "partner_booking_action_guard";
    static final String ACTION_ACCEPT_BOOKING = "com.apnaservo.partner.ACCEPT_BOOKING";
    static final String ACTION_REJECT_BOOKING = "com.apnaservo.partner.REJECT_BOOKING";
    static final String EXTRA_BOOKING_ID = "booking_id";
    // A new id is intentional: Android preserves the sound selected for an
    // existing channel.  This migrates devices away from the old custom-ring
    // channel to the system-default booking notification behavior.
    static final String CHANNEL_BOOKING_REQUESTS = "partner_booking_requests_alert_v6";

    @Override public void onReceive(Context rawContext, Intent intent) {
        if (rawContext == null || intent == null) return;
        Context context = rawContext.getApplicationContext();
        String bookingId = intent.getStringExtra(EXTRA_BOOKING_ID);
        String action = intent.getAction();
        if (TextUtils.isEmpty(bookingId)
                || (!ACTION_ACCEPT_BOOKING.equals(action) && !ACTION_REJECT_BOOKING.equals(action))) return;
        if (!isLoggedIn(context)) return;

        SharedPreferences guard = context.getSharedPreferences(ACTION_PREFS, Context.MODE_PRIVATE);
        // One booking-level guard blocks ACCEPT+REJECT races as well as double
        // taps. The backend remains authoritative for the final transition.
        String guardKey = "booking:" + bookingId;
        long now = System.currentTimeMillis();
        synchronized (PartnerBookingActionReceiver.class) {
            if (guard.getBoolean(guardKey + ":resolved", false)
                    || now - guard.getLong(guardKey, 0L) < 30_000L) return;
            guard.edit().putLong(guardKey, now).apply();
        }

        PendingResult pending = goAsync();
        BackendClient.JsonCallback callback = new BackendClient.JsonCallback() {
            @Override public void onSuccess(JSONObject object) {
                guard.edit().putBoolean(guardKey + ":resolved", true).apply();
                cancel(context, bookingId);
                if (ACTION_ACCEPT_BOOKING.equals(action)) openAcceptedBooking(context, bookingId);
                pending.finish();
            }
            @Override public void onError(String message) {
                String error = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
                if (error.contains("already") || error.contains("accepted") || error.contains("unavailable")
                        || error.contains("expired") || error.contains("cancel")) {
                    guard.edit().putBoolean(guardKey + ":resolved", true).apply();
                    cancel(context, bookingId);
                } else {
                    guard.edit().remove(guardKey).apply();
                }
                pending.finish();
            }
        };
        BackendClient client = new BackendClient(context);
        if (ACTION_ACCEPT_BOOKING.equals(action)) {
            client.acceptBooking(bookingId, 30, "Within 30 minutes", callback);
        } else {
            client.rejectBooking(bookingId, callback);
        }
    }

    private static void cancel(Context context, String bookingId) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(Math.abs(bookingId.hashCode()));
    }

    private static void openAcceptedBooking(Context context, String bookingId) {
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("push_action_type", "OPEN_PARTNER_BOOKING");
        open.putExtra("push_action_id", bookingId);
        context.startActivity(open);
    }

    private static boolean isLoggedIn(Context context) {
        try {
            String alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences prefs = EncryptedSharedPreferences.create(
                    PREFS, alias, context, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            return prefs.getBoolean("logged_in", false);
        } catch (Exception ignored) {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("logged_in", false);
        }
    }
}
