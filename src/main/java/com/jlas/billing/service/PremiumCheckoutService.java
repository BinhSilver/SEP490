package com.jlas.billing.service;

import com.jlas.auth.entity.Payment;
import com.jlas.auth.entity.PremiumPlan;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.PremiumPlanRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.billing.entity.CreditPackage;
import com.jlas.billing.repository.CreditPackageRepository;
import com.jlas.billing.config.PayOsProperties;
import com.jlas.billing.payos.PayOsClient;
import com.jlas.billing.repository.BillingPaymentRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.config.AiCallQuotaProperties;
import com.jlas.notification.service.UserNotificationPort;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Premium checkout through PayOS without webhooks (JLAS2-49).
 * <p>
 * The buyer's browser never decides anything: the return page and the polling only ask the server to check,
 * and the server reads the order from PayOS itself. Premium is granted while the payment row is locked and
 * still PENDING, so repeated or concurrent checks grant it exactly once. A PAID order only counts when PayOS
 * reports the full amount of this order.
 */
@Service
public class PremiumCheckoutService {
    static final String PROVIDER = "PAYOS";
    /** PayOS limits the transfer note to 9 characters for bank accounts not linked through PayOS. */
    static final String DESCRIPTION = "KOTSU PRM";
    private static final String FREE_PLAN = "FREE";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final Logger log = LoggerFactory.getLogger(PremiumCheckoutService.class);

    private final BillingPaymentRepository payments;
    private final PremiumPlanRepository plans;
    private final CreditPackageRepository packages;
    private final CouponService coupons;
    private final UserCreditService credits;
    private final UserRepository users;
    private final PayOsClient payos;
    private final PayOsProperties properties;
    private final AiCallQuotaProperties quota;
    private final UserNotificationPort notifications;
    private final TransactionTemplate tx;

    public PremiumCheckoutService(BillingPaymentRepository payments, PremiumPlanRepository plans, CreditPackageRepository packages,
                                  CouponService coupons, UserCreditService credits, UserRepository users,
                                  PayOsClient payos, PayOsProperties properties, AiCallQuotaProperties quota,
                                  UserNotificationPort notifications, PlatformTransactionManager transactionManager) {
        this.payments = payments;
        this.plans = plans;
        this.packages = packages;
        this.coupons = coupons;
        this.credits = credits;
        this.users = users;
        this.payos = payos;
        this.properties = properties;
        this.quota = quota;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ── Views ───────────────────────────────────────────────────────────────

    public record PlanView(Long id, String name, String description, BigDecimal price, int durationDays) {
    }

    public record CurrentPlan(String planName, boolean premiumActive, LocalDateTime expiresAt) {
    }

    /** What Premium actually changes today: the AI conversation allowance (0 monthly = unlimited). */
    public record AiAllowance(double freeDailyMinutes, double freeMonthlyMinutes,
                              double premiumDailyMinutes, double premiumMonthlyMinutes) {
    }

    public record Pricing(List<PlanView> plans, CurrentPlan current, AiAllowance aiCall, boolean paymentsEnabled) {
    }

    public record OrderView(long orderCode, String status, String planName, Integer durationDays, BigDecimal amount,
                            String currency, String checkoutUrl, String qrCode, LocalDateTime expiresAt,
                            LocalDateTime createdAt, LocalDateTime paidAt, LocalDateTime premiumUntil,
                            String productType, Integer creditAmount, BigDecimal originalAmount,
                            BigDecimal discountAmount, String couponCode) {
        static OrderView from(Payment p) {
            boolean payable = "PENDING".equals(p.getStatus());
            return new OrderView(Long.parseLong(p.getOrderCode()), p.getStatus(), p.getPlanNameSnapshot(),
                    p.getDurationDaysSnapshot(), p.getAmountPaid(), p.getCurrency(),
                    payable ? p.getCheckoutUrl() : null, payable ? p.getQrCode() : null, p.getExpiresAt(),
                    p.getCreatedAt(), p.getPaidAt(), p.getPremiumUntil(), p.getProductType(),
                    p.getCreditAmountSnapshot(), p.getOriginalAmount() == null ? p.getAmountPaid() : p.getOriginalAmount(),
                    p.getDiscountAmount(), p.getCouponCodeSnapshot());
        }
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true) // reads the account's current plan (lazy)
    public Pricing pricing(Long userId) {
        List<PlanView> onSale = plans.findAll(Sort.by("durationDays", "price")).stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsActive()) && !FREE_PLAN.equalsIgnoreCase(p.getName()))
                .map(p -> new PlanView(p.getId(), p.getName(), p.getDescription(), p.getPrice(), p.getDurationDays()))
                .toList();
        CurrentPlan current = userId == null ? null : users.findById(userId).map(this::current).orElse(null);
        return new Pricing(onSale, current, new AiAllowance(quota.freeDailyMinutes(), quota.freeMonthlyMinutes(),
                quota.premiumDailyMinutes(), quota.premiumMonthlyMinutes()), properties.configured());
    }

    public List<OrderView> history(Long userId) {
        return payments.findTop50ByUser_IdOrderByCreatedAtDescIdDesc(userId).stream().map(OrderView::from).toList();
    }

    public record PackageView(Long id, String name, String description, int creditAmount, BigDecimal price) { }
    public record CreditPricing(List<PackageView> packages, Long balance, boolean paymentsEnabled) { }

    public CreditPricing creditPricing(Long userId) {
        return new CreditPricing(packages.findAll(Sort.by("price", "id")).stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsActive()))
                .map(p -> new PackageView(p.getId(), p.getName(), p.getDescription(), p.getCreditAmount(), p.getPrice()))
                .toList(), userId == null ? null : credits.balance(userId), properties.configured());
    }

    public record CouponPreview(String code, BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal finalAmount) { }
    public CouponPreview preview(Long userId, String code, String productType, Long productId) {
        BigDecimal amount;
        if ("PREMIUM".equals(productType)) {
            PremiumPlan p = plans.findById(productId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy gói Premium."));
            if (!Boolean.TRUE.equals(p.getIsActive()) || FREE_PLAN.equalsIgnoreCase(p.getName()))
                throw new ApiException(ErrorCode.BAD_REQUEST, "Gói này hiện không bán.");
            amount = p.getPrice();
        } else if ("CREDIT".equals(productType)) {
            CreditPackage p = packages.findById(productId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy gói CP."));
            if (!Boolean.TRUE.equals(p.getIsActive())) throw new ApiException(ErrorCode.BAD_REQUEST, "Gói này hiện không bán.");
            amount = p.getPrice();
        } else throw new ApiException(ErrorCode.BAD_REQUEST, "Loại sản phẩm không hợp lệ.");
        vnd(amount);
        var q = coupons.preview(code, productType, amount, userId);
        return new CouponPreview(q.code(), q.originalAmount(), q.discountAmount(), q.finalAmount());
    }

    /** Reads an order of this buyer; a pending one is first checked with PayOS (throttled). */
    public OrderView order(Long userId, long orderCode) {
        Payment payment = owned(userId, orderCode);
        LocalDateTime now = LocalDateTime.now();
        if ("PENDING".equals(payment.getStatus()) && (payment.getCheckedAt() == null
                || payment.getCheckedAt().isBefore(now.minusSeconds(properties.checkThrottleSeconds())))) {
            try {
                check(payment.getId());
            } catch (ApiException exception) {
                // PayOS unreachable for a moment: keep showing the pending order, the next poll retries.
                log.info("PayOS check of {} failed: {}", orderCode, exception.getMessage());
            }
            payment = owned(userId, orderCode);
        }
        return OrderView.from(payment);
    }

    // ── Commands ────────────────────────────────────────────────────────────

    /** Opens a PayOS order for an active plan, or returns the buyer's still-payable order for it. */
    public OrderView start(Long userId, Long planId) {
        return start(userId, planId, null);
    }

    public OrderView start(Long userId, Long planId, String couponCode) {
        PremiumPlan plan = planId == null ? null : plans.findById(planId).orElse(null);
        if (plan == null || !Boolean.TRUE.equals(plan.getIsActive()) || FREE_PLAN.equalsIgnoreCase(plan.getName())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Gói này hiện không bán.");
        }
        vnd(plan.getPrice());
        String normalized = CouponService.normalize(couponCode);
        LocalDateTime now = LocalDateTime.now();
        Payment payment = tx.execute(status -> {
            var reuseQuote = coupons.quoteForReuse(normalized, "PREMIUM", plan.getPrice(), userId);
            var reusable = payments.findReusable(PROVIDER, userId, planId, now.plusMinutes(1)).stream()
                    .filter(p -> p.getAmountPaid().compareTo(reuseQuote.finalAmount()) == 0
                            && java.util.Objects.equals(p.getCouponCodeSnapshot(), reuseQuote.code())).findFirst();
            if (reusable.isPresent()) return reusable.get();
            var quote = coupons.reserve(normalized, "PREMIUM", plan.getPrice(), userId);
            return openOrder(userId, plan, null, quote, now);
        });
        if (payment.getCheckoutUrl() != null) return OrderView.from(payment);
        return createLink(payment, DESCRIPTION);
    }

    public OrderView startCredit(Long userId, Long packageId, String couponCode) {
        CreditPackage pack = packageId == null ? null : packages.findById(packageId).orElse(null);
        if (pack == null || !Boolean.TRUE.equals(pack.getIsActive()))
            throw new ApiException(ErrorCode.BAD_REQUEST, "Gói CP này hiện không bán.");
        vnd(pack.getPrice());
        String normalized = CouponService.normalize(couponCode);
        LocalDateTime now = LocalDateTime.now();
        Payment payment = tx.execute(status -> {
            var reuseQuote = coupons.quoteForReuse(normalized, "CREDIT", pack.getPrice(), userId);
            var reusable = payments.findReusableCredit(PROVIDER, userId, packageId, now.plusMinutes(1)).stream()
                    .filter(p -> p.getAmountPaid().compareTo(reuseQuote.finalAmount()) == 0
                            && java.util.Objects.equals(p.getCouponCodeSnapshot(), reuseQuote.code())).findFirst();
            if (reusable.isPresent()) return reusable.get();
            var quote = coupons.reserve(normalized, "CREDIT", pack.getPrice(), userId);
            return openOrder(userId, null, pack, quote, now);
        });
        if (payment.getCheckoutUrl() != null) return OrderView.from(payment);
        return createLink(payment, "KOTSU CP");
    }

    private OrderView createLink(Payment payment, String description) {
        long amount = vnd(payment.getAmountPaid());
        long orderCode = Long.parseLong(payment.getOrderCode());
        String page = properties.appBaseUrl() + "/premium/checkout/" + orderCode;
        PayOsClient.CreatedLink link;
        try {
            link = payos.create(orderCode, amount, description, page, page + "?cancel=1",
                    Instant.now().plusSeconds(properties.linkTtlMinutes() * 60L).getEpochSecond());
        } catch (ApiException exception) {
            payment.setStatus("FAILED");
            payment.setResponseCode("CREATE_FAILED");
            payments.save(payment);
            throw exception;
        }
        payment.setPaymentLinkId(link.paymentLinkId());
        payment.setCheckoutUrl(link.checkoutUrl());
        payment.setQrCode(link.qrCode());
        return OrderView.from(payments.save(payment));
    }

    /** The buyer gives up: cancel at PayOS, then settle from PayOS's answer (it may already be paid). */
    public OrderView cancel(Long userId, long orderCode) {
        Payment payment = owned(userId, orderCode);
        if (!"PENDING".equals(payment.getStatus())) return OrderView.from(payment);
        try {
            payos.cancel(orderCode, "Nguoi mua huy");
        } catch (ApiException exception) {
            log.info("PayOS cancel of {} failed, checking its status instead: {}", orderCode, exception.getMessage());
        }
        check(payment.getId());
        return OrderView.from(owned(userId, orderCode));
    }

    /** Server-side polling for buyers who closed the page: asks PayOS about pending orders. */
    @Scheduled(fixedDelayString = "${payos.reconcile-delay-ms:60000}", initialDelayString = "${payos.reconcile-delay-ms:60000}")
    public void reconcile() {
        if (!properties.configured()) return;
        LocalDateTime now = LocalDateTime.now();
        for (Long id : payments.findDueForCheck(PROVIDER, now.minusSeconds(20), now.minusSeconds(30), Limit.of(20))) {
            try {
                check(id);
            } catch (RuntimeException exception) {
                log.warn("PayOS check of payment {} failed: {}", id, exception.getMessage());
            }
        }
    }

    /** Reads the order from PayOS and applies the result while the payment row is locked. */
    void check(Long paymentId) {
        Payment current = payments.findById(paymentId).orElseThrow();
        if (!"PENDING".equals(current.getStatus())) return; // settled: no need to ask PayOS again
        String orderCode = current.getOrderCode();
        PayOsClient.RemotePayment remote = payos.fetch(Long.parseLong(orderCode));
        tx.executeWithoutResult(status -> {
            Payment payment = payments.findForUpdate(paymentId).orElseThrow();
            if (!"PENDING".equals(payment.getStatus())) return; // already settled: never grant twice
            LocalDateTime now = LocalDateTime.now();
            payment.setCheckedAt(now);
            long amount = vnd(payment.getAmountPaid());
            switch (remote.status()) {
                case "PAID" -> {
                    if (remote.amount() == amount && remote.amountPaid() >= amount) {
                        if ("CREDIT".equals(payment.getProductType())) grantCredit(payment, remote.reference(), now);
                        else grant(payment, remote.reference(), now);
                    } else {
                        log.warn("PayOS order {} is PAID with amount {}/{} but expected {}", orderCode,
                                remote.amountPaid(), remote.amount(), amount);
                        payment.setStatus("FAILED");
                        payment.setResponseCode("AMOUNT_MISMATCH");
                    }
                }
                case "CANCELLED" -> {
                    payment.setStatus("CANCELLED");
                    payment.setResponseCode("CANCELLED");
                }
                case "EXPIRED" -> {
                    payment.setStatus("EXPIRED");
                    payment.setResponseCode("EXPIRED");
                }
                default -> { /* PENDING / PROCESSING: keep waiting */ }
            }
        });
    }

    private void grant(Payment payment, String bankReference, LocalDateTime now) {
        User user = payments.lockUser(payment.getUser().getId()).orElseThrow();
        // Same rule as manual grants: days stack on a running Premium period, otherwise start now.
        boolean active = current(user).premiumActive();
        LocalDateTime until = (active ? user.getPremiumExpiredAt() : now).plusDays(payment.getDurationDaysSnapshot());
        user.setPremiumPlan(payment.getPremiumPlan());
        user.setPremiumExpiredAt(until);
        payment.setStatus("PAID");
        payment.setResponseCode("00");
        payment.setPaidAt(now);
        // PayOS's payment link id is unique per order (the column is unique); the bank reference is only logged.
        payment.setProviderTransactionId(payment.getPaymentLinkId());
        log.info("PayOS order {} paid, bank reference {}", payment.getOrderCode(), bankReference);
        payment.setPremiumUntil(until);
        notifications.notifyUser(user.getId(), "PREMIUM", "Thanh toán thành công",
                "Gói " + payment.getPlanNameSnapshot() + " có hiệu lực đến " + DATE.format(until) + ".",
                "/premium/billing", "PREMIUM_PAID_" + payment.getOrderCode());
    }

    private void grantCredit(Payment payment, String bankReference, LocalDateTime now) {
        credits.earn(payment.getUser().getId(), payment.getCreditAmountSnapshot(), CreditReason.CREDIT_PURCHASE,
                "PAYMENT", payment.getId(), "Mua " + payment.getCreditAmountSnapshot() + " CP");
        payment.setStatus("PAID");
        payment.setResponseCode("00");
        payment.setPaidAt(now);
        payment.setProviderTransactionId(payment.getPaymentLinkId());
        log.info("PayOS CP order {} paid, bank reference {}", payment.getOrderCode(), bankReference);
        notifications.notifyUser(payment.getUser().getId(), "CREDIT", "Đã cộng " + payment.getCreditAmountSnapshot() + " CP",
                "Thanh toán gói " + payment.getPlanNameSnapshot() + " thành công.",
                "/premium/billing", "CREDIT_PAID_" + payment.getOrderCode());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Payment openOrder(Long userId, PremiumPlan plan, CreditPackage pack, CouponService.Quote quote, LocalDateTime now) {
        for (int attempt = 0; ; attempt++) {
            // Unique per provider (DB key); millisecond-based, well under PayOS's 2^53 order code limit.
            long orderCode = (System.currentTimeMillis() % 1_000_000_000_000L) * 100
                    + ThreadLocalRandom.current().nextInt(100);
            try {
                return payments.saveAndFlush(Payment.builder().user(users.getReferenceById(userId)).premiumPlan(plan)
                        .productType(plan == null ? "CREDIT" : "PREMIUM").creditPackage(pack)
                        .creditAmountSnapshot(pack == null ? null : pack.getCreditAmount())
                        .coupon(quote.coupon()).couponCodeSnapshot(quote.code())
                        .originalAmount(quote.originalAmount()).discountAmount(quote.discountAmount())
                        .provider(PROVIDER).orderCode(Long.toString(orderCode)).status("PENDING")
                        .amountPaid(quote.finalAmount()).currency("VND").planNameSnapshot(plan == null ? pack.getName() : plan.getName())
                        .durationDaysSnapshot(plan == null ? null : plan.getDurationDays())
                        .expiresAt(now.plusMinutes(properties.linkTtlMinutes())).build());
            } catch (DataIntegrityViolationException duplicate) {
                if (attempt >= 2) throw duplicate;
            }
        }
    }

    private Payment owned(Long userId, long orderCode) {
        return payments.findByProviderAndOrderCodeAndUser_Id(PROVIDER, Long.toString(orderCode), userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn thanh toán."));
    }

    private CurrentPlan current(User user) {
        PremiumPlan plan = user.getPremiumPlan();
        boolean active = plan != null && !FREE_PLAN.equalsIgnoreCase(plan.getName())
                && user.getPremiumExpiredAt() != null && user.getPremiumExpiredAt().isAfter(LocalDateTime.now());
        return new CurrentPlan(active ? plan.getName() : null, active, active ? user.getPremiumExpiredAt() : null);
    }

    /** PayOS takes whole VND amounts. */
    private static long vnd(BigDecimal price) {
        if (price == null || price.signum() <= 0 || price.stripTrailingZeros().scale() > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Giá gói chưa hợp lệ để thanh toán (cần số tiền VND nguyên).");
        }
        return price.longValueExact();
    }
}
