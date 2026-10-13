package com.jlas.admin.service;

import com.jlas.admin.dto.AdminUserView;
import com.jlas.admin.repository.AdminUserRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.notification.service.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.Map;

@Service @RequiredArgsConstructor
public class AdminCreditService {
    private static final String LEDGER_NOTE = "Quản trị viên điều chỉnh CP";
    private final AdminUserRepository users;
    private final UserRepository userRepository;
    private final UserCreditService credits;
    private final AdminAuditService audit;
    private final UserNotificationPort notifications;
    private final PlatformTransactionManager transactionManager;

    public AdminUserView adjust(Long actorId, Long userId, Integer amount, String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() < 5 || reason.length() > 500)
            throw new ApiException(ErrorCode.BAD_REQUEST, "Vui lòng nhập lý do từ 5 đến 500 ký tự.");
        try {
            if (amount == null || amount == 0 || amount < -100000 || amount > 100000)
                throw new ApiException(ErrorCode.BAD_REQUEST, "Số CP điều chỉnh phải khác 0 và tối đa 100.000 CP.");
            return new TransactionTemplate(transactionManager).execute(status -> apply(actorId, userId, amount, reason));
        } catch (ApiException rejected) {
            // Audit after rollback: a separate audit transaction must not wait on the locked user row.
            audit.recordRejected(actorId, "USER_CREDIT_ADJUST", "USER", userId.toString(), reason,
                    Map.of("rejection", rejected.getMessage()));
            throw rejected;
        }
    }

    private AdminUserView apply(Long actorId, Long userId, int amount, String reason) {
        users.lockAdminRole();
        long before = userRepository.findForCreditUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng."))
                .getCreditBalance();
        if (amount < 0 && before < -(long) amount)
            throw new ApiException(ErrorCode.CONFLICT, "Người dùng không đủ CP để trừ.");
        var result = amount > 0
                ? credits.earn(userId, amount, CreditReason.ADMIN_GRANT, "ADMIN_ADJUST", null, LEDGER_NOTE)
                : credits.spend(userId, -amount, CreditReason.ADMIN_DEDUCT, "ADMIN_ADJUST", null, LEDGER_NOTE);
        audit.record(actorId, "USER_CREDIT_ADJUST", "USER", userId.toString(), reason, "SUCCESS",
                Map.of("amount", amount, "before", before, "after", result.balance()));
        notifications.notifyUser(userId, "CREDIT", amount > 0 ? "Đã cộng " + amount + " CP" : "Đã trừ " + (-amount) + " CP",
                LEDGER_NOTE, "/credit-history", "ADMIN_CREDIT_" + System.nanoTime());
        return AdminUserView.from(users.findById(userId).orElseThrow(), true, LocalDateTime.now());
    }

}
