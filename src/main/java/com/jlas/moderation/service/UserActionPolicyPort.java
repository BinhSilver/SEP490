package com.jlas.moderation.service;

/** Contract used by feature modules before accepting user-generated content. */
/**
 * Guard for community actions: commenting and publishing content publicly.
 * Blocked while the user has an active MUTE or BAN. Private study features must not call it.
 */
public interface UserActionPolicyPort {
    void assertCanAct(Long userId);
}
