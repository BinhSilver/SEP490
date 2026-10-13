package com.jlas.admin.service;

import com.jlas.admin.dto.AdminPaymentView;
import com.jlas.admin.repository.AdminPaymentRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/** Payment lookup only: System Admin can search and read payments, never change or refund them. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPaymentService {
    static final Set<String> STATUSES = Set.of("PENDING", "PAID", "FAILED", "CANCELLED", "EXPIRED", "REFUNDED");

    private final AdminPaymentRepository payments;

    public Page<AdminPaymentView> list(String q, String status, LocalDateTime from, LocalDateTime to,
                                       String productType, int page, int size) {
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Trạng thái thanh toán không hợp lệ.");
        }
        if (productType != null && !Set.of("PREMIUM", "CREDIT").contains(productType))
            throw new ApiException(ErrorCode.BAD_REQUEST, "Loại sản phẩm không hợp lệ.");
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Khoảng thời gian không hợp lệ.");
        }
        return payments.findAll((root, query, cb) -> {
            var filters = new ArrayList<Predicate>();
            if (q != null && !q.isBlank()) {
                String term = "%" + q.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                var user = root.join("user");
                filters.add(cb.or(cb.like(cb.lower(root.get("orderCode")), term, '\\'),
                        cb.like(cb.lower(root.get("providerTransactionId")), term, '\\'),
                        cb.like(cb.lower(user.get("email")), term, '\\'),
                        cb.like(cb.lower(user.get("fullName")), term, '\\')));
            }
            if (status != null) filters.add(cb.equal(root.get("status"), status));
            if (productType != null) filters.add(cb.equal(root.get("productType"), productType));
            if (from != null) filters.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) filters.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            return cb.and(filters.toArray(Predicate[]::new));
        }, AdminUserService.pageRequest(page, size)).map(AdminPaymentView::from);
    }

    public AdminPaymentView detail(Long id) {
        return payments.findWithUserById(id).map(AdminPaymentView::from)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy giao dịch."));
    }
}
