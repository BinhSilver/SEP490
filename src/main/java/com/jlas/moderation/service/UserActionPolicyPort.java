package com.jlas.moderation.service;

/** Contract used by feature modules before accepting user-generated content. */
public interface UserActionPolicyPort {
    void assertCanAct(Long userId);
}
