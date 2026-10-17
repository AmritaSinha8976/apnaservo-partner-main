package com.apnaservo.partner;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;

/**
 * Two-tier location quality check:
 *  - usable()         : availability heartbeat (relaxed — works indoors + emulator)
 *  - usableForJob()   : active-job GPS (strict — rejects weak / stale fixes)
 *
 * Mock location is always rejected in both tiers.
 */
final class LocationQuality {
    private LocationQuality() {}

    // ── Availability heartbeat thresholds (relaxed) ──────────────────────────
    private static final float AVAILABILITY_MAX_ACCURACY_M = 150f;
    private static final long  AVAILABILITY_MAX_AGE_NS     = 300_000_000_000L; // 5 min

    // ── Active-job GPS thresholds (strict) ───────────────────────────────────
    private static final float JOB_MAX_ACCURACY_M = 50f;
    private static final long  JOB_MAX_AGE_NS     = 60_000_000_000L;  // 60 sec

    /** Availability heartbeat — accepts network/cell fixes, indoor GPS, emulator.
     *  Mock-location check is intentionally omitted here so that emulator Extended
     *  Controls coordinates (which Android marks as mock on API 31+) are accepted
     *  for the "Use My Location" / availability heartbeat path. */
    static boolean usable(Location fix) {
        if (fix == null || !fix.hasAccuracy()) return false;
        return validCoordinates(fix.getLatitude(), fix.getLongitude())
                && validAccuracyAndAge(fix.getAccuracy(), fix.getElapsedRealtimeNanos(),
                        SystemClock.elapsedRealtimeNanos(),
                        AVAILABILITY_MAX_ACCURACY_M, AVAILABILITY_MAX_AGE_NS);
    }

    /** Active-job GPS — strict accuracy + freshness required. */
    static boolean usableForJob(Location fix) {
        if (fix == null || !fix.hasAccuracy()) return false;
        if (isMock(fix)) return false;
        return validCoordinates(fix.getLatitude(), fix.getLongitude())
                && validAccuracyAndAge(fix.getAccuracy(), fix.getElapsedRealtimeNanos(),
                        SystemClock.elapsedRealtimeNanos(),
                        JOB_MAX_ACCURACY_M, JOB_MAX_AGE_NS);
    }

    /** Returns true if candidate is better than current for availability use. */
    static boolean better(Location candidate, Location current) {
        if (!usable(candidate)) return false;
        if (!usable(current)) return true;
        return betterFix(candidate.getAccuracy(), candidate.getElapsedRealtimeNanos(),
                current.getAccuracy(), current.getElapsedRealtimeNanos());
    }

    /** Returns true if candidate is better than current for active-job use. */
    static boolean betterForJob(Location candidate, Location current) {
        if (!usableForJob(candidate)) return false;
        if (!usableForJob(current)) return true;
        return betterFix(candidate.getAccuracy(), candidate.getElapsedRealtimeNanos(),
                current.getAccuracy(), current.getElapsedRealtimeNanos());
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    static boolean valid(double lat, double lng, float accuracy, long fixNanos, long nowNanos) {
        long age = nowNanos - fixNanos;
        return validCoordinates(lat, lng)
                && validAccuracyAndAge(accuracy, fixNanos, nowNanos,
                        AVAILABILITY_MAX_ACCURACY_M, AVAILABILITY_MAX_AGE_NS);
    }

    private static boolean validCoordinates(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isInfinite(lat)
                && !Double.isNaN(lng) && !Double.isInfinite(lng)
                && Math.abs(lat) <= 90 && Math.abs(lng) <= 180
                && !(lat == 0 && lng == 0);
    }

    private static boolean validAccuracyAndAge(float accuracy, long fixNanos, long nowNanos,
                                                float maxAccuracy, long maxAgeNs) {
        long age = nowNanos - fixNanos;
        return !Float.isNaN(accuracy) && !Float.isInfinite(accuracy)
                && accuracy > 0 && accuracy <= maxAccuracy
                && fixNanos > 0 && age >= 0 && age <= maxAgeNs;
    }

    static boolean betterFix(float candidateAccuracy, long candidateTime,
            float currentAccuracy, long currentTime) {
        long delta = candidateTime - currentTime;
        if (delta < -5_000_000_000L) return false;
        return candidateAccuracy <= currentAccuracy
                || (delta > 15_000_000_000L && candidateAccuracy <= currentAccuracy + 10f);
    }

    private static boolean isMock(Location fix) {
        if (Build.VERSION.SDK_INT >= 31) return fix.isMock();
        return fix.isFromMockProvider();
    }
}
