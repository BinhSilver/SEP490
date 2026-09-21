package com.jlas.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;

@Service
public class GoogleTokenService {
    private final String clientId;

    public GoogleTokenService(@Value("${app.google.client-id:}") String clientId) {
        this.clientId = clientId;
    }

    public GoogleUserInfo verify(String rawIdToken) {
        if (clientId == null || clientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google login is not configured");
        }
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(clientId))
                    .build();
            GoogleIdToken idToken = verifier.verify(rawIdToken);
            if (idToken == null || !Boolean.TRUE.equals(idToken.getPayload().getEmailVerified())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google ID token");
            }
            GoogleIdToken.Payload payload = idToken.getPayload();
            return new GoogleUserInfo(payload.getSubject(), payload.getEmail(),
                    (String) payload.get("name"), (String) payload.get("picture"));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google authentication failed", exception);
        }
    }

    public record GoogleUserInfo(String googleId, String email, String fullName, String avatar) {
    }
}
