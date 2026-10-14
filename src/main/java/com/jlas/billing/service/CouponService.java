package com.jlas.billing.service;

import com.jlas.billing.entity.Coupon;
import com.jlas.billing.repository.BillingPaymentRepository;
import com.jlas.billing.repository.CouponRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;

@Service @RequiredArgsConstructor
public class CouponService {
    // PayOS requires a payable bank-transfer amount; 2,000 VND is the minimum for this checkout.
    public static final BigDecimal MIN_PAYABLE = new BigDecimal("2000");
    private final CouponRepository coupons;
    private final BillingPaymentRepository payments;

    public record Quote(String code, BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal finalAmount,
                        Coupon coupon) { }

    public static String normalize(String code) {
        if (code == null || code.isBlank()) return null;
        String value = code.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9_-]{3,40}"))
            throw new ApiException(ErrorCode.BAD_REQUEST, "Mã giảm giá không hợp lệ.");
        return value;
    }

    @Transactional(readOnly = true)
    public Quote preview(String code, String productType, BigDecimal original, Long userId) {
        return quote(code, productType, original, userId, false);
    }

    /** Caller must hold a transaction through payment insertion so reservations cannot race. */
    public Quote reserve(String code, String productType, BigDecimal original, Long userId) {
        return quote(code, productType, original, userId, true);
    }

    public Quote quoteForReuse(String code, String productType, BigDecimal original, Long userId) {
        return quote(code, productType, original, userId, false, false);
    }

    private Quote quote(String rawCode, String productType, BigDecimal original, Long userId, boolean lock) {
        return quote(rawCode, productType, original, userId, lock, true);
    }

    private Quote quote(String rawCode, String productType, BigDecimal original, Long userId, boolean lock, boolean checkLimits) {
        String code = normalize(rawCode);
        if (original == null || original.stripTrailingZeros().scale() > 0 || original.compareTo(MIN_PAYABLE) < 0)
            throw invalid("Giá thanh toán phải là số VND nguyên và ít nhất 2.000 VND.");
        if (code == null) return new Quote(null, original, BigDecimal.ZERO, original, null);
        Coupon coupon = (lock ? coupons.lockByCode(code) : coupons.findByCode(code))
                .orElseThrow(() -> invalid("Mã giảm giá không tồn tại."));
        LocalDateTime now = LocalDateTime.now();
        if (!Boolean.TRUE.equals(coupon.getIsActive()) || coupon.getStartsAt() != null && now.isBefore(coupon.getStartsAt())
                || coupon.getEndsAt() != null && !now.isBefore(coupon.getEndsAt()))
            throw invalid("Mã giảm giá chưa có hiệu lực hoặc đã hết hạn.");
        if (!"ALL".equals(coupon.getAppliesTo()) && !coupon.getAppliesTo().equals(productType))
            throw invalid("Mã giảm giá không áp dụng cho sản phẩm này.");
        if (coupon.getMinOrderAmount() != null && original.compareTo(coupon.getMinOrderAmount()) < 0)
            throw invalid("Đơn hàng chưa đạt giá trị tối thiểu của mã giảm giá.");
        if (checkLimits && coupon.getUsageLimit() != null && payments.countCouponUses(coupon.getId(), now) >= coupon.getUsageLimit())
            throw invalid("Mã giảm giá đã hết lượt sử dụng.");
        if (checkLimits && userId != null && coupon.getPerUserLimit() != null
                && payments.countCouponUsesByUser(coupon.getId(), userId, now) >= coupon.getPerUserLimit())
            throw invalid("Bạn đã dùng hết lượt của mã giảm giá này.");
        BigDecimal discount = "PERCENT".equals(coupon.getDiscountType())
                ? original.multiply(coupon.getDiscountValue()).divide(new BigDecimal("100"), 0, RoundingMode.FLOOR)
                : coupon.getDiscountValue();
        if ("PERCENT".equals(coupon.getDiscountType()) && coupon.getMaxDiscount() != null)
            discount = discount.min(coupon.getMaxDiscount());
        discount = discount.min(original);
        BigDecimal payable = original.subtract(discount);
        if (payable.stripTrailingZeros().scale() > 0 || payable.compareTo(MIN_PAYABLE) < 0)
            throw invalid("Số tiền sau giảm giá phải là số VND nguyên và ít nhất 2.000 VND.");
        return new Quote(code, original, discount, payable, coupon);
    }

    private static ApiException invalid(String message) { return new ApiException(ErrorCode.BAD_REQUEST, message); }
}
