package com.apnaservo.partner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AuthHttpPolicyTest {
    @Test
    public void forbiddenAndBusinessFailuresDoNotRefreshAuthentication() {
        assertTrue(AuthHttpPolicy.isAuthenticationFailure(401));
        assertFalse(AuthHttpPolicy.isAuthenticationFailure(403));
        assertFalse(AuthHttpPolicy.isAuthenticationFailure(409));
        assertFalse(AuthHttpPolicy.isAuthenticationFailure(422));
        assertFalse(AuthHttpPolicy.isAuthenticationFailure(500));
    }
}
