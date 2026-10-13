package com.jlas.billing;

import com.jlas.auth.entity.Payment;
import com.jlas.auth.entity.PremiumPlan;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.PaymentRepository;
import com.jlas.auth.repository.PremiumPlanRepository;
import com.jlas.auth.repository.RoleRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.util.JwtUtil;
import com.jlas.billing.payos.PayOsClient;
import com.jlas.billing.payos.PayOsClient.RemotePayment;
import com.jlas.billing.service.PremiumCheckoutService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.config.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JLAS2-49: PayOS checkout without webhooks. PayOS itself is mocked; every result comes from the server's check. */
@SpringBootTest(properties = {"spring.config.location=classpath:/application-test.yaml", "payos.client-id=test-client", "payos.api-key=test-api",
        "payos.checksum-key=test-checksum-key", "payos.reconcile-delay-ms=3600000"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PremiumCheckoutIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PremiumPlanRepository plans;
    @Autowired PaymentRepository payments;
    @Autowired PremiumCheckoutService checkout;
    @Autowired AuthProperties authProperties;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PayOsClient payos;

    private User buyer;
    private PremiumPlan monthly;
    private PremiumPlan yearly;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM user_notification");
        payments.deleteAll();
        users.deleteAll();
        plans.deleteAll();
        plans.save(plan("FREE", "0", 36500, true));
        monthly = plans.save(plan("MONTHLY", "10000", 30, true));
        yearly = plans.save(plan("YEARLY", "100000", 365, true));
        buyer = user("buyer");
        when(payos.create(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(call -> new PayOsClient.CreatedLink("link-" + call.getArgument(0),
                        "https://pay.payos.vn/web/" + call.getArgument(0), "000201-qr", "PENDING"));
    }

    @Test
    void pricingIsPublicAndOnlyListsPlansOnSale() throws Exception {
        plans.save(plan("OLD", "5000", 7, false));
        mvc.perform(get("/api/premium/plans")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plans.length()").value(2))
                .andExpect(jsonPath("$.data.plans[0].name").value("MONTHLY"))
                .andExpect(jsonPath("$.data.current").doesNotExist())
                .andExpect(jsonPath("$.data.paymentsEnabled").value(true))
                .andExpect(jsonPath("$.data.aiCall.premiumDailyMinutes").exists());
        // An account on the FREE plan (lazy plan reference) is not Premium.
        buyer.setPremiumPlan(plans.findAll().stream().filter(p -> p.getName().equals("FREE")).findFirst().orElseThrow());
        users.save(buyer);
        mvc.perform(get("/api/premium/plans").header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.current.premiumActive").value(false));
        mvc.perform(post("/api/premium/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"planId\":" + monthly.getId() + "}")).andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutOpensOnePendingOrderAndReusesIt() throws Exception {
        long code = start(buyer, monthly).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.amount").value(10000))
                .andExpect(jsonPath("$.data.checkoutUrl").exists())
                .andReturn().getResponse().getContentAsString().transform(PremiumCheckoutIntegrationTest::orderCode);
        start(buyer, monthly).andExpect(jsonPath("$.data.orderCode").value(code));
        verify(payos, times(1)).create(eq(code), eq(10000L), anyString(), anyString(), anyString(), anyLong());
        Payment saved = payments.findAll().getFirst();
        assertThat(saved.getPlanNameSnapshot()).isEqualTo("MONTHLY");
        assertThat(saved.getDurationDaysSnapshot()).isEqualTo(30);
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(10));
    }

    @Test
    void paidOrderGrantsPremiumExactlyOnce() throws Exception {
        long code = orderCode(start(buyer, monthly).andReturn().getResponse().getContentAsString());
        when(payos.fetch(code)).thenReturn(new RemotePayment(code, 10000, 10000, "PAID", "FT123"));
        LocalDateTime before = LocalDateTime.now();
        mvc.perform(get("/api/premium/orders/{code}", code).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.checkoutUrl").doesNotExist());
        LocalDateTime granted = users.findById(buyer.getId()).orElseThrow().getPremiumExpiredAt();
        assertThat(granted).isBetween(before.plusDays(30), LocalDateTime.now().plusDays(30));
        // Repeated and forced checks never add days again.
        Long id = payments.findAll().getFirst().getId();
        checkout.order(buyer.getId(), code);
        invokeCheck(id);
        invokeCheck(id);
        User after = users.findById(buyer.getId()).orElseThrow();
        assertThat(after.getPremiumExpiredAt()).isEqualTo(granted);
        assertThat(planOf(buyer)).isEqualTo("MONTHLY");
        Payment paid = payments.findById(id).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getPremiumUntil()).isEqualTo(granted);
        assertThat(paid.getProviderTransactionId()).isEqualTo("link-" + code);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE user_id = ? AND type = 'PREMIUM'",
                Long.class, buyer.getId())).isEqualTo(1L);
        verify(payos, times(1)).fetch(code);
    }

    @Test
    void paidDaysStackOnARunningPremium() throws Exception {
        LocalDateTime running = LocalDateTime.now().plusDays(10).withNano(0);
        buyer.setPremiumPlan(monthly);
        buyer.setPremiumExpiredAt(running);
        users.save(buyer);
        long code = orderCode(start(buyer, yearly).andReturn().getResponse().getContentAsString());
        when(payos.fetch(code)).thenReturn(new RemotePayment(code, 100000, 100000, "PAID", "FT9"));
        checkout.order(buyer.getId(), code);
        User after = users.findById(buyer.getId()).orElseThrow();
        assertThat(after.getPremiumExpiredAt()).isEqualTo(running.plusDays(365));
        assertThat(planOf(buyer)).isEqualTo("YEARLY");
    }

    @Test
    void underpaidCancelledOrExpiredOrdersGrantNothing() throws Exception {
        long underpaid = orderCode(start(buyer, monthly).andReturn().getResponse().getContentAsString());
        when(payos.fetch(underpaid)).thenReturn(new RemotePayment(underpaid, 10000, 1000, "PAID", "FT1"));
        assertThat(checkout.order(buyer.getId(), underpaid).status()).isEqualTo("FAILED");

        long cancelled = orderCode(start(buyer, monthly).andReturn().getResponse().getContentAsString());
        when(payos.fetch(cancelled)).thenReturn(new RemotePayment(cancelled, 10000, 0, "CANCELLED", null));
        assertThat(checkout.order(buyer.getId(), cancelled).status()).isEqualTo("CANCELLED");

        long expired = orderCode(start(buyer, yearly).andReturn().getResponse().getContentAsString());
        when(payos.fetch(expired)).thenReturn(new RemotePayment(expired, 100000, 0, "EXPIRED", null));
        assertThat(checkout.order(buyer.getId(), expired).status()).isEqualTo("EXPIRED");

        // A PAID answer for a different amount (forged or mismatched order) does not count either.
        long mismatched = orderCode(start(buyer, yearly).andReturn().getResponse().getContentAsString());
        when(payos.fetch(mismatched)).thenReturn(new RemotePayment(mismatched, 1000, 1000, "PAID", "FT2"));
        assertThat(checkout.order(buyer.getId(), mismatched).status()).isEqualTo("FAILED");

        assertThat(users.findById(buyer.getId()).orElseThrow().getPremiumExpiredAt()).isNull();
    }

    @Test
    void stillPendingOrderStaysPendingAndOthersCannotSeeIt() throws Exception {
        long code = orderCode(start(buyer, monthly).andReturn().getResponse().getContentAsString());
        when(payos.fetch(code)).thenReturn(new RemotePayment(code, 10000, 0, "PENDING", null));
        mvc.perform(get("/api/premium/orders/{code}", code).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.qrCode").value("000201-qr"));
        mvc.perform(get("/api/premium/orders/{code}", code).header("Authorization", bearer(user("stranger"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/premium/orders").header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void cancelSettlesFromPayOsAnswer() throws Exception {
        long code = orderCode(start(buyer, monthly).andReturn().getResponse().getContentAsString());
        when(payos.fetch(code)).thenReturn(new RemotePayment(code, 10000, 0, "CANCELLED", null));
        mvc.perform(post("/api/premium/orders/{code}/cancel", code).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        verify(payos).cancel(eq(code), anyString());

        // Paid at the last second: cancelling still ends as PAID with Premium granted.
        long late = orderCode(start(buyer, yearly).andReturn().getResponse().getContentAsString());
        when(payos.cancel(eq(late), anyString())).thenThrow(new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "paid"));
        when(payos.fetch(late)).thenReturn(new RemotePayment(late, 100000, 100000, "PAID", "FT7"));
        mvc.perform(post("/api/premium/orders/{code}/cancel", late).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAID"));
        assertThat(users.findById(buyer.getId()).orElseThrow().getPremiumExpiredAt()).isNotNull();
    }

    @Test
    void backgroundCheckGrantsWhenTheBuyerClosedThePage() throws Exception {
        long code = orderCode(start(buyer, monthly).andReturn().getResponse().getContentAsString());
        jdbc.update("UPDATE payments SET created_at = ? WHERE order_code = ?",
                LocalDateTime.now().minusMinutes(2), Long.toString(code));
        when(payos.fetch(code)).thenReturn(new RemotePayment(code, 10000, 10000, "PAID", "FT5"));
        checkout.reconcile();
        assertThat(payments.findAll().getFirst().getStatus()).isEqualTo("PAID");
        assertThat(users.findById(buyer.getId()).orElseThrow().getPremiumExpiredAt()).isNotNull();
    }

    @Test
    void rejectsPlansNotForSaleAndRecordsGatewayFailures() throws Exception {
        PremiumPlan free = plans.findAll().stream().filter(p -> p.getName().equals("FREE")).findFirst().orElseThrow();
        start(buyer, free).andExpect(status().isBadRequest());
        start(buyer, plans.save(plan("STOPPED", "5000", 7, false))).andExpect(status().isBadRequest());
        start(buyer, plans.save(plan("ODD", "9.99", 30, true))).andExpect(status().isConflict());
        when(payos.create(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "down"));
        start(buyer, monthly).andExpect(status().isBadGateway());
        assertThat(payments.findAll()).singleElement().satisfies(p -> {
            assertThat(p.getStatus()).isEqualTo("FAILED");
            assertThat(p.getResponseCode()).isEqualTo("CREATE_FAILED");
        });
    }

    private String planOf(User user) {
        return jdbc.queryForObject("SELECT p.name FROM users u JOIN premium_plans p ON p.id = u.premium_plan_id WHERE u.id = ?",
                String.class, user.getId());
    }

    private void invokeCheck(Long id) throws Exception {
        var method = PremiumCheckoutService.class.getDeclaredMethod("check", Long.class);
        method.setAccessible(true);
        method.invoke(checkout, id);
    }

    private ResultActions start(User user, PremiumPlan plan) throws Exception {
        return mvc.perform(post("/api/premium/checkout").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":" + plan.getId() + "}"));
    }

    private static long orderCode(String json) {
        var matcher = java.util.regex.Pattern.compile("\"orderCode\":(\\d+)").matcher(json);
        if (!matcher.find()) throw new IllegalStateException("No order code in " + json);
        return Long.parseLong(matcher.group(1));
    }

    private User user(String name) {
        Role role = roles.findByName("LEARNER").orElseGet(() -> roles.save(Role.builder().name("LEARNER").build()));
        return users.save(User.builder().email(name + "@example.test").role(role).loginType("LOCAL")
                .passwordHash("not-used").isVerified(true).build());
    }

    private static PremiumPlan plan(String name, String price, int days, boolean active) {
        return PremiumPlan.builder().name(name).price(new BigDecimal(price)).durationDays(days).isActive(active).build();
    }

    private String bearer(User user) {
        return "Bearer " + JwtUtil.generateToken(user.getEmail(), authProperties.jwtSecret(), authProperties.accessTokenTtl());
    }
}
