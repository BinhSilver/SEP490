package com.jlas.common.event;

/**
 * Published by the central credit service after credits were actually added to a learner's ledger
 * (never for a duplicate reference). Features may react to it, for example to celebrate the reward.
 *
 * @param reason        {@code CreditReason} name
 * @param referenceType what the credits were for; may be null
 * @param referenceId   id of that thing; may be null
 * @param note          human-readable note stored with the ledger row; may be null
 */
public record CreditEarned(Long userId, int amount, String reason, String referenceType, Long referenceId,
                           String note, long balance) {
}
