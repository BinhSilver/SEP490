package com.jlas.mission.service;

/** Awards credits and keeps the user's running balance in sync with the ledger. */
public interface CreditLedgerService {

    /**
     * Awards a positive amount of credits. Awards keyed by {@code referenceType} and
     * {@code referenceId} are idempotent, so a replayed event never double-counts.
     */
    int award(Long userId, int amount, String reason, String referenceType, Long referenceId);

    /** Returns the user's current credit balance. */
    int balance(Long userId);
}
