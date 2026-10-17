package com.apnaservo.partner;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class AppControlRepository {
    interface Listener { void onConfig(AppConfig config, boolean fromNetwork); }

    static final String[] FEATURE_IDS = {
            "partner_dashboard", "partner_booking_management", "partner_earnings",
            "partner_availability", "partner_profile", "partner_notifications",
            "partner_support", "partner_location", "partner_documents"
    };
    private static final String PREFS = "partner_app_control_cache";
    private static final String KEY_JSON = "last_valid_config";
    static final int MAX_REMOTE_CONFIG_CHARS = 256 * 1024;
    private static final int MAX_RETRY_ATTEMPTS = 4;
    private final BackendClient client;
    private final SharedPreferences prefs;
    private AppConfig current;
    private boolean fetching;
    private boolean disposed;
    private boolean retryScheduled;
    private int retryAttempt;
    private final Handler main = new Handler(Looper.getMainLooper());

    AppControlRepository(Context context, BackendClient client) {
        this.client = client;
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_JSON, "");
        AppConfig cached = raw != null && raw.length() <= MAX_REMOTE_CONFIG_CHARS ? parseSafely(raw) : AppConfig.invalid();
        if (!cached.valid && !TextUtils.isEmpty(raw)) prefs.edit().remove(KEY_JSON).apply();
        this.current = cached.valid ? cached : AppConfig.defaults();
    }

    AppConfig current() { return current == null ? AppConfig.defaults() : current; }

    void refresh(Listener listener) {
        if (disposed) return;
        if (client == null || fetching) {
            if (listener != null) listener.onConfig(current(), false);
            return;
        }
        fetching = true;
        client.fetchPartnerAppControl(new BackendClient.JsonCallback() {
            @Override public void onSuccess(JSONObject object) {
                fetching = false;
                String raw = object == null ? "" : object.toString();
                AppConfig parsed = raw.length() <= MAX_REMOTE_CONFIG_CHARS ? parseSafely(raw) : AppConfig.invalid();
                if (!disposed && parsed.valid && compareRevision(parsed.configVersion, current().configVersion) >= 0) {
                    current = parsed;
                    prefs.edit().putString(KEY_JSON, raw).apply();
                    retryAttempt = 0;
                    if (listener != null) listener.onConfig(current, true);
                } else {
                    if (listener != null && !disposed) listener.onConfig(current(), false);
                    if (!disposed) scheduleRetry(listener);
                }
            }
            @Override public void onError(String message) {
                fetching = false;
                if (!disposed && listener != null) listener.onConfig(current(), false);
                if (!disposed) scheduleRetry(listener);
            }
        });
    }

    void dispose() {
        disposed = true;
        retryScheduled = false;
        main.removeCallbacksAndMessages(null);
    }

    private void scheduleRetry(Listener listener) {
        if (disposed || retryScheduled || retryAttempt >= MAX_RETRY_ATTEMPTS) return;
        retryScheduled = true;
        long delay = Math.min(30_000L, 1_000L << retryAttempt) + (long) (Math.random() * 750L);
        retryAttempt++;
        main.postDelayed(() -> {
            retryScheduled = false;
            refresh(listener);
        }, delay);
    }

    private static AppConfig parseSafely(String raw) {
        if (TextUtils.isEmpty(raw)) return AppConfig.invalid();
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject config = root.optJSONObject("config");
            if (config == null) return AppConfig.invalid();
            AppConfig out = new AppConfig();
            out.valid = true;
            out.configVersion = limited(root.optString("configVersion", "0"), 40);
            JSONObject status = config.optJSONObject("appStatus");
            String rawStatus = status == null ? config.optString("appStatus", "LIVE")
                    : status.optString("mode", status.optString("status", status.optString("code", status.optString("type", "LIVE"))));
            out.status = supportedStatus(rawStatus);
            out.statusTitle = limited(status == null ? "" : status.optString("title", ""), 120);
            out.statusMessage = limited(status == null ? "" : status.optString("message", ""), 500);
            JSONObject update = config.optJSONObject("update");
            out.latestVersion = update == null ? "" : limited(update.optString("latestVersion", ""), 32);
            out.latestBuild = update == null ? 0 : Math.max(0, update.optInt("latestBuild", 0));
            out.minimumVersion = update == null ? "" : limited(update.optString("minimumVersion", ""), 32);
            out.minimumBuild = update == null ? 0 : Math.max(0, update.optInt("minimumBuild", 0));
            out.updateType = update == null ? "soft" : safeUpdateType(update.optString("type", "soft"));
            out.updateEnabled = update != null && update.optBoolean("enabled", false) && validVersion(out.latestVersion);
            if ("force".equals(out.updateType) && !validVersion(out.minimumVersion)) out.minimumVersion = out.latestVersion;
            if (!validVersion(out.minimumVersion)) out.minimumVersion = "";
            out.updateTitle = limited(update == null ? "" : update.optString("title", ""), 100);
            out.updateMessage = limited(update == null ? "" : update.optString("message", ""), 400);
            out.updateButton = limited(update == null ? "" : update.optString("buttonText", ""), 40);
            out.storeUrl = limited(update == null ? "" : update.optString("storeUrl", ""), 500);
            JSONObject ui = config.optJSONObject("ui");
            out.uiHomeTitle = ui == null ? "" : ui.optString("homeTitle", "");
            out.uiHomeSubtitle = ui == null ? "" : ui.optString("homeSubtitle", "");
            if (ui != null) { JSONArray hidden = ui.optJSONArray("hiddenSections"); if (hidden != null) for (int i = 0; i < Math.min(hidden.length(), 32); i++) out.uiHiddenSections.add(limited(hidden.optString(i, ""), 48)); }
            JSONObject theme = config.optJSONObject("theme");
            out.primaryColor = safeColor(theme == null ? "" : theme.optString("primaryColor", ""), "#f32368");
            JSONObject partnerHome = config.optJSONObject("partnerHome");
            out.partnerHomeSections = safeArray(partnerHome == null ? null : partnerHome.optJSONArray("sections"), 40);
            JSONObject laundry = config.optJSONObject("laundry");
            out.laundrySections = safeArray(laundry == null ? null : laundry.optJSONArray("sections"), 40);
            out.laundryDashboardCards = safeArray(laundry == null ? null : laundry.optJSONArray("dashboardCards"), 20);
            out.laundryServices = safeArray(laundry == null ? null : laundry.optJSONArray("services"), 100);
            out.laundryItems = safeArray(laundry == null ? null : laundry.optJSONArray("items"), 100);
            out.pickupWorkflow = safeArray(laundry == null ? null : laundry.optJSONArray("pickupWorkflow"), 20);
            out.deliveryWorkflow = safeArray(laundry == null ? null : laundry.optJSONArray("deliveryWorkflow"), 20);
            JSONObject laundrySettings = laundry == null ? null : laundry.optJSONObject("settings");
            out.pickupEnabled = laundrySettings == null || laundrySettings.optBoolean("pickupEnabled", true);
            out.deliveryEnabled = laundrySettings == null || laundrySettings.optBoolean("deliveryEnabled", true);
            out.staffEnabled = laundrySettings == null || laundrySettings.optBoolean("staffEnabled", true);
            out.navigation = safeArray(config.optJSONArray("navigation"), 12);
            out.formSchemas = config.optJSONObject("forms") == null ? new JSONObject() : config.optJSONObject("forms");
            JSONObject features = config.optJSONObject("features");
            for (String id : FEATURE_IDS) {
                JSONObject feature = features == null ? null : features.optJSONObject(id);
                out.features.put(id, feature == null || feature.optBoolean("enabled", true));
            }
            JSONArray announcements = config.optJSONArray("announcements");
            if (announcements == null) announcements = root.optJSONArray("announcements");
            if (announcements != null) for (int i = 0; i < Math.min(announcements.length(), 30); i++) {
                JSONObject item = announcements.optJSONObject(i);
                if (item != null) out.announcements.add(item);
            }
            return out;
        } catch (Exception ignored) {
            return AppConfig.invalid();
        }
    }

    private static String limited(String value, int max) { String safe = value == null ? "" : value.trim(); return safe.length() <= max ? safe : safe.substring(0, max); }
    private static String safeColor(String value, String fallback) { String safe = limited(value, 7); return safe.matches("#[0-9a-fA-F]{6}") ? safe : fallback; }
    private static JSONArray safeArray(JSONArray source, int max) { JSONArray output = new JSONArray(); if (source != null) for (int i = 0; i < Math.min(source.length(), max); i++) { JSONObject item = source.optJSONObject(i); if (item != null) output.put(item); } return output; }
    private static String supportedStatus(String value) { String status = value == null ? "LIVE" : value.trim().toUpperCase(Locale.US); return "MAINTENANCE".equals(status) || "COMING_SOON".equals(status) || "TEMPORARILY_UNAVAILABLE".equals(status) ? status : "LIVE"; }
    private static String safeUpdateType(String value) {
        // Admin labels are human-facing ("Normal" / "Mandatory"); keep the
        // client contract canonical so either spelling remains safe to publish.
        return ("force".equalsIgnoreCase(value) || "mandatory".equalsIgnoreCase(value)) ? "force" : "soft";
    }
    private static boolean validVersion(String value) { return value != null && value.matches("\\d+(?:\\.\\d+){1,3}"); }
    private static int compareRevision(String left, String right) { return AppConfig.compareVersions(left, right); }

    static final class AppConfig {
        boolean valid;
        String configVersion = "0", status = "LIVE", statusTitle = "", statusMessage = "";
        boolean updateEnabled;
        int latestBuild, minimumBuild;
        String updateType = "soft", latestVersion = "", minimumVersion = "", updateTitle = "", updateMessage = "", updateButton = "Update", storeUrl = "", uiHomeTitle = "", uiHomeSubtitle = "";
        final List<String> uiHiddenSections = new ArrayList<>();
        final java.util.Map<String, Boolean> features = new java.util.HashMap<>();
        final List<JSONObject> announcements = new ArrayList<>();
        String primaryColor = "#f32368";
        JSONArray partnerHomeSections = new JSONArray(), laundrySections = new JSONArray(), laundryDashboardCards = new JSONArray(), laundryServices = new JSONArray(), laundryItems = new JSONArray(), pickupWorkflow = new JSONArray(), deliveryWorkflow = new JSONArray(), navigation = new JSONArray();
        JSONObject formSchemas = new JSONObject();
        boolean pickupEnabled = true, deliveryEnabled = true, staffEnabled = true;
        static AppConfig defaults() {
            AppConfig c = new AppConfig();
            c.valid = true;
            for (String id : FEATURE_IDS) c.features.put(id, true);
            return c;
        }
        static AppConfig invalid() { return new AppConfig(); }
        boolean featureEnabled(String id) { return !features.containsKey(id) || Boolean.TRUE.equals(features.get(id)); }
        boolean uiSectionVisible(String id) { return !uiHiddenSections.contains(id); }
        JSONArray laundryFormFields(String serviceId) { JSONObject schema = formSchemas.optJSONObject(serviceId); return schema == null || schema.optJSONArray("fields") == null ? new JSONArray() : schema.optJSONArray("fields"); }
        boolean maintenance() { return "MAINTENANCE".equals(status); }
        boolean forceUpdate(String currentVersion) { return forceUpdate(currentVersion, 0); }
        boolean forceUpdate(String currentVersion, int currentBuild) {
            if (!updateEnabled) return false;
            if (minimumBuild > 0 && currentBuild > 0 && currentBuild < minimumBuild) return true;
            return !blank(minimumVersion) && compareVersions(currentVersion, minimumVersion) < 0;
        }
        boolean softUpdate(String currentVersion) { return softUpdate(currentVersion, 0); }
        boolean softUpdate(String currentVersion, int currentBuild) {
            boolean belowLatestBuild = latestBuild > 0 && currentBuild > 0 && currentBuild < latestBuild;
            return updateEnabled && !forceUpdate(currentVersion, currentBuild)
                    && (belowLatestBuild || compareVersions(currentVersion, latestVersion) < 0);
        }
        static int compareVersions(String left, String right) {
            if (blank(right)) return 0;
            String[] a = (left == null ? "" : left).replaceAll("[^0-9.]", "").split("\\.");
            String[] b = right.replaceAll("[^0-9.]", "").split("\\.");
            int size = Math.max(a.length, b.length);
            for (int i = 0; i < size; i++) {
                int x = i < a.length ? number(a[i]) : 0, y = i < b.length ? number(b[i]) : 0;
                if (x != y) return x < y ? -1 : 1;
            }
            return 0;
        }
        private static int number(String value) { try { return Integer.parseInt(value); } catch (Exception ignored) { return 0; } }
        private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    }
}
