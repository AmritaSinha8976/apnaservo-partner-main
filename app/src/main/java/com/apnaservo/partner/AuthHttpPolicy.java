package com.apnaservo.partner;

final class AuthHttpPolicy {
    private AuthHttpPolicy() {
    }

    static boolean isAuthenticationFailure(int httpCode) {
        return httpCode == 401;
    }
}
