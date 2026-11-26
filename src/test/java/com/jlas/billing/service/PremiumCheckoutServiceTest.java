package com.jlas.billing.service;

import com.jlas.auth.entity.Payment;
import com.jlas.auth.entity.PremiumPlan;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.PremiumPlanRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.billing.config.PayOsProperties;
import com.jlas.billing.entity.Coupon;
import com.jlas.billing.entity.CreditPackage;
import com.jlas.billing.payos.PayOsClient;
import com.jlas.billing.repository.BillingPaymentRepository;
import com.jlas.billing.repository.CreditPackageRepository;
import com.jlas.kaiwa.config.AiCallQuotaProperties;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.notification.service.UserNotificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PremiumCheckoutServiceTest {
    private final BillingPaymentRepository payments = mock(BillingPaymentRepository.class);
    private final PremiumPlanRepository plans = mock(PremiumPlanRepository.class);
    private final CreditPackageRepository packages = mock(CreditPackageRepository.class);
    private final CouponService coupons = mock(CouponService.class);
    private final UserCreditService credits = mock(UserCreditService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PayOsClient payos = mock(PayOsClient.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final User account = User.builder().id(7L).build();
    private PremiumCheckoutService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(users.getReferenceById(7L)).thenReturn(account);
        when(payments.saveAndFlush(any())).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(42L);
            return payment;
        });
        service = new PremiumCheckoutService(payments, plans, packages, coupons, credits, users, payos,
                new PayOsProperties("id", "key", "checksum", "https://payos.test", "http://localhost:8080", 15, 3),
                mock(AiCallQuotaProperties.class), mock(UserNotificationPort.class), transactions);
    }

    @Test
    void freePremiumOrderGrantsImmediatelyWithoutPayos() {
        PremiumPlan plan = PremiumPlan.builder().id(3L).name("Premium").price(new BigDecimal("20000"))
                .durationDays(30).isActive(true).build();
        when(plans.findById(3L)).thenReturn(Optional.of(plan));
        when(payments.findReusable(eq("PAYOS"), eq(7L), eq(3L), any())).thenReturn(List.of());
        when(payments.lockUser(7L)).thenReturn(Optional.of(account));
        quote("PREMIUM");

        PremiumCheckoutService.OrderView view = service.start(7L, 3L, "FREE100");

        assertEquals("PAID", view.status());
        assertEquals(0, view.amount().compareTo(BigDecimal.ZERO));
        assertNotNull(view.premiumUntil());
        assertEquals(plan, account.getPremiumPlan());
        assertEquals(view.premiumUntil(), account.getPremiumExpiredAt());
        assertNull(view.checkoutUrl());
        verifyNoInteractions(payos);
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).saveAndFlush(saved.capture());
        assertEquals("COUPON_FREE", saved.getValue().getResponseCode());
        assertEquals("COUPON-" + view.orderCode(), saved.getValue().getProviderTransactionId());
    }

    @Test
    void freeCreditOrderGrantsImmediatelyWithoutPayos() {
        CreditPackage pack = CreditPackage.builder().id(4L).name("100 CP").creditAmount(100)
                .price(new BigDecimal("20000")).isActive(true).build();
        when(packages.findById(4L)).thenReturn(Optional.of(pack));
        when(payments.findReusableCredit(eq("PAYOS"), eq(7L), eq(4L), any())).thenReturn(List.of());
        quote("CREDIT");

        PremiumCheckoutService.OrderView view = service.startCredit(7L, 4L, "FREE100");

        assertEquals("PAID", view.status());
        assertEquals(100, view.creditAmount());
        assertNull(view.checkoutUrl());
        verify(credits).earn(eq(7L), eq(100), any(), eq("PAYMENT"), eq(42L), any());
        verifyNoInteractions(payos);
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).saveAndFlush(saved.capture());
        assertEquals("COUPON_FREE", saved.getValue().getResponseCode());
        assertEquals("COUPON-" + view.orderCode(), saved.getValue().getProviderTransactionId());
    }

    private void quote(String type) {
        Coupon coupon = Coupon.builder().id(2L).code("FREE100").build();
        CouponService.Quote free = new CouponService.Quote("FREE100", new BigDecimal("20000"),
                new BigDecimal("20000"), BigDecimal.ZERO, coupon);
        when(coupons.quoteForReuse("FREE100", type, new BigDecimal("20000"), 7L)).thenReturn(free);
        when(coupons.reserve("FREE100", type, new BigDecimal("20000"), 7L)).thenReturn(free);
    }
}
