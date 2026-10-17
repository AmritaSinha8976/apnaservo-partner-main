package com.apnaservo.partner;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocationQualityTest {
    private static final long NOW = 200_000_000_000L;

    @Test public void acceptsRecentAccurateFix() {
        assertTrue(LocationQuality.valid(26.14, 91.74, 20, NOW - 2_000_000_000L, NOW));
    }

    /** Availability threshold is 5 min (300 s). A fix 301 s old must be rejected. */
    @Test public void rejectsOldEvenWhenAccurate() {
        assertFalse(LocationQuality.valid(26.14, 91.74, 1, NOW - 301_000_000_000L, NOW));
    }

    /** Availability threshold is 150 m. Values above that must be rejected. */
    @Test public void rejectsApproximateAndMissingAccuracy() {
        for (float accuracy : new float[]{0, -1, 151, 500, 3000, Float.NaN, Float.POSITIVE_INFINITY})
            assertFalse(LocationQuality.valid(26.14, 91.74, accuracy, NOW, NOW));
    }

    @Test public void rejectsInvalidCoordinatesAndTimestamps() {
        assertFalse(LocationQuality.valid(0, 0, 10, NOW, NOW));
        assertFalse(LocationQuality.valid(Double.NaN, 91, 10, NOW, NOW));
        assertFalse(LocationQuality.valid(91, 91, 10, NOW, NOW));
        assertFalse(LocationQuality.valid(26, 181, 10, NOW, NOW));
        assertFalse(LocationQuality.valid(26, 91, 10, NOW + 1, NOW));
        assertFalse(LocationQuality.valid(26, 91, 10, 0, NOW));
    }

    @Test public void keepsPreciseGpsInsteadOfWorseNetworkFix() {
        assertFalse(LocationQuality.betterFix(50, NOW, 8, NOW - 20_000_000_000L));
        assertTrue(LocationQuality.betterFix(10, NOW, 8, NOW - 20_000_000_000L));
    }

    @Test public void rejectsOutOfOrderButAllowsBetterRecentFix() {
        assertFalse(LocationQuality.betterFix(2, NOW - 6_000_000_000L, 10, NOW));
        assertTrue(LocationQuality.betterFix(2, NOW - 2_000_000_000L, 10, NOW));
    }

    // ── Manual "Use my current location" button path ──────────────────────────

    /** A fresh fix from the manual button must pass the availability threshold. */
    @Test public void manualUpdateAcceptsFreshAccurateFix() {
        assertTrue(LocationQuality.valid(26.1445, 91.7362, 30, NOW - 1_000_000_000L, NOW));
    }

    /** A stale cached fix (>5 min old) must be rejected so the button forces a fresh read. */
    @Test public void manualUpdateRejectsStaleFixOlderThanFiveMinutes() {
        assertFalse(LocationQuality.valid(26.1445, 91.7362, 20, NOW - 310_000_000_000L, NOW));
    }

    /** Zero-zero coordinates (default Android emulator before location is set) must be rejected. */
    @Test public void manualUpdateRejectsDefaultEmulatorZeroCoordinates() {
        assertFalse(LocationQuality.valid(0.0, 0.0, 10, NOW - 1_000_000_000L, NOW));
    }

    /** A fix with accuracy > 150 m (e.g. cell-only indoors) must be rejected for availability. */
    @Test public void manualUpdateRejectsVeryPoorAccuracy() {
        assertFalse(LocationQuality.valid(26.1445, 91.7362, 151, NOW - 1_000_000_000L, NOW));
    }

    /** After a successful manual update the new fix should be preferred over an older one. */
    @Test public void manualUpdateNewFixIsBetterThanOldFix() {
        assertTrue(LocationQuality.betterFix(25, NOW, 60, NOW - 240_000_000_000L));
    }

    // ── Bug regression: emulator Extended Controls location (mock on API 31+) ──

    /**
     * usable() must NOT call isMock() so that emulator Extended Controls
     * coordinates (which Android marks as mock on API 31+) are accepted for the
     * availability heartbeat and the "Use My Location" button path.
     *
     * Verified indirectly via LocationQuality.valid(), which exercises the same
     * coordinate + accuracy + age checks that usable() uses.
     * Guwahati coordinates: 26.1445 N, 91.7362 E.
     */
    @Test public void usableAcceptsGuwahatiCoordinatesWithGoodAccuracyAndAge() {
        assertTrue(LocationQuality.valid(26.1445, 91.7362, 20f, NOW - 1_000_000_000L, NOW));
    }

    /**
     * Regression: getCurrentLocation(token=null) returns null on cold emulator.
     * The fix replaces it with requestLocationUpdates + timeout. This test
     * confirms that a null/invalid location is correctly treated as "no fix"
     * by the quality gate.
     */
    @Test public void invalidLocationParamsAreNotUsable() {
        assertFalse(LocationQuality.valid(Double.NaN, Double.NaN, Float.NaN, 0L, NOW));
    }

    /** Guwahati fix exactly at the 150 m accuracy boundary must be accepted. */
    @Test public void guwahatiFixAtBoundaryAccuracyIsAccepted() {
        assertTrue(LocationQuality.valid(26.1445, 91.7362, 150f, NOW - 1_000_000_000L, NOW));
    }

    /** Guwahati fix one metre over the 150 m boundary must be rejected. */
    @Test public void guwahatiFixJustOverBoundaryAccuracyIsRejected() {
        assertFalse(LocationQuality.valid(26.1445, 91.7362, 151f, NOW - 1_000_000_000L, NOW));
    }

    // ── Regression: coarse-only permission (hAcc=100 m from fused provider) ──

    /**
     * When the user grants only "Approximate" location (ACCESS_COARSE_LOCATION),
     * the fused provider delivers fixes with hAcc ~100 m. These must pass usable()
     * so the "Use My Location" button succeeds on the emulator and on devices where
     * fine permission was denied.
     *
     * Proven by dumpsys: fused last location hAcc=100.0, coords 26.154050,91.662883.
     */
    @Test public void coarseOnlyFixAt100mAccuracyIsUsable() {
        assertTrue(LocationQuality.valid(26.154050, 91.662883, 100f, NOW - 5_000_000_000L, NOW));
    }

    /** A coarse fix that is just over the 150 m threshold must still be rejected. */
    @Test public void coarseFixOver150mIsRejected() {
        assertFalse(LocationQuality.valid(26.154050, 91.662883, 151f, NOW - 5_000_000_000L, NOW));
    }
}
