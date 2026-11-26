package com.jlas.billing.service;

import com.jlas.billing.entity.Coupon;
import com.jlas.billing.repository.BillingPaymentRepository;
import com.jlas.billing.repository.CouponRepository;
import com.jlas.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponServiceTest {
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final CouponService service = new CouponService(coupons, mock(BillingPaymentRepository.class));

    @Test
    void fullDiscountIsFree() {
        when(coupons.findByCode("FREE100")).thenReturn(Optional.of(coupon("PERCENT", "100")));

        CouponService.Quote quote = service.preview("free100", "PREMIUM", new BigDecimal("20000"), 7L);

        assertEquals(0, quote.finalAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, quote.discountAmount().compareTo(new BigDecimal("20000")));
    }

    @Test
    void partialDiscountBelowPayosMinimumIsRejected() {
        when(coupons.findByCode("NEARFREE")).thenReturn(Optional.of(coupon("FIXED", "19000")));

        assertThrows(ApiException.class, () -> service.preview("NEARFREE", "CREDIT", new BigDecimal("20000"), 7L));
    }

    @Test
    void originalPriceMustStillReachMinimum() {
        assertThrows(ApiException.class, () -> service.preview(null, "CREDIT", new BigDecimal("1000"), 7L));
    }

    private static Coupon coupon(String type, String value) {
        return Coupon.builder().code("FREE100").discountType(type).discountValue(new BigDecimal(value))
                .appliesTo("ALL").isActive(true).build();
    }
}
