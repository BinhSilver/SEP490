package com.jlas.auth.dto;

import java.time.LocalDateTime;

/** A safe, user-facing representation of one recorded sign-in session. */
public record LoginHistoryItem(
        String device,
        String ipAddress,
        LocalDateTime signedInAt,
        boolean active) {
}
