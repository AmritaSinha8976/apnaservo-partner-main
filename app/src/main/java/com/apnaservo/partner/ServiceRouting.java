package com.apnaservo.partner;

import java.util.Locale;

/** Canonical, display-name-independent service and role routing contract. */
final class ServiceRouting {
    static final String ROLE_OWNER = "owner";
    static final String ROLE_STAFF = "staff";
    static final String ROUTE_UNAVAILABLE = "service_configuration_unavailable";

    private ServiceRouting() {}

    static String canonicalServiceId(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace('&', ' ').replace('-', '_').replace('/', '_')
                .replaceAll("[^a-z0-9_ ]+", "").replaceAll("[ _]+", "_");
        if (value.isEmpty() || "service".equals(value)) return "";
        if (value.equals("ac") || value.contains("ac_repair") || value.contains("air_condition")) return "ac_repair";
        if (value.contains("washing_machine")) return "washing_machine";
        if (value.contains("refrigerator") || value.contains("fridge")) return "refrigerator";
        if (value.contains("microwave")) return "microwave";
        if (value.contains("appliance")) return "appliance_repair";
        if (value.contains("laundry") || value.contains("dry_clean") || value.contains("cloth_wash")) return "laundry";
        if (value.contains("clean")) return "cleaning";
        if (value.contains("plumb")) return "plumbing";
        if (value.contains("electric")) return "electrician";
        if (value.contains("carpent")) return "carpenter";
        if (value.contains("paint")) return "painting";
        if (value.contains("pest")) return "pest_control";
        if (value.contains("interior")) return "interior";
        if (value.contains("roadside") || value.contains("road_assist")) return "roadside";
        if (value.equals("ro") || value.contains("ro_service") || value.contains("water_purifier")) return "ro";
        return isKnown(value) ? value : "";
    }

    static boolean isKnown(String serviceId) {
        switch (serviceId == null ? "" : serviceId) {
            case "ac_repair": case "washing_machine": case "refrigerator": case "microwave":
            case "appliance_repair": case "laundry": case "cleaning": case "plumbing":
            case "electrician": case "carpenter": case "painting": case "pest_control":
            case "interior": case "roadside": case "ro": return true;
            default: return false;
        }
    }

    static String serviceFamily(String serviceId) {
        String id = canonicalServiceId(serviceId);
        if (id.equals("washing_machine") || id.equals("refrigerator") || id.equals("microwave")) return "appliance_repair";
        return id;
    }

    static boolean matchesAssignment(String assignedService, String bookingService) {
        String assigned = serviceFamily(assignedService);
        String booking = serviceFamily(bookingService);
        return !assigned.isEmpty() && assigned.equals(booking);
    }

    static String route(String service, String role) {
        String id = canonicalServiceId(service);
        String normalizedRole = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if (!isKnown(id) || (!ROLE_OWNER.equals(normalizedRole) && !ROLE_STAFF.equals(normalizedRole))) return ROUTE_UNAVAILABLE;
        if (ROLE_STAFF.equals(normalizedRole)) {
            if ("laundry".equals(id)) return "laundry_staff";
            if ("cleaning".equals(id)) return "cleaning_staff";
            return id + "_partner";
        }
        if ("laundry".equals(id)) return "laundry_owner";
        if ("cleaning".equals(id)) return "cleaning_owner";
        return id + "_partner";
    }

    static String displayName(String service) {
        switch (canonicalServiceId(service)) {
            case "ac_repair": return "AC Repair & Service";
            case "washing_machine": return "Washing Machine Repair";
            case "refrigerator": return "Refrigerator Repair";
            case "microwave": return "Microwave Repair";
            case "appliance_repair": return "Appliance Repair";
            case "laundry": return "Laundry";
            case "cleaning": return "Cleaning";
            case "plumbing": return "Plumbing";
            case "electrician": return "Electrician";
            case "carpenter": return "Carpenter";
            case "painting": return "Painting";
            case "pest_control": return "Pest Control";
            case "interior": return "Interior";
            case "roadside": return "Roadside Assistance";
            case "ro": return "RO Service";
            default: return "Service";
        }
    }
}
