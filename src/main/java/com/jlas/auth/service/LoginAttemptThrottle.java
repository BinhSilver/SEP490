package com.jlas.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Limits attempts per account independently of the connection's IP address. */
@Service
@RequiredArgsConstructor
public class LoginAttemptThrottle {
    private final AuthRequestThrottle requests;

    public void assertAllowed(String email, String clientIp) {
        requests.assertLoginAllowed(email, clientIp);
    }

    public void recordSuccess(String email, String clientIp) {
        requests.loginSucceeded(email);
    }
}
