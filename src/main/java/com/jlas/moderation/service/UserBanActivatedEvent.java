package com.jlas.moderation.service;

/** Published after a ban is persisted so realtime features can revoke access. */
public record UserBanActivatedEvent(long userId) { }
