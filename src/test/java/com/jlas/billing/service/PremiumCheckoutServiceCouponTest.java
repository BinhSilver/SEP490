package com.jlas.billing.service;

import com.jlas.auth.entity.Payment;
import com.jlas.auth.entity.PremiumPlan;
import com.jlas.auth.repository.PremiumPlanRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.billing.config.PayOsProperties;
import com.jlas.billing.entity.CreditPackage;
import com.jlas.billing.payos.PayOsClient;
import com.jlas.billing.repository.BillingPaymentRepository;
import com.jlas.billing.repository.CreditPackageRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.config.AiCallQuotaProperties;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.notification.service.UserNotificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PremiumCheckoutServiceCouponTest {
    private final BillingPaymentRepository payments = mock(BillingPaymentRepository.class);
    private final PremiumPlanRepository plans = mock(PremiumPlanRepository.class);
    private final CreditPackageRepository packages = mock(CreditPackageRepository.class);
    private final CouponService coupons = mock(CouponService.class);
    private final PayOsClient payos = mock(PayOsClient.class);
    private PremiumCheckoutService checkout;
    private Payment old;

    @BeforeEach
    void setUp() {
        checkout = spy(new PremiumCheckoutService(payments, plans, packages, coupons,
                mock(UserCreditService.class), mock(UserRepository.class), payos,
                mock(PayOsProperties.class), mock(AiCallQuotaProperties.class),
                mock(UserNotificationPort.class), mock(PlatformTransactionManager.class)));
        PremiumPlan plan = PremiumPlan.builder().id(7L).name("Premium").isActive(true)
                .price(new BigDecimal("100000")).durationDays(30).build();
        old = Payment.builder().id(3L).orderCode("123").status("PENDING").productType("PREMIUM")
                .premiumPlan(plan).amountPaid(plan.getPrice()).originalAmount(plan.getPrice())
                .discountAmount(BigDecimal.ZERO).currency("VND").planNameSnapshot(plan.getName())
                .checkoutUrl("https://payos.example/old").expiresAt(LocalDateTime.now().plusMinutes(10)).build();
        when(payments.findByProviderAndOrderCodeAndUser_Id("PAYOS", "123", 5L)).thenReturn(Optional.of(old));
        when(plans.findById(7L)).thenReturn(Optional.of(plan));
    }

    @Test
    void invalidCouponLeavesOldOrderAndPayOsLinkUntouched() {
        when(coupons.preview(eq("BAD"), eq("PREMIUM"), any(), eq(5L)))
                .thenThrow(new ApiException(ErrorCode.BAD_REQUEST, "Mã không hợp lệ."));

        assertThrows(ApiException.class, () -> checkout.applyCoupon(5L, 123L, "BAD"));

        verify(checkout, never()).cancel(any(), anyLong());
        verifyNoInteractions(payos);
        assertEquals("PENDING", old.getStatus());
        assertEquals("https://payos.example/old", old.getCheckoutUrl());
    }

    @Test
    void validCouponCancelsOldOrderAndReturnsReplacement() {
        when(coupons.preview(eq("SAVE"), eq("PREMIUM"), any(), eq(5L)))
                .thenReturn(new CouponService.Quote("SAVE", new BigDecimal("100000"),
                        new BigDecimal("10000"), new BigDecimal("90000"), null));
        var cancelled = view(123L, "CANCELLED");
        var replacement = view(456L, "PENDING");
        doReturn(cancelled).when(checkout).cancel(5L, 123L);
        doReturn(replacement).when(checkout).start(5L, 7L, "SAVE");

        assertSame(replacement, checkout.applyCoupon(5L, 123L, "save"));

        var sequence = inOrder(coupons, checkout);
        sequence.verify(coupons).preview(eq("SAVE"), eq("PREMIUM"), any(), eq(5L));
        sequence.verify(checkout).cancel(5L, 123L);
        sequence.verify(checkout).start(5L, 7L, "SAVE");
    }

    @Test
    void paidDuringCancellationReturnsPaidOrderWithoutReplacement() {
        when(coupons.preview(eq("SAVE"), eq("PREMIUM"), any(), eq(5L)))
                .thenReturn(new CouponService.Quote("SAVE", new BigDecimal("100000"),
                        new BigDecimal("10000"), new BigDecimal("90000"), null));
        var paid = view(123L, "PAID");
        doReturn(paid).when(checkout).cancel(5L, 123L);

        assertSame(paid, checkout.applyCoupon(5L, 123L, "SAVE"));
        verify(checkout, never()).start(any(), any(), any());
    }

    @Test
    void replacingOwnCouponValidatesWithoutCountingItsPendingReservationTwice() {
        old.setCouponCodeSnapshot("SAVE");
        when(coupons.quoteForReuse(eq("SAVE"), eq("PREMIUM"), any(), eq(5L)))
                .thenReturn(new CouponService.Quote("SAVE", new BigDecimal("100000"),
                        new BigDecimal("10000"), new BigDecimal("90000"), null));
        doReturn(view(123L, "CANCELLED")).when(checkout).cancel(5L, 123L);
        doReturn(view(456L, "PENDING")).when(checkout).start(5L, 7L, "SAVE");

        assertEquals(456L, checkout.applyCoupon(5L, 123L, "SAVE").orderCode());
        verify(coupons).quoteForReuse(eq("SAVE"), eq("PREMIUM"), any(), eq(5L));
        verify(coupons, never()).preview(any(), any(), any(), any());
    }

    @Test
    void expiredOwnReservationMustPassNormalCouponLimitsBeforeCancellation() {
        old.setCouponCodeSnapshot("SAVE");
        old.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(coupons.preview(eq("SAVE"), eq("PREMIUM"), any(), eq(5L)))
                .thenThrow(new ApiException(ErrorCode.BAD_REQUEST, "Mã đã hết lượt."));

        assertThrows(ApiException.class, () -> checkout.applyCoupon(5L, 123L, "SAVE"));

        verify(checkout, never()).cancel(any(), anyLong());
        verify(coupons, never()).quoteForReuse(any(), any(), any(), any());
    }

    @Test
    void creditOrderUsesTheCreditPackageForReplacement() {
        CreditPackage pack = CreditPackage.builder().id(9L).name("CP").isActive(true)
                .price(new BigDecimal("50000")).creditAmount(100).build();
        old.setProductType("CREDIT");
        old.setPremiumPlan(null);
        old.setCreditPackage(pack);
        when(packages.findById(9L)).thenReturn(Optional.of(pack));
        when(coupons.preview(eq("SAVE"), eq("CREDIT"), any(), eq(5L)))
                .thenReturn(new CouponService.Quote("SAVE", pack.getPrice(),
                        new BigDecimal("5000"), new BigDecimal("45000"), null));
        doReturn(view(123L, "CANCELLED")).when(checkout).cancel(5L, 123L);
        var replacement = view(789L, "PENDING");
        doReturn(replacement).when(checkout).startCredit(5L, 9L, "SAVE");

        assertSame(replacement, checkout.applyCoupon(5L, 123L, "SAVE"));
        verify(checkout).startCredit(5L, 9L, "SAVE");
    }

    @Test
    void alreadyPaidOrderIsReturnedWithoutCouponValidation() {
        old.setStatus("PAID");

        assertEquals("PAID", checkout.applyCoupon(5L, 123L, "SAVE").status());
        verifyNoInteractions(coupons, payos);
        verify(checkout, never()).cancel(any(), anyLong());
    }

    private static PremiumCheckoutService.OrderView view(long orderCode, String status) {
        return new PremiumCheckoutService.OrderView(orderCode, status, "Premium", 30,
                new BigDecimal("90000"), "VND", null, null, null, null, null, null,
                "PREMIUM", null, new BigDecimal("100000"), new BigDecimal("10000"), "SAVE");
    }
}
