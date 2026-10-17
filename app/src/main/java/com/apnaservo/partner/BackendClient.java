package com.apnaservo.partner;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class BackendClient {
    interface JsonCallback {
        void onSuccess(JSONObject object);
        void onError(String message);
    }

    interface PdfCallback {
        void onSuccess(byte[] bytes, String fileName);
        void onError(String message);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_TRANSIENT_RETRIES = 2;
    private static final int MAX_PDF_BYTES = 16 * 1024 * 1024;
    private static final String LOCAL_DEVICE_TOKEN = "local-device-auth";
    private static final String INSTALLATION_PREFS = "apnaservo_partner_installation";
    private static final String INSTALLATION_ID_KEY = "installation_id";
    private static final OkHttpClient SHARED_HTTP = new OkHttpClient.Builder()
            .dispatcher(createDispatcher())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final OkHttpClient http = SHARED_HTTP;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object tokenRefreshLock = new Object();
    private final List<TokenCallback> tokenRefreshWaiters = new ArrayList<>();
    private boolean tokenRefreshInFlight;
    private final List<String> baseUrls;
    private final String devUid;
    private final String deviceId;
    private String activeBaseUrl;
    private String cachedToken = "";

    private static Dispatcher createDispatcher() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(32);
        dispatcher.setMaxRequestsPerHost(8);
        return dispatcher;
    }

    private String installationId(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(INSTALLATION_PREFS, Context.MODE_PRIVATE);
        String existing = sanitizeDeviceId(prefs.getString(INSTALLATION_ID_KEY, ""));
        if (!existing.isEmpty()) return existing;
        String created = sanitizeDeviceId(UUID.randomUUID().toString());
        prefs.edit().putString(INSTALLATION_ID_KEY, created).apply();
        return created;
    }

    BackendClient(Context context) {
        this.baseUrls = buildBaseUrls();
        this.activeBaseUrl = baseUrls.get(0);
        this.deviceId = installationId(context);
        this.devUid = "local-partner-" + deviceId;
    }

    String getSocketUrl() {
        return activeBaseUrl.replace("/api", "");
    }

    List<String> getSocketUrls() {
        List<String> socketUrls = new ArrayList<>();
        for (String url : baseUrls) {
            socketUrls.add(url.replace("/api", ""));
        }
        return socketUrls;
    }

    void warmUp() {
        Request request = new Request.Builder()
                .url(getSocketUrl() + "/health")
                .get()
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                // Authentication requests retain their own retry and fallback handling.
            }

            @Override
            public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }

    void sendOtp(String phone, String role, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone == null ? "" : phone);
            body.put("role", role == null ? "partner" : role);
            body.put("purpose", "login");
        } catch (Exception ignored) {
        }
        publicPost("/otp/send", body, callback == null ? silent() : callback);
    }

    void verifyOtp(String phone, String role, String requestId, String otp, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone == null ? "" : phone);
            body.put("role", role == null ? "partner" : role);
            body.put("purpose", "login");
            body.put("requestId", requestId == null ? "" : requestId);
            body.put("otp", otp == null ? "" : otp);
        } catch (Exception ignored) {
        }
        publicPost("/otp/verify", body, callback == null ? silent() : callback);
    }

    void upsertPartnerProfile(String name, String phone, String email, Set<String> skills, boolean online, int radiusKm, String area, String fcmToken, String photoUrl, JsonCallback callback) {
        upsertPartnerProfile(name, phone, email, skills, online, radiusKm, area, fcmToken, photoUrl, null, Double.NaN, Double.NaN, callback);
    }

    void upsertPartnerProfile(String name, String phone, String email, Set<String> skills, boolean online, int radiusKm, String area, String fcmToken, String photoUrl, JSONObject extraProfile, JsonCallback callback) {
        upsertPartnerProfile(name, phone, email, skills, online, radiusKm, area, fcmToken, photoUrl, extraProfile, Double.NaN, Double.NaN, callback);
    }

    void upsertPartnerProfile(String name, String phone, String email, Set<String> skills, boolean online, int radiusKm, String area, String fcmToken, String photoUrl, JSONObject extraProfile, double lat, double lng, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            JSONArray categories = new JSONArray();
            for (String skill : skills) {
                categories.put(skill);
            }
            body.put("name", name);
            body.put("phone", phone);
            body.put("email", email == null ? "" : email);
            body.put("serviceCategory", categories);
            String serviceId = "";
            for (String skill : skills) {
                serviceId = ServiceRouting.canonicalServiceId(skill);
                if (!serviceId.isEmpty()) break;
            }
            body.put("serviceId", serviceId);
            body.put("serviceType", serviceId);
            body.put("isOnline", online);
            body.put("serviceRadiusKm", radiusKm);
            body.put("serviceArea", area);
            body.put("city", "Guwahati");
            if (Double.isFinite(lat) && Double.isFinite(lng)
                    && (lat != 0 || lng != 0)
                    && lat >= -90 && lat <= 90
                    && lng >= -180 && lng <= 180) {
                body.put("lat", lat);
                body.put("lng", lng);
            }
            body.put("fcmToken", fcmToken == null ? "" : fcmToken);
            body.put("photoUrl", photoUrl == null ? "" : photoUrl);
            if (extraProfile != null) {
                JSONArray keys = extraProfile.names();
                if (keys != null) {
                    for (int index = 0; index < keys.length(); index++) {
                        String key = keys.optString(index, "");
                        if (!key.trim().isEmpty()) {
                            body.put(key, extraProfile.opt(key));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        post("/partners/profile", body, callback);
    }

    void fetchPartnerProfile(JsonCallback callback) {
        get("/partners/me", callback);
    }

    void startLaundryStaffSession(String fcmToken, boolean online, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("fcmToken", fcmToken == null ? "" : fcmToken);
            body.put("isOnline", online);
        } catch (Exception ignored) {
        }
        post("/partners/staff/session", body, callback);
    }

    void fetchLaundryStaffBookings(JsonCallback callback) {
        get("/partners/staff/bookings", callback);
    }

    void setLaundryStaffOnline(boolean online, String fcmToken, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("isOnline", online);
            body.put("fcmToken", fcmToken == null ? "" : fcmToken);
        } catch (Exception ignored) {
        }
        patch("/partners/staff/online", body, callback);
    }

    void assignLaundryStaff(String bookingId, int staffSequence, JsonCallback callback) {
        assignLaundryStaff(bookingId, staffSequence, "pickup", callback);
    }

    void assignLaundryStaff(String bookingId, int staffSequence, String assignmentType, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("staffSequence", staffSequence);
            body.put("assignmentType", assignmentType == null ? "pickup" : assignmentType);
        } catch (Exception ignored) {
        }
        patch("/partners/laundry/bookings/" + bookingId + "/assign-staff", body, callback);
    }

    void addLaundryStaff(String name, String phone, String email, String role, String photoUrl, boolean ownerConfirmed, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name == null ? "" : name);
            body.put("phone", phone == null ? "" : phone);
            body.put("email", email == null ? "" : email);
            body.put("role", role == null ? "Laundry Staff" : role);
            body.put("photoUrl", photoUrl == null ? "" : photoUrl);
            body.put("ownerConfirmed", ownerConfirmed);
        } catch (Exception ignored) {
        }
        // Use the owner + service scoped endpoint. The legacy Laundry-only
        // endpoint rejected valid drafts when approval synchronization lagged.
        post("/partners/services/laundry/staff", body, callback);
    }

    void fetchServiceStaff(String serviceType, JsonCallback callback) {
        get("/partners/services/" + staffServicePath(serviceType) + "/staff", callback);
    }

    void createServiceStaff(String serviceType, String name, String phone, String email,
                            String idType, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name == null ? "" : name);
            body.put("phone", phone == null ? "" : phone);
            body.put("email", email == null ? "" : email);
            body.put("idType", idType == null ? "Official ID" : idType);
            body.put("ownerConfirmed", true);
        } catch (Exception ignored) {
        }
        post("/partners/services/" + staffServicePath(serviceType) + "/staff", body, callback);
    }

    void updateServiceStaff(String serviceType, String staffId, JSONObject fields, JsonCallback callback) {
        patch("/partners/services/" + staffServicePath(serviceType) + "/staff/" + staffId,
                fields == null ? new JSONObject() : fields, callback);
    }

    void removeServiceStaff(String serviceType, String staffId, JsonCallback callback) {
        delete("/partners/services/" + staffServicePath(serviceType) + "/staff/" + staffId,
                new JSONObject(), callback);
    }

    private String staffServicePath(String serviceType) {
        return "cleaning".equalsIgnoreCase(serviceType) ? "cleaning" : "laundry";
    }

    void uploadLaundryStaffPhoto(int staffSequence, File file, JsonCallback callback) {
        if (file == null || !file.exists()) {
            callback.onError("Staff photo file missing");
            return;
        }
        if (staffSequence <= 0) {
            callback.onError("Staff profile is unavailable");
            return;
        }
        getIdToken(new TokenCallback() {
            @Override
            public void onToken(String token) {
                executeSingleImageUploadWithFallback("/partners/laundry/staff/" + staffSequence + "/photo", "photo", file, token,
                        callback == null ? silent() : callback, 0, null);
            }

            @Override
            public void onError(String message) {
                main.post(() -> (callback == null ? silent() : callback).onError(message));
            }
        });
    }

    void uploadLaundryStaffIdentity(int staffSequence, File file, String idType, JsonCallback callback) {
        if (file == null || !file.exists()) {
            callback.onError("Government ID file missing");
            return;
        }
        if (staffSequence <= 0) {
            callback.onError("Staff profile is unavailable");
            return;
        }
        getIdToken(new TokenCallback() {
            @Override
            public void onToken(String token) {
                executeLaundryStaffIdentityUpload("/partners/laundry/staff/" + staffSequence + "/identity", file, idType, token,
                        callback == null ? silent() : callback, 0, null);
            }

            @Override
            public void onError(String message) {
                main.post(() -> (callback == null ? silent() : callback).onError(message));
            }
        });
    }

    void updateLaundryStaffTask(String bookingId, String status, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("status", status == null ? "" : status);
        } catch (Exception ignored) {
        }
        patch("/partners/staff/bookings/" + bookingId + "/status", body, callback);
    }

    void updateStaffBookingStatus(String bookingId, String status, int finalAmount, LocationPayload location, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("status", status == null ? "" : status);
            if (finalAmount > 0) body.put("finalAmount", finalAmount);
            if (location != null) {
                body.put("lat", location.lat);
                body.put("lng", location.lng);
                body.put("accuracy", location.accuracy);
                body.put("provider", location.provider == null ? "" : location.provider);
                body.put("isMock", location.isMock);
                body.put("recordedAt", location.recordedAtMillis);
            }
        } catch (Exception ignored) {
        }
        patch("/partners/staff/bookings/" + bookingId + "/status", body, callback);
    }

    void saveFcmToken(String token) {
        JSONObject body = new JSONObject();
        try {
            body.put("token", token == null ? "" : token);
            body.put("platform", "android");
            body.put("deviceId", deviceId);
            body.put("appType", "partner");
        } catch (Exception ignored) {
        }
        post("/notifications/device-token", body, silent());
    }

    void removeFcmToken(String token, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("token", token == null ? "" : token);
            body.put("deviceId", deviceId);
            body.put("appType", "partner");
        } catch (Exception ignored) {
        }
        delete("/notifications/device-token", body, callback == null ? silent() : callback);
    }

    void requestAccountDeletion(String reason, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("reason", reason == null ? "" : reason.trim());
        } catch (Exception ignored) {
        }
        post("/partners/delete-account-request", body, callback);
    }

    void fetchNotifications(JsonCallback callback) {
        fetchNotifications(1, 25, callback);
    }

    void fetchNotifications(int page, int limit, JsonCallback callback) {
        get("/notifications/my-notifications?role=partner&page=" + Math.max(1, page) + "&limit=" + Math.max(1, Math.min(100, limit)), callback);
    }

    void markNotificationRead(String notificationId) {
        if (notificationId == null || notificationId.trim().isEmpty()) {
            return;
        }
        patch("/notifications/" + notificationId.trim() + "/read?role=partner", new JSONObject(), silent());
    }

    void markAllNotificationsRead(JsonCallback callback) {
        patch("/notifications/read-all?role=partner", new JSONObject(), callback == null ? silent() : callback);
    }

    void createPartnerSupportTicket(String category, String message, String clientMessageId, String attachmentUrl, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("category", category == null ? "Partner Verification Support" : category);
            body.put("message", message == null ? "" : message);
            body.put("clientMessageId", clientMessageId == null ? "" : clientMessageId);
            body.put("attachmentUrl", attachmentUrl == null ? "" : attachmentUrl);
            body.put("priority", "high");
        } catch (Exception ignored) {
        }
        post("/partners/support-tickets", body, callback == null ? silent() : callback);
    }

    void setOnline(boolean online) {
        post(online ? "/partners/online" : "/partners/offline", new JSONObject(), silent());
    }

    /**
     * Submit the partner's availability slots for today and/or tomorrow.
     * Each day is a JSONObject: { date:"YYYY-MM-DD", morning, afternoon, evening }.
     * The backend validates the dates (today/tomorrow IST only).
     */
    void saveAvailability(org.json.JSONArray slots, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("slots", slots == null ? new org.json.JSONArray() : slots);
        } catch (Exception ignored) {
        }
        put("/partners/availability", body, callback == null ? silent() : callback);
    }

    void updateLocation(double lat, double lng) {
        updateLocation(lat, lng, 9999, "unknown", false, "", silent());
    }

    void updateLocation(double lat, double lng, float accuracy, String provider, boolean isMock, String bookingId, JsonCallback callback) {
        updateLocation(new LocationPayload(lat, lng, accuracy, provider, isMock, System.currentTimeMillis()), bookingId, callback);
    }

    void updateLocation(LocationPayload location, String bookingId, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("lat", location.lat);
            body.put("lng", location.lng);
            body.put("accuracy", location.accuracy);
            body.put("provider", location.provider == null ? "" : location.provider);
            body.put("isMock", location.isMock);
            body.put("bookingId", bookingId == null ? "" : bookingId);
            body.put("recordedAt", location.recordedAtMillis);
        } catch (Exception ignored) {
        }
        patch("/partners/location", body, callback == null ? silent() : callback);
    }

    void fetchPartnerBookings(JsonCallback callback) {
        get("/bookings/partner", callback);
    }

    void fetchBillingStatus(JsonCallback callback) {
        get("/partners/billing/status", callback);
    }

    void fetchReferrals(JsonCallback callback) {
        get("/partners/referrals", callback);
    }

    void applyReferralCode(String code, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("referralCode", code == null ? "" : code.trim().toUpperCase());
        } catch (Exception ignored) {
        }
        post("/partners/referrals/apply", body, callback == null ? silent() : callback);
    }

    void createBillingOrder(JsonCallback callback) {
        post("/partners/billing/razorpay/order", new JSONObject(), callback);
    }

    void verifyBillingPayment(String orderId, String paymentId, String signature, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("razorpayOrderId", orderId);
            body.put("razorpayPaymentId", paymentId);
            body.put("razorpaySignature", signature);
        } catch (Exception ignored) {
        }
        post("/partners/billing/razorpay/verify", body, callback);
    }

    // ── NEW: Cashfree subscription flow (separate from the Razorpay methods
    //        above, which are left untouched). ─────────────────────────────
    void createCashfreeSubscriptionOrder(JsonCallback callback) {
        post("/partners/billing/cashfree/order", new JSONObject(), callback);
    }

    void verifyCashfreeSubscription(String orderId, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("orderId", orderId == null ? "" : orderId);
        } catch (Exception ignored) {
        }
        post("/partners/billing/cashfree/verify", body, callback);
    }

    void getBooking(String bookingId, JsonCallback callback) {
        get("/bookings/" + bookingId, callback);
    }

    void getServiceChecklist(String serviceCategory, JsonCallback callback) {
        String category = serviceCategory == null ? "service" : serviceCategory.trim();
        get("/bookings/service-checklists/" + android.net.Uri.encode(category), callback);
    }

    void acceptBooking(String bookingId, int arrivalEstimateMinutes, String arrivalEstimateLabel, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("arrivalEstimateMinutes", arrivalEstimateMinutes);
            body.put("arrivalEstimateLabel", arrivalEstimateLabel == null ? "" : arrivalEstimateLabel);
        } catch (Exception ignored) {
        }
        post("/bookings/" + bookingId + "/accept", body, callback);
    }

    void rejectBooking(String bookingId, JsonCallback callback) {
        post("/bookings/" + bookingId + "/reject", new JSONObject(), callback);
    }

    void respondToLocationChange(String bookingId, String action, String reason, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("action", action == null ? "accept" : action);
            body.put("reason", reason == null ? "" : reason);
        } catch (Exception ignored) {
        }
        post("/bookings/" + bookingId + "/location-response", body, callback);
    }

    void updateBookingStatus(String bookingId, String status, int finalAmount, JsonCallback callback) {
        updateBookingStatus(bookingId, status, finalAmount, null, callback);
    }

    void updateBookingStatus(String bookingId, String status, int finalAmount, LocationPayload location, JsonCallback callback) {
        updateBookingStatus(bookingId, status, finalAmount, location, null, "", "", callback);
    }

    void updateBookingStatus(String bookingId, String status, int finalAmount, LocationPayload location,
                             JSONArray completedTasks, String additionalWork, String partnerNotes, JsonCallback callback) {
        updateBookingStatus(bookingId, status, finalAmount, location, completedTasks, additionalWork,
                new JSONArray(), partnerNotes, 0, "", callback);
    }

    void updateBookingStatus(String bookingId, String status, int finalAmount, LocationPayload location,
                             JSONArray completedTasks, String workDescription, JSONArray customWork,
                             String additionalNotes, int checklistVersion, String idempotencyKey,
                             JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("status", status);
            body.put("finalAmount", finalAmount);
            if (completedTasks != null) body.put("completedTasks", completedTasks);
            body.put("additionalWork", workDescription == null ? "" : workDescription);
            body.put("partnerNotes", additionalNotes == null ? "" : additionalNotes);
            if (checklistVersion > 0) {
                body.put("structuredQuoteVersion", 1);
                body.put("workDescription", workDescription == null ? "" : workDescription);
                body.put("customWork", customWork == null ? new JSONArray() : customWork);
                body.put("additionalNotes", additionalNotes == null ? "" : additionalNotes);
                body.put("checklistVersion", checklistVersion);
                body.put("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey);
            }
            if (location != null) {
                body.put("lat", location.lat);
                body.put("lng", location.lng);
                body.put("accuracy", location.accuracy);
                body.put("provider", location.provider == null ? "" : location.provider);
                body.put("isMock", location.isMock);
                body.put("recordedAt", location.recordedAtMillis);
            }
        } catch (Exception ignored) {
        }
        patch("/bookings/" + bookingId + "/status", body, callback);
    }

    void monitorBookingChat(String bookingId, String message, String clientMessageId) {
        if (bookingId == null || bookingId.trim().isEmpty() || message == null || message.trim().isEmpty()) {
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("message", message);
            body.put("clientMessageId", clientMessageId == null ? "" : clientMessageId);
            body.put("source", "partner_support_chat");
        } catch (Exception ignored) {
        }
        post("/bookings/" + bookingId + "/chat/monitor", body, silent());
    }

    void fetchBookingChatMessages(String bookingId, JsonCallback callback) {
        if (bookingId == null || bookingId.trim().isEmpty()) {
            callback.onError("Booking missing");
            return;
        }
        get("/bookings/" + bookingId.trim() + "/chat/messages", callback);
    }

    void sendBookingChatMessage(String bookingId, String message, String clientMessageId, JsonCallback callback) {
        if (bookingId == null || bookingId.trim().isEmpty() || message == null || message.trim().isEmpty()) {
            callback.onError("Message missing");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("message", message.trim());
            body.put("clientMessageId", clientMessageId == null ? "" : clientMessageId);
        } catch (Exception ignored) {
        }
        post("/bookings/" + bookingId.trim() + "/chat/messages", body, callback);
    }

    void markBookingChatSeen(String bookingId) {
        if (bookingId == null || bookingId.trim().isEmpty()) {
            return;
        }
        patch("/bookings/" + bookingId.trim() + "/chat/seen", new JSONObject(), silent());
    }

    void fetchMyReviews(JsonCallback callback) {
        get("/reviews/partner/me", callback);
    }

    void disputeReview(String reviewId, String reason, String details, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("reason", reason == null || reason.trim().isEmpty() ? "other" : reason.trim());
            body.put("details", details == null ? "" : details.trim());
        } catch (Exception ignored) {
        }
        post("/reviews/" + reviewId + "/dispute", body, callback);
    }

    void uploadPartnerDocument(String documentType, File file, String aadhaarLast4, boolean compressedByClient, long originalSizeBytes, JsonCallback callback) {
        if (file == null || !file.exists()) {
            callback.onError("Document file missing");
            return;
        }
        getIdToken(new TokenCallback() {
            @Override
            public void onToken(String token) {
                executeDocumentUploadWithFallback(documentType, file, aadhaarLast4, compressedByClient, originalSizeBytes, token, callback, 0, null);
            }

            @Override
            public void onError(String message) {
                main.post(() -> callback.onError(message));
            }
        });
    }

    void uploadPartnerProfilePhoto(File file, JsonCallback callback) {
        if (file == null || !file.exists()) {
            callback.onError("Profile photo file missing");
            return;
        }
        getIdToken(new TokenCallback() {
            @Override
            public void onToken(String token) {
                executeSingleImageUploadWithFallback("/partners/profile-photo", "photo", file, token, callback == null ? silent() : callback, 0, null);
            }

            @Override
            public void onError(String message) {
                main.post(() -> (callback == null ? silent() : callback).onError(message));
            }
        });
    }

    static final class LocationPayload {
        final double lat;
        final double lng;
        final float accuracy;
        final String provider;
        final boolean isMock;
        final long recordedAtMillis;

        LocationPayload(double lat, double lng, float accuracy, String provider, boolean isMock, long recordedAtMillis) {
            this.lat = lat;
            this.lng = lng;
            this.accuracy = accuracy;
            this.provider = provider;
            this.isMock = isMock;
            this.recordedAtMillis = recordedAtMillis;
        }
    }

    void createCallLog(String bookingId, String action, String reason, JsonCallback callback) {
        createCallLog(bookingId, action, reason, "primary", callback);
    }

    void createCallLog(String bookingId, String action, String reason, String contactType, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("action", action == null ? "start" : action);
            body.put("reason", reason == null ? "" : reason);
            body.put("contactType", contactType == null ? "primary" : contactType);
        } catch (Exception ignored) {
        }
        post("/bookings/" + bookingId + "/calls", body, callback);
    }

    void reportCustomerNoResponse(String bookingId, String reason, LocationPayload location, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("reason", reason == null ? "" : reason);
            body.put("evidenceUrl", "");
            if (location != null) {
                body.put("lat", location.lat);
                body.put("lng", location.lng);
                body.put("accuracy", location.accuracy);
                body.put("provider", location.provider == null ? "" : location.provider);
                body.put("isMock", location.isMock);
                body.put("recordedAt", location.recordedAtMillis);
            }
        } catch (Exception ignored) {
        }
        post("/bookings/" + bookingId + "/no-response-report", body, callback);
    }

    void downloadStatement(String fromDate, String toDate, PdfCallback callback) {
        StringBuilder path = new StringBuilder("/partner/get-statement");
        List<String> query = new ArrayList<>();
        if (fromDate != null && !fromDate.trim().isEmpty()) {
            query.add("from=" + fromDate.trim());
        }
        if (toDate != null && !toDate.trim().isEmpty()) {
            query.add("to=" + toDate.trim());
        }
        if (!query.isEmpty()) {
            path.append("?").append(String.join("&", query));
        }
        getIdToken(new TokenCallback() {
            @Override
            public void onToken(String token) {
                executePdfWithFallback(path.toString(), token, callback, 0, null);
            }

            @Override
            public void onError(String message) {
                main.post(() -> callback.onError(message));
            }
        });
    }

    void fetchPartnerAppControl(JsonCallback callback) {
        get("/app-control/config?app=partner&platform=android", callback);
    }

    void connectProfile(String name, String phone, String email, Set<String> skills, boolean online, int radiusKm, String area, String photoUrl) {
        upsertPartnerProfile(name, phone, email, skills, online, radiusKm, area, "", photoUrl, silent());
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null && !task.getResult().trim().isEmpty()) {
                saveFcmToken(task.getResult());
            }
        });
    }

    void getIdToken(TokenCallback callback) {
        if (isLocalDevelopmentBackend()) {
            callback.onToken("local-development");
            return;
        }
        if (auth.getCurrentUser() != null) {
            auth.getCurrentUser().getIdToken(false)
                    .addOnSuccessListener(result -> {
                        cachedToken = result.getToken();
                        callback.onToken(cachedToken);
                    })
                    .addOnFailureListener(error -> {
                        if (!cachedToken.isEmpty()) {
                            callback.onToken(cachedToken);
                            return;
                        }
                        retryTokenAfterFirebaseError(callback, error == null ? "Firebase session unavailable" : error.getMessage());
                    });
            return;
        }
        callback.onError("AUTH_SESSION_MISSING: Please sign in again.");
    }

    void refreshAuthentication(TokenCallback callback) {
        cachedToken = "";
        if (isLocalDevelopmentBackend()) {
            callback.onToken("local-development");
            return;
        }
        if (auth.getCurrentUser() == null) {
            callback.onError("AUTH_SESSION_MISSING: Please sign in again.");
            return;
        }
        refreshTokenSingleFlight(callback);
    }

    private void retryTokenAfterFirebaseError(TokenCallback callback, String firstError) {
        if (auth.getCurrentUser() != null) {
            refreshTokenSingleFlight(new TokenCallback() {
                @Override public void onToken(String token) { callback.onToken(token); }
                @Override public void onError(String message) { callback.onError(nonEmpty(message, firstError)); }
            });
            return;
        }
        callback.onError("AUTH_SESSION_MISSING: Please sign in again.");
    }

    private void refreshTokenSingleFlight(TokenCallback callback) {
        synchronized (tokenRefreshLock) {
            tokenRefreshWaiters.add(callback);
            if (tokenRefreshInFlight) return;
            tokenRefreshInFlight = true;
        }
        if (auth.getCurrentUser() == null) {
            finishTokenRefresh("", "AUTH_SESSION_MISSING: Please sign in again.");
            return;
        }
        auth.getCurrentUser().getIdToken(true)
                .addOnSuccessListener(result -> finishTokenRefresh(result.getToken(), ""))
                .addOnFailureListener(error -> finishTokenRefresh("", nonEmpty(error.getMessage(), "Firebase Auth token unavailable")));
    }

    private void finishTokenRefresh(String token, String error) {
        List<TokenCallback> callbacks;
        synchronized (tokenRefreshLock) {
            callbacks = new ArrayList<>(tokenRefreshWaiters);
            tokenRefreshWaiters.clear();
            tokenRefreshInFlight = false;
            cachedToken = token == null ? "" : token;
        }
        for (TokenCallback callback : callbacks) {
            if (!cachedToken.isEmpty()) callback.onToken(cachedToken);
            else callback.onError(error);
        }
    }

    private String nonEmpty(String primary, String fallback) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary;
        }
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback;
        }
        return "Firebase Auth token unavailable";
    }

    private void get(String path, JsonCallback callback) {
        getIdToken(new TokenCallback() {
            @Override
            public void onToken(String token) {
                executeWithFallback("GET", path, null, token, callback, 0, null);
            }

            @Override
            public void onError(String message) {
                main.post(() -> callback.onError(message));
            }
        });
    }

    private void post(String path, JSONObject body, JsonCallback callback) {
        requestWithBody("POST", path, body, callback);
    }

    private void publicPost(String path, JSONObject body, JsonCallback callback) {
        executePublicWithFallback(path, body, callback, 0, null, 0);
    }

    private void patch(String path, JSONObject body, JsonCallback callback) {
        requestWithBody("PATCH", path, body, callback);
    }

    private void put(String path, JSONObject body, JsonCallback callback) {
        requestWithBody("PUT", path, body, callback);
    }

    private void delete(String path, JSONObject body, JsonCallback callback) {
        requestWithBody("DELETE", path, body, callback);
    }

    private void requestWithBody(String method, String path, JSONObject body, JsonCallback callback) {
        getIdToken(new TokenCallback() {
            @Override
            public void onToken(String token) {
                executeWithFallback(method, path, body, token, callback, 0, null);
            }

            @Override
            public void onError(String message) {
                main.post(() -> callback.onError(message));
            }
        });
    }

    private void executePublicWithFallback(String path, JSONObject body, JsonCallback callback, int index, String lastError, int retryAttempt) {
        if (index >= baseUrls.size()) {
            String message = lastError == null || lastError.trim().isEmpty() ? "backend not reachable" : lastError;
            main.post(() -> callback.onError(message));
            return;
        }
        String candidateBaseUrl = baseUrlForAttempt(index);
        Request request = new Request.Builder()
                .url(candidateBaseUrl + path)
                .post(RequestBody.create((body == null ? new JSONObject() : body).toString(), JSON))
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (retryAttempt < MAX_TRANSIENT_RETRIES) {
                    long delayMs = 450L * (retryAttempt + 1) * (retryAttempt + 1);
                    main.postDelayed(() -> executePublicWithFallback(path, body, callback, index, "Network temporarily unavailable", retryAttempt + 1), delayMs);
                    return;
                }
                executePublicWithFallback(path, body, callback, index + 1, "Network temporarily unavailable", 0);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() == null ? "{}" : response.body().string();
                if (!response.isSuccessful()) {
                    int responseCode = response.code();
                    if (isTransientHttp(responseCode) && retryAttempt < MAX_TRANSIENT_RETRIES) {
                        long delayMs = 450L * (retryAttempt + 1) * (retryAttempt + 1);
                        main.postDelayed(() -> executePublicWithFallback(path, body, callback, index, safeHttpError(responseCode, text), retryAttempt + 1), delayMs);
                        return;
                    }
                    main.post(() -> callback.onError(safeHttpError(responseCode, text)));
                    return;
                }
                activeBaseUrl = candidateBaseUrl;
                try {
                    JSONObject object = new JSONObject(text);
                    main.post(() -> callback.onSuccess(object));
                } catch (Exception error) {
                    main.post(() -> callback.onError(error.getMessage()));
                }
            }
        });
    }

    private void executeWithFallback(String method, String path, JSONObject body, String token, JsonCallback callback, int index, String lastError) {
        executeWithFallback(method, path, body, token, callback, index, lastError, 0);
    }

    private void executeWithFallback(String method, String path, JSONObject body, String token, JsonCallback callback, int index, String lastError, int retryAttempt) {
        executeWithFallback(method, path, body, token, callback, index, lastError, retryAttempt, false);
    }

    private void executeWithFallback(String method, String path, JSONObject body, String token, JsonCallback callback, int index, String lastError, int retryAttempt, boolean authRetried) {
        if (index >= baseUrls.size()) {
            String message = lastError == null || lastError.trim().isEmpty() ? "backend not reachable" : lastError;
            main.post(() -> callback.onError(message));
            return;
        }
        String candidateBaseUrl = baseUrlForAttempt(index);
        Request.Builder builder = new Request.Builder()
                .url(candidateBaseUrl + path);
        addAuthenticationHeaders(builder, token);
        if ("GET".equals(method)) {
            builder.get();
        } else {
            RequestBody requestBody = RequestBody.create((body == null ? new JSONObject() : body).toString(), JSON);
            if ("PATCH".equals(method)) {
                builder.patch(requestBody);
            } else if ("PUT".equals(method)) {
                builder.put(requestBody);
            } else if ("DELETE".equals(method)) {
                builder.delete(requestBody);
            } else {
                builder.post(requestBody);
            }
        }
        execute(builder.build(), candidateBaseUrl, method, path, body, token, callback, index, retryAttempt, authRetried);
    }

    private void execute(Request request, String candidateBaseUrl, String method, String path, JSONObject body, String token, JsonCallback callback, int index, int retryAttempt, boolean authRetried) {
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (retryAttempt < MAX_TRANSIENT_RETRIES) {
                    retryJsonRequest(method, path, body, token, callback, index, e.getMessage(), retryAttempt + 1, authRetried);
                    return;
                }
                executeWithFallback(method, path, body, token, callback, index + 1, "Network temporarily unavailable");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() == null ? "{}" : response.body().string();
                if (!response.isSuccessful()) {
                    int responseCode = response.code();
                    if (isAuthHttp(responseCode) && canRefreshAuthToken(token) && !authRetried) {
                        retryTokenAfterFirebaseError(new TokenCallback() {
                            @Override
                            public void onToken(String freshToken) {
                                executeWithFallback(method, path, body, freshToken, callback, index, "Authentication refreshed", 0, true);
                            }

                            @Override
                            public void onError(String message) {
                                main.post(() -> callback.onError(safeHttpError(responseCode, text)));
                            }
                        }, text);
                        return;
                    }
                    if (isTransientHttp(responseCode) && retryAttempt < MAX_TRANSIENT_RETRIES) {
                        retryJsonRequest(method, path, body, token, callback, index, "HTTP " + responseCode, retryAttempt + 1, authRetried);
                        return;
                    }
                    main.post(() -> callback.onError(safeHttpError(responseCode, text)));
                    return;
                }
                activeBaseUrl = candidateBaseUrl;
                try {
                    JSONObject object = new JSONObject(text);
                    main.post(() -> callback.onSuccess(object));
                } catch (Exception error) {
                    main.post(() -> callback.onError(error.getMessage()));
                }
            }
        });
    }

    private void retryJsonRequest(String method, String path, JSONObject body, String token, JsonCallback callback, int index, String lastError, int nextAttempt, boolean authRetried) {
        long delayMs = 450L * nextAttempt * nextAttempt;
        main.postDelayed(() -> executeWithFallback(method, path, body, token, callback, index, lastError, nextAttempt, authRetried), delayMs);
    }

    private boolean isAuthHttp(int code) {
        return AuthHttpPolicy.isAuthenticationFailure(code);
    }

    private boolean canRefreshAuthToken(String token) {
        return !isLocalDevelopmentBackend()
                && auth.getCurrentUser() != null
                && !LOCAL_DEVICE_TOKEN.equals(token)
                && !"local-development".equals(token);
    }

    private boolean isTransientHttp(int code) {
        return code == 408 || code == 425 || code == 429 || (code >= 500 && code <= 599);
    }

    private String safeHttpError(int code, String body) {
        String message = "";
        try {
            JSONObject object = new JSONObject(body == null ? "{}" : body);
            message = object.optString("message", "");
        } catch (Exception ignored) {
        }
        if (message == null || message.trim().isEmpty()) {
            if (code == 401) {
                message = "Unauthorized. Please login again.";
            } else if (code == 403) {
                message = "You are signed in, but this action is not allowed.";
            } else if (code == 409) {
                message = "Request conflicts with latest booking state.";
            } else if (code == 413) {
                message = "File too large.";
            } else if (code == 415) {
                message = "Unsupported file type.";
            } else if (code >= 500) {
                message = "Server temporarily unavailable.";
            } else {
                message = "Request failed.";
            }
        }
        return "HTTP " + code + ": " + message;
    }

    private void executePdfWithFallback(String path, String token, PdfCallback callback, int index, String lastError) {
        if (index >= baseUrls.size()) {
            String message = lastError == null || lastError.trim().isEmpty() ? "Statement download failed" : lastError;
            main.post(() -> callback.onError(message));
            return;
        }
        String candidateBaseUrl = baseUrlForAttempt(index);
        Request request = new Request.Builder()
                .url(candidateBaseUrl + path)
                .addHeader("Authorization", "Bearer " + token)
                .get()
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                executePdfWithFallback(path, token, callback, index + 1, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String text = response.body() == null ? "" : response.body().string();
                    main.post(() -> callback.onError(statementErrorMessage(response.code(), text)));
                    return;
                }
                activeBaseUrl = candidateBaseUrl;
                String fileName = fileNameFromDisposition(response.header("Content-Disposition"));
                long contentLength = response.body() == null ? 0L : response.body().contentLength();
                if (contentLength > MAX_PDF_BYTES) {
                    main.post(() -> callback.onError("Statement is too large to open safely on this device"));
                    return;
                }
                byte[] bytes = readAllBytes(response.body() == null ? null : response.body().byteStream(), MAX_PDF_BYTES);
                main.post(() -> callback.onSuccess(bytes, fileName));
            }
        });
    }

    private void executeDocumentUploadWithFallback(String documentType, File file, String aadhaarLast4, boolean compressedByClient, long originalSizeBytes, String token, JsonCallback callback, int index, String lastError) {
        if (index >= baseUrls.size()) {
            String message = lastError == null || lastError.trim().isEmpty() ? "Document upload failed" : lastError;
            main.post(() -> callback.onError(message));
            return;
        }
        String candidateBaseUrl = baseUrlForAttempt(index);
        RequestBody fileBody = RequestBody.create(file, MediaType.get(imageMimeType(file)));
        MultipartBody.Builder multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("documentType", documentType == null ? "id_proof" : documentType)
                .addFormDataPart("compressedByClient", String.valueOf(compressedByClient))
                .addFormDataPart("originalSizeBytes", String.valueOf(Math.max(originalSizeBytes, file.length())))
                .addFormDataPart("document", file.getName(), fileBody);
        if (aadhaarLast4 != null && !aadhaarLast4.trim().isEmpty()) {
            multipart.addFormDataPart("aadhaarLast4", aadhaarLast4.trim());
        }
        Request request = new Request.Builder()
                .url(candidateBaseUrl + "/partners/documents")
                .addHeader("Authorization", "Bearer " + token)
                .post(multipart.build())
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                executeDocumentUploadWithFallback(documentType, file, aadhaarLast4, compressedByClient, originalSizeBytes, token, callback, index + 1, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() == null ? "{}" : response.body().string();
                if (!response.isSuccessful()) {
                    main.post(() -> callback.onError(safeHttpError(response.code(), text)));
                    return;
                }
                activeBaseUrl = candidateBaseUrl;
                try {
                    JSONObject object = new JSONObject(text);
                    main.post(() -> callback.onSuccess(object));
                } catch (Exception error) {
                    main.post(() -> callback.onError(error.getMessage()));
                }
            }
        });
    }

    private void executeSingleImageUploadWithFallback(String path, String fieldName, File file, String token, JsonCallback callback, int index, String lastError) {
        if (index >= baseUrls.size()) {
            String message = lastError == null || lastError.trim().isEmpty() ? "Image upload failed" : lastError;
            main.post(() -> callback.onError(message));
            return;
        }
        String candidateBaseUrl = baseUrlForAttempt(index);
        RequestBody fileBody = RequestBody.create(file, MediaType.get(imageMimeType(file)));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(fieldName, file.getName(), fileBody)
                .build();
        Request request = new Request.Builder()
                .url(candidateBaseUrl + path)
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                executeSingleImageUploadWithFallback(path, fieldName, file, token, callback, index + 1, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() == null ? "{}" : response.body().string();
                if (!response.isSuccessful()) {
                    main.post(() -> callback.onError(safeHttpError(response.code(), text)));
                    return;
                }
                activeBaseUrl = candidateBaseUrl;
                try {
                    JSONObject object = new JSONObject(text);
                    main.post(() -> callback.onSuccess(object));
                } catch (Exception error) {
                    main.post(() -> callback.onError(error.getMessage()));
                }
            }
        });
    }

    private void executeLaundryStaffIdentityUpload(String path, File file, String idType, String token, JsonCallback callback, int index, String lastError) {
        if (index >= baseUrls.size()) {
            String message = lastError == null || lastError.trim().isEmpty() ? "Government ID upload failed" : lastError;
            main.post(() -> callback.onError(message));
            return;
        }
        String candidateBaseUrl = baseUrlForAttempt(index);
        RequestBody fileBody = RequestBody.create(file, MediaType.get(imageMimeType(file)));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("idType", idType == null || idType.trim().isEmpty() ? "Government ID" : idType.trim())
                .addFormDataPart("document", file.getName(), fileBody)
                .build();
        Request request = new Request.Builder()
                .url(candidateBaseUrl + path)
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                executeLaundryStaffIdentityUpload(path, file, idType, token, callback, index + 1, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() == null ? "{}" : response.body().string();
                if (!response.isSuccessful()) {
                    main.post(() -> callback.onError(safeHttpError(response.code(), text)));
                    return;
                }
                activeBaseUrl = candidateBaseUrl;
                try {
                    JSONObject object = new JSONObject(text);
                    main.post(() -> callback.onSuccess(object));
                } catch (Exception error) {
                    main.post(() -> callback.onError(error.getMessage()));
                }
            }
        });
    }

    private String imageMimeType(File file) {
        if (file == null || !file.exists()) {
            return "image/jpeg";
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] header = new byte[12];
            int read = input.read(header);
            if (read >= 4
                    && header[0] == 0x25
                    && header[1] == 0x50
                    && header[2] == 0x44
                    && header[3] == 0x46) {
                return "application/pdf";
            }
            if (read >= 8
                    && (header[0] & 0xff) == 0x89
                    && header[1] == 0x50
                    && header[2] == 0x4e
                    && header[3] == 0x47
                    && header[4] == 0x0d
                    && header[5] == 0x0a
                    && header[6] == 0x1a
                    && header[7] == 0x0a) {
                return "image/png";
            }
        } catch (Exception ignored) {
        }
        return "image/jpeg";
    }

    private byte[] readAllBytes(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            if (buffer.size() > maxBytes - read) {
                throw new IOException("Downloaded file exceeds the safe device memory limit");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private String fileNameFromDisposition(String disposition) {
        if (disposition == null) {
            return "apnaservo-job-statement.pdf";
        }
        String marker = "filename=\"";
        int start = disposition.indexOf(marker);
        if (start >= 0) {
            int end = disposition.indexOf("\"", start + marker.length());
            if (end > start) {
                return disposition.substring(start + marker.length(), end);
            }
        }
        return "apnaservo-job-statement.pdf";
    }

    private String statementErrorMessage(int code, String body) {
        try {
            JSONObject object = new JSONObject(body == null ? "{}" : body);
            String message = object.optString("message", "");
            if (!message.isEmpty()) {
                return message;
            }
        } catch (Exception ignored) {
        }
        if (code == 404) {
            return "No completed jobs found";
        }
        if (code == 401 || code == 403) {
            return "Unauthorized partner";
        }
        return "PDF generation failed";
    }

    private JsonCallback silent() {
        return new JsonCallback() {
            @Override public void onSuccess(JSONObject object) {}
            @Override public void onError(String message) {}
        };
    }

    boolean isLocalDevelopmentBackend() {
        return activeBaseUrl.startsWith("http://127.0.0.1")
                || activeBaseUrl.startsWith("http://10.0.2.2")
                || activeBaseUrl.startsWith("http://localhost");
    }

    String getDevUid() {
        return devUid;
    }

    private void addAuthenticationHeaders(Request.Builder builder, String token) {
        builder.addHeader("Authorization", "Bearer " + token);
        if (isLocalDevelopmentBackend() || LOCAL_DEVICE_TOKEN.equals(token) || "local-development".equals(token)) {
            builder.addHeader("X-ApnaServo-Dev-Uid", devUid);
            builder.addHeader("X-ApnaServo-Dev-Role", "partner");
        }
    }

    private String sanitizeDeviceId(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^a-zA-Z0-9._:-]", "");
        return cleaned.length() >= 6 ? cleaned : "android-device";
    }

    private List<String> buildBaseUrls() {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        addSecureUrl(urls, BuildConfig.APNA_SERVO_API_BASE_URL);
        addSecureUrl(urls, BuildConfig.APNA_SERVO_API_BACKUP_URL);
        if (urls.isEmpty()) {
            urls.add("https://apnaservobk-98gz.onrender.com/api");
        }
        return new ArrayList<>(urls);
    }

    /** Active endpoint goes first, then every remaining configured endpoint exactly once. */
    private String baseUrlForAttempt(int index) {
        if (index == 0) return activeBaseUrl;
        int remainingIndex = index - 1;
        for (String configured : baseUrls) {
            if (configured.equals(activeBaseUrl)) continue;
            if (remainingIndex-- == 0) return configured;
        }
        return "";
    }

    private void addSecureUrl(LinkedHashSet<String> urls, String rawUrl) {
        if (rawUrl == null) {
            return;
        }
        String url = rawUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.startsWith("https://") || url.startsWith("http://")) {
            urls.add(url);
        }
    }

    interface TokenCallback {
        void onToken(String token);
        void onError(String message);
    }
}
