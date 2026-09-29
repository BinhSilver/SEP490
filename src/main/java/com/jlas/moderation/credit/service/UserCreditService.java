package com.jlas.moderation.credit.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.event.CreditEarned;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.repository.UserCreditTransactionRepository;
import lombok.RequiredArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserCreditService implements UserCreditPort {
    private final UserCreditTransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;

    public record CreditTransactionView(Long id, int amount, String reason,
                                        String referenceType, Long referenceId,
                                        String note, LocalDateTime createdAt) {
    }

    /** Outcome of a ledger write; {@code applied} is false when the same reference was already recorded. */
    public record CreditResult(boolean applied, long balance) {
    }

    @Override
    public void rewardCreator(Long userId, int amount, String referenceType, Long referenceId, String note) {
        earn(userId, amount, CreditReason.CREATOR_REWARD, referenceType, referenceId, note);
    }

    @Transactional(readOnly = true)
    @Override
    public long balance(Long userId) {
        return userRepository.findById(userId).map(User::getCreditBalance).orElse(0);
    }

    @Transactional(readOnly = true)
    public List<CreditTransactionView> history(Long userId) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(value -> new CreditTransactionView(value.getId(), value.getAmount(), value.getReason(),
                        value.getReferenceType(), value.getReferenceId(), value.getNote(), value.getCreatedAt()))
                .toList();
    }

    /**
     * The only way to add credits. Calls with the same (reason, referenceType, referenceId) are applied
     * once: the user row lock serialises the duplicate check and uk_credit_transaction_reference backs it up.
     */
    @Transactional
    public CreditResult earn(Long userId, int amount, CreditReason reason,
                             String referenceType, Long referenceId, String note) {
        if (amount <= 0) throw new ApiException(ErrorCode.BAD_REQUEST, "Earn amount must be > 0");
        User user = lockUser(userId);
        if (isDuplicate(userId, reason, referenceType, referenceId)) {
            return new CreditResult(false, user.getCreditBalance());
        }
        user.setCreditBalance(Math.addExact(user.getCreditBalance(), amount));
        userRepository.save(user);
        saveTransaction(user, amount, reason, referenceType, referenceId, note);
        events.publishEvent(new CreditEarned(userId, amount, reason.name(), referenceType, referenceId, note,
                user.getCreditBalance()));
        return new CreditResult(true, user.getCreditBalance());
    }

    /** The only way to remove credits; idempotent per reference like {@link #earn}. */
    @Transactional
    public CreditResult spend(Long userId, int amount, CreditReason reason,
                              String referenceType, Long referenceId, String note) {
        if (amount <= 0) throw new ApiException(ErrorCode.BAD_REQUEST, "Spend amount must be > 0");
        User user = lockUser(userId);
        if (isDuplicate(userId, reason, referenceType, referenceId)) {
            return new CreditResult(false, user.getCreditBalance());
        }
        if (user.getCreditBalance() < amount) {
            throw new ApiException(ErrorCode.INSUFFICIENT_CREDIT,
                    "Bạn không đủ Credit. Cần " + amount + " · Hiện có " + user.getCreditBalance());
        }
        user.setCreditBalance(Math.subtractExact(user.getCreditBalance(), amount));
        userRepository.save(user);
        saveTransaction(user, -amount, reason, referenceType, referenceId, note);
        return new CreditResult(true, user.getCreditBalance());
    }

    /**
     * Moves the user's net credit for one reference to {@code target} and returns the change applied.
     * Used when a moderator revises a decision, so flipping back and forth never pays twice.
     * A take-back never pushes the balance below 0: credit already spent is not reclaimed.
     */
    @Transactional
    public int settle(Long userId, int target, CreditReason gainReason, CreditReason lossReason,
                      String referenceType, Long referenceId, Long revisionId, String note) {
        if (revisionId == null) throw new IllegalArgumentException("A moderation audit revision is required");
        User user = lockUser(userId);
        // Each revision is a distinct operation. Keep the ledger's unique-reference backstop
        // without treating a later re-approval as a duplicate of the original approval.
        if (isDuplicate(userId, gainReason, "MODERATION_REVISION", revisionId)
                || isDuplicate(userId, lossReason, "MODERATION_REVISION", revisionId)) return 0;
        long delta = target - transactionRepository.netAmount(userId, referenceType, referenceId);
        int applied = delta >= 0 ? (int) delta : -(int) Math.min(-delta, user.getCreditBalance());
        if (applied == 0) return 0;
        user.setCreditBalance(Math.addExact(user.getCreditBalance(), applied));
        userRepository.save(user);
        saveTransaction(user, applied, applied > 0 ? gainReason : lossReason,
                "MODERATION_REVISION", revisionId, note);
        return applied;
    }

    private User lockUser(Long userId) {
        return userRepository.findForCreditUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found"));
    }

    private boolean isDuplicate(Long userId, CreditReason reason,
                                String referenceType, Long referenceId) {
        return referenceType != null && referenceId != null
                && transactionRepository.existsByUserIdAndReasonAndReferenceTypeAndReferenceId(
                userId, reason.name(), referenceType, referenceId);
    }

    private void saveTransaction(User user, int amount, CreditReason reason,
                                 String referenceType, Long referenceId, String note) {
        transactionRepository.save(com.jlas.moderation.entity.UserCreditTransaction.builder()
                .user(user)
                .amount(amount)
                .reason(reason.name())
                .referenceType(referenceType)
                .referenceId(referenceId)
                .note(note)
                .build());
    }
}
