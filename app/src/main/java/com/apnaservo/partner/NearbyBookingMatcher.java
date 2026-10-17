package com.apnaservo.partner;

final class NearbyBookingMatcher {
    private static final double EARTH_RADIUS_KM = 6371.0;

    private NearbyBookingMatcher() {}

    static boolean matches(double partnerLat, double partnerLng,
            double serviceLat, double serviceLng, double radiusKm) {
        if (!validCoordinates(partnerLat, partnerLng)
                || !validCoordinates(serviceLat, serviceLng)
                || !Double.isFinite(radiusKm) || radiusKm <= 0) {
            return false;
        }
        return distanceKm(partnerLat, partnerLng, serviceLat, serviceLng) <= radiusKm;
    }

    private static boolean validCoordinates(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90 && lat <= 90
                && lng >= -180 && lng <= 180
                && (lat != 0 || lng != 0);
    }

    private static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double latDistance = Math.toRadians(lat2 - lat1);
        double lngDistance = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
