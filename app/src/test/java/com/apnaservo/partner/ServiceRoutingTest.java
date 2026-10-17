package com.apnaservo.partner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ServiceRoutingTest {
    @Test public void canonicalAliasesAreStable() {
        assertEquals("ac_repair", ServiceRouting.canonicalServiceId("AC Repair & Service"));
        assertEquals("washing_machine", ServiceRouting.canonicalServiceId("Washing Machine Repair"));
        assertEquals("refrigerator", ServiceRouting.canonicalServiceId("Fridge Repair"));
        assertEquals("microwave", ServiceRouting.canonicalServiceId("Microwave Repair"));
        assertEquals("pest_control", ServiceRouting.canonicalServiceId("Pest Control"));
        assertEquals("ro", ServiceRouting.canonicalServiceId("Water Purifier Service"));
    }

    @Test public void requiredRoleMatrixResolvesStrictly() {
        assertEquals("ac_repair_partner", ServiceRouting.route("ac_repair", "owner"));
        assertEquals("laundry_owner", ServiceRouting.route("laundry", "owner"));
        assertEquals("laundry_staff", ServiceRouting.route("laundry", "staff"));
        assertEquals("cleaning_owner", ServiceRouting.route("cleaning", "owner"));
        assertEquals("cleaning_staff", ServiceRouting.route("cleaning", "staff"));
        assertEquals("plumbing_partner", ServiceRouting.route("plumbing", "owner"));
        assertEquals("electrician_partner", ServiceRouting.route("electrician", "owner"));
        assertEquals("carpenter_partner", ServiceRouting.route("carpenter", "owner"));
        assertEquals("painting_partner", ServiceRouting.route("painting", "owner"));
        assertEquals("appliance_repair_partner", ServiceRouting.route("appliance_repair", "owner"));
        assertEquals("roadside_partner", ServiceRouting.route("roadside", "owner"));
    }

    @Test public void invalidAndCrossServiceMappingsAreRejected() {
        assertEquals(ServiceRouting.ROUTE_UNAVAILABLE, ServiceRouting.route("unknown", "owner"));
        assertFalse(ServiceRouting.matchesAssignment("laundry", "cleaning"));
        assertTrue(ServiceRouting.matchesAssignment("appliance_repair", "washing_machine"));
    }
}
