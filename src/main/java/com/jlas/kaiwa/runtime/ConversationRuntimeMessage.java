package com.jlas.kaiwa.runtime;

import java.time.Instant;

/** A short-lived transcript item; it is intentionally not a JPA entity. */
public record ConversationRuntimeMessage(String role, String content, Instant createdAt) {}
