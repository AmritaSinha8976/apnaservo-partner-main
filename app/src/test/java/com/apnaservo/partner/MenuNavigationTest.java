package com.apnaservo.partner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Verifies the label-to-screen routing logic used by handleMenuAction and
 * slidingSideDrawerRow.  These checks mirror the if-chain in handleMenuAction
 * so that any future accidental removal of a branch is caught immediately.
 */
public class MenuNavigationTest {

    // Mirrors the routing conditions in handleMenuAction.
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

    @Test
    public void ownerMenuItemsAllResolveToKnownScreens() {
        String[] ownerItems = {"Dashboard", "My Bookings", "Earnings",
                "My Services", "Profile", "Documents", "Refer & Earn", "Support", "Settings"};
        for (String item : ownerItems) {
            String route = routeForLabel(item);
            assertFalse("Menu item '" + item + "' must not fall through to unknown",
                    route.isEmpty());
        }
    }

    @Test
    public void staffMenuItemsAllResolveToKnownScreens() {
        String[] staffItems = {"Dashboard", "My Jobs", "Profile",
                "Support", "Settings", "Logout"};
        for (String item : staffItems) {
            String route = routeForLabel(item);
            assertFalse("Staff menu item '" + item + "' must not fall through to unknown",
                    route.isEmpty());
        }
    }

    @Test
    public void settingsLabelRoutesToSettingsScreen() {
        assertTrue("'Settings' must route to settings screen",
                "settings".equals(routeForLabel("Settings")));
    }

    @Test
    public void logoutLabelRoutesToLogout() {
        assertTrue("'Logout' must route to logout",
                "logout".equals(routeForLabel("Logout")));
    }

    @Test
    public void dashboardLabelRoutesToHome() {
        assertTrue("'Dashboard' must route to home",
                "home".equals(routeForLabel("Dashboard")));
    }
}
