package com.jlas.moderation.credit.service;

/** Credit operations exposed to feature modules without sharing persistence models. */
public interface UserCreditPort {
    long balance(Long userId);

    void rewardCreator(Long userId, int amount, String referenceType, Long referenceId, String note);
}
