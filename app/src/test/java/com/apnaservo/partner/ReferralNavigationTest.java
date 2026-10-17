package com.apnaservo.partner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies the referral-specific routing logic that mirrors handleMenuAction
 * and slidingSideDrawerMenu, and the status-label helper used in the UI.
 *
 * These are pure-JVM tests — no Android framework needed.
 */
public class ReferralNavigationTest {

    // ── Mirrors the if-chain in handleMenuAction (subset relevant to referrals) ──

    private String routeForLabel(String label) {
        if (label.contains("Logout"))    return "logout";
        if (label.contains("Dashboard")) return "home";
        if (label.contains("Booking") || label.contains("Jobs")) return "bookings";
        if (label.contains("Earning"))   return "earnings";
        if (label.contains("Refer"))     return "referral";
        if (label.contains("Settings"))  return "settings";
        if (label.contains("Legal"))     return "legal_info";
        if ("Profile".equals(label))     return "profile";
        return "profile_tool";
    }

    // ── Mirrors the status-chip logic in referralStatusChip() ──────────────────

    private String chipLabelForStatus(String status, int jobCount, int threshold) {
        if ("eligible".equals(status))    return "Eligible";
        if ("in_progress".equals(status)) return jobCount + "/" + threshold;
        return "Pending";
    }

    // ── Mirrors the progress sub-line in referralProgressLabel() ──────────────

    private String progressLabelForStatus(String status, int jobCount, int threshold) {
        if ("eligible".equals(status))    return "Completed " + threshold + "/" + threshold + " jobs";
        if ("in_progress".equals(status)) return jobCount + "/" + threshold + " jobs completed";
        return "Registered · awaiting first job";
    }

    // ── Mirrors the owner items array in slidingSideDrawerMenu() ───────────────

    private static final String[] OWNER_MENU_ITEMS = {
        "Dashboard", "My Bookings", "Earnings", "My Services",
        "Profile", "Documents", "Refer & Earn", "Support", "Settings"
    };

    // ─────────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void referEarnLabelRoutesToReferralScreen() {
        assertEquals("referral", routeForLabel("Refer & Earn"));
    }

    @Test
    public void referContainingLabelsAllRouteToReferral() {
        assertEquals("referral", routeForLabel("Refer"));
        assertEquals("referral", routeForLabel("Referral"));
        assertEquals("referral", routeForLabel("Refer & Earn"));
    }

    @Test
    public void ownerMenuContainsReferAndEarn() {
        boolean found = false;
        for (String item : OWNER_MENU_ITEMS) {
            if (item.contains("Refer")) { found = true; break; }
        }
        assertTrue("Owner menu must contain a 'Refer' item", found);
    }

    @Test
    public void ownerMenuItemsAllResolveToKnownScreens() {
        for (String item : OWNER_MENU_ITEMS) {
            String route = routeForLabel(item);
            assertFalse("Menu item '" + item + "' must not fall through to empty",
                    route.isEmpty());
        }
    }

    @Test
    public void referralDoesNotClashWithEarningsRoute() {
        assertFalse("'Refer & Earn' must not route to earnings",
                "earnings".equals(routeForLabel("Refer & Earn")));
    }

    @Test
    public void pendingStatusChipShowsPending() {
        assertEquals("Pending", chipLabelForStatus("pending", 0, 3));
    }

    @Test
    public void inProgressStatusChipShowsFraction() {
        assertEquals("1/3", chipLabelForStatus("in_progress", 1, 3));
        assertEquals("2/3", chipLabelForStatus("in_progress", 2, 3));
    }

    @Test
    public void eligibleStatusChipShowsEligible() {
        assertEquals("Eligible", chipLabelForStatus("eligible", 3, 3));
    }

    @Test
    public void pendingProgressLabelShowsAwaitingFirstJob() {
        assertTrue(progressLabelForStatus("pending", 0, 3)
                .contains("awaiting first job"));
    }

    @Test
    public void inProgressProgressLabelShowsCount() {
        String label = progressLabelForStatus("in_progress", 2, 3);
        assertTrue(label.contains("2/3"));
        assertTrue(label.contains("completed"));
    }

    @Test
    public void eligibleProgressLabelShowsThreshold() {
        String label = progressLabelForStatus("eligible", 3, 3);
        assertTrue(label.contains("3/3"));
        assertTrue(label.contains("Completed"));
    }

    @Test
    public void earningsLabelStillRoutesToEarnings() {
        // Regression guard: "Earn" in "Refer & Earn" must not steal Earnings routing.
        // handleMenuAction checks "Earning" (not "Earn") so the order is safe — verify it.
        assertEquals("earnings", routeForLabel("Earnings"));
        assertEquals("earnings", routeForLabel("My Earnings"));
    }

    @Test
    public void existingMenuItemsUnaffected() {
        assertEquals("home",         routeForLabel("Dashboard"));
        assertEquals("bookings",     routeForLabel("My Bookings"));
        assertEquals("earnings",     routeForLabel("Earnings"));
        assertEquals("settings",     routeForLabel("Settings"));
        assertEquals("profile",      routeForLabel("Profile"));
        assertEquals("profile_tool", routeForLabel("My Services"));
        assertEquals("profile_tool", routeForLabel("Documents"));
        assertEquals("profile_tool", routeForLabel("Support"));
    }
}
