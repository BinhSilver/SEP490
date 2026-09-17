package com.jlas.mission.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.mission.service.CreditLedgerService;
import com.jlas.moderation.entity.UserCreditTransaction;
import com.jlas.moderation.repository.UserCreditTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link CreditLedgerService} on the shared credit ledger. */
@Service
@RequiredArgsConstructor
public class CreditLedgerServiceImpl implements CreditLedgerService {

    private final UserRepository userRepository;
    private final UserCreditTransactionRepository transactionRepository;

    @Override
    @Transactional
    public int award(Long userId, int amount, String reason, String referenceType, Long referenceId) {
        if (amount <= 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Credit amount must be positive.");
        }
        if (reason == null || reason.isBlank() || reason.length() > 30) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Credit reason is invalid.");
        }
        if (referenceType != null && referenceType.length() > 30) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Credit reference type is invalid.");
        }

        boolean referenced = referenceType != null && referenceId != null;

        // Lock the user row first so the idempotency check below is serialized against
        // concurrent awards of the same reference; the DB unique key is the final backstop.
        User user = userRepository.findForCreditUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found."));

        if (referenced && transactionRepository.existsReference(userId, reason, referenceType, referenceId)) {
            return currentBalance(user);
        }

        transactionRepository.save(UserCreditTransaction.builder()
                .user(user)
                .amount(amount)
                .reason(reason)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .build());

        user.setCreditBalance(currentBalance(user) + amount);
        userRepository.save(user);
        return user.getCreditBalance();
    }

    @Override
    @Transactional(readOnly = true)
    public int balance(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found."));
        return currentBalance(user);
    }

    private static int currentBalance(User user) {
        return user.getCreditBalance() == null ? 0 : user.getCreditBalance();
    }
}
