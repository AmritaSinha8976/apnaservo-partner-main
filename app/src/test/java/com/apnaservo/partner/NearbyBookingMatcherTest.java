package com.apnaservo.partner;

import org.junit.Test;

import static org.junit.Assert.*;

public class NearbyBookingMatcherTest {
    @Test
    public void matchesBookingNearPartnerUsingServiceCoordinates() {
        assertTrue(NearbyBookingMatcher.matches(26.1445, 91.7362, 26.15, 91.74, 25));
    }

    @Test
    public void doesNotMatchBookingNearOldHardCodedPartnerLocationWhenPartnerIsElsewhere() {
        assertFalse(NearbyBookingMatcher.matches(28.6139, 77.2090, 26.1445, 91.7362, 25));
    }

    @Test
    public void rejectsMissingOrInvalidServiceCoordinates() {
        assertFalse(NearbyBookingMatcher.matches(26.1445, 91.7362, 0, 0, 25));
        assertFalse(NearbyBookingMatcher.matches(26.1445, 91.7362, Double.NaN, 91.7362, 25));
        assertFalse(NearbyBookingMatcher.matches(26.1445, 91.7362, 91, 91.7362, 25));
    }

    @Test
    public void rejectsMissingOrInvalidPartnerCoordinatesAndRadius() {
        assertFalse(NearbyBookingMatcher.matches(0, 0, 26.15, 91.74, 25));
        assertFalse(NearbyBookingMatcher.matches(Double.NaN, 91.7362, 26.15, 91.74, 25));
        assertFalse(NearbyBookingMatcher.matches(26.1445, 91.7362, 26.15, 91.74, 0));
    }

    @Test
    public void enforcesNearbyRadius() {
        assertTrue(NearbyBookingMatcher.matches(26.1445, 91.7362, 26.15, 91.74, 2));
        assertFalse(NearbyBookingMatcher.matches(26.1445, 91.7362, 26.15, 91.74, 0.1));
    }

    // ── Manual location-update integration ───────────────────────────────────────

    /**
     * After the partner taps "Use my current location" and the backend stores the
     * new coordinates, the matching engine must use those coordinates — not any
     * previously cached or hard-coded value.
     *
     * Scenario: partner was previously at (0,0) (no location), then updates to
     * Guwahati (26.1445, 91.7362). A booking 1.2 km away must now match.
     */
    @Test
    public void updatedPartnerLocationIsUsedForNearbyMatching() {
        // Before update: no valid partner location — must not match
        assertFalse(NearbyBookingMatcher.matches(0, 0, 26.1500, 91.7400, 8));

        // After manual update: partner is now at Guwahati coordinates
        double updatedLat = 26.1445;
        double updatedLng = 91.7362;
        // Booking ~1.2 km away — must match within 8 km radius
        assertTrue(NearbyBookingMatcher.matches(updatedLat, updatedLng, 26.1500, 91.7400, 8));
    }

    /**
     * A partner who manually updates their location to a different city must NOT
     * match bookings in the old city, preventing stale-location dispatch errors.
     */
    @Test
    public void partnerInGuwahatiDoesNotMatchBookingInDelhi() {
        double guwahatiLat = 26.1445;
        double guwahatiLng = 91.7362;
        double delhiBookingLat = 28.6139;
        double delhiBookingLng = 77.2090;
        assertFalse(NearbyBookingMatcher.matches(guwahatiLat, guwahatiLng, delhiBookingLat, delhiBookingLng, 8));
    }
}
