package com.jlas.admin;

import com.jlas.admin.repository.AdminAuditLogRepository;
import com.jlas.admin.service.AdminAuditService;
import com.jlas.admin.service.AdminUserService;
import com.jlas.auth.dto.LoginRequest;
import com.jlas.auth.dto.LoginResponse;
import com.jlas.auth.entity.Payment;
import com.jlas.auth.entity.PremiumPlan;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.AuthTokenRepository;
import com.jlas.auth.repository.PaymentRepository;
import com.jlas.auth.repository.PremiumPlanRepository;
import com.jlas.auth.repository.RoleRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.service.AuthService;
import com.jlas.auth.service.EmailService;
import com.jlas.auth.service.GoogleTokenService;
import com.jlas.auth.util.JwtUtil;
import com.jlas.billing.repository.CouponRepository;
import com.jlas.billing.repository.CreditPackageRepository;
import com.jlas.billing.entity.Coupon;
import com.jlas.billing.entity.CreditPackage;
import com.jlas.common.exception.ApiException;
import com.jlas.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.location=classpath:/application-test.yaml")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SystemAdminIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AuthTokenRepository tokens;
    @Autowired AdminAuditLogRepository logs;
    @Autowired AuthService auth;
    @Autowired PremiumPlanRepository plans;
    @Autowired PaymentRepository payments;
    @Autowired CouponRepository coupons;
    @Autowired CreditPackageRepository packages;
    @Autowired AdminUserService admin;
    @Autowired AdminAuditService audit;
    @Autowired AuthProperties authProperties;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailService email;
    @MockitoBean GoogleTokenService google;

    private User superAdmin;
    private User learner;
    private String adminToken;
    private static final String PASSWORD = "Dummy-test-password-123";

    @BeforeEach
    void setup() {
        logs.deleteAll();
        tokens.deleteAll();
        jdbc.update("DELETE FROM user_notification");
        jdbc.update("DELETE FROM user_credit_transaction");
        payments.deleteAll();
        coupons.deleteAll();
        packages.deleteAll();
        users.deleteAll();
        plans.deleteAll();
        superAdmin = createUser("super", "SUPER_ADMIN");
        learner = createUser("learner", "LEARNER");
        adminToken = jwt(superAdmin);
    }

    @Test
    void fourRolesShareTheAdminAreaAsDecided() throws Exception {
        User moderator = createUser("moderator", "MODERATOR");
        User creator = createUser("creator", "CONTENT_CREATOR");
        for (User denied : new User[]{learner, moderator, creator}) {
            for (String page : new String[]{"/api/admin/system/users", "/api/admin/system/audit-logs",
                    "/api/admin/system/dashboard", "/api/admin/system/premium-plans", "/api/admin/system/payments",
                    "/admin/system/users"}) {
                mvc.perform(get(page).header("Authorization", bearer(jwt(denied))))
                        .andExpect(status().isForbidden());
            }
        }
        // System Admin also runs moderation, including user sanctions.
        for (String page : new String[]{"/api/admin/system/users", "/api/admin/moderation/queue",
                "/api/admin/moderation/appeals", "/api/admin/moderation/denylist",
                "/api/admin/moderation/sanctions/active", "/admin/moderation/queue"}) {
            mvc.perform(get(page).header("Authorization", bearer(adminToken))).andExpect(status().isOk());
        }
        // Moderators moderate but cannot sanction users; learners and content creators stay out.
        mvc.perform(get("/api/admin/moderation/queue").header("Authorization", bearer(jwt(moderator))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/moderation/sanctions/active").header("Authorization", bearer(jwt(moderator))))
                .andExpect(status().isForbidden());
        for (User denied : new User[]{learner, creator}) {
            mvc.perform(get("/api/admin/moderation/queue").header("Authorization", bearer(jwt(denied))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/system/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/system/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin%2Fsystem%2Fusers"));
    }

    @Test
    void eachRoleLandsOnItsOwnAdminArea() throws Exception {
        User moderator = createUser("moderator", "MODERATOR");
        mvc.perform(get("/admin").header("Authorization", bearer(adminToken)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/system/dashboard"));
        for (String page : new String[]{"/admin/system/dashboard", "/admin/system/users", "/admin/system/plans",
                "/admin/system/payments", "/admin/system/audit"}) {
            mvc.perform(get(page).header("Authorization", bearer(adminToken)))
                    .andExpect(status().isOk()).andExpect(model().attribute("section", "system-" + page.substring(14)));
            mvc.perform(get(page).header("Authorization", bearer(jwt(moderator))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/admin/system/unknown").header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin").header("Authorization", bearer(jwt(moderator))))
                .andExpect(status().isOk()).andExpect(model().attribute("section", "overview"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lockedTokenAllowsPublicLoginButRejectsProtectedRequests(boolean cookie) throws Exception {
        String lockedToken = login(learner).getAccessToken();
        admin.lock(superAdmin.getId(), learner.getId(), "Kiểm tra đăng nhập");
        RequestPostProcessor withLockedToken = request -> {
            if (cookie) request.setCookies(new Cookie("access_token", lockedToken));
            else request.addHeader("Authorization", bearer(lockedToken));
            return request;
        };
        mvc.perform(get("/login").with(withLockedToken))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mvc.perform(post("/api/auth/login").with(withLockedToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + superAdmin.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.email").value(superAdmin.getEmail()))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        mvc.perform(get("/api/auth/me").with(withLockedToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        mvc.perform(get("/admin/system/users").with(withLockedToken))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?locked=1"));
    }

    @Test
    void refreshRejectsLockedAccountEvenWhenTokenIsStillActive() throws Exception {
        String refreshToken = login(learner).getRefreshToken();
        // Model an active token that overlaps the account lock: the account check
        // must still reject it independently of refresh-token revocation.
        learner.setLockedAt(LocalDateTime.now());
        users.save(learner);
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void lockRevokesSessionsBlocksAllLoginPathsAndUnlockRestoresLogin() throws Exception {
        LoginResponse first = login(learner);
        LoginResponse second = login(learner);
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(first.getAccessToken())))
                .andExpect(status().isOk());
        action(learner.getId(), "lock", "  Kiểm tra khóa  ", 200);
        assertThat(tokens.findByUser_IdAndTokenTypeAndUsedAtIsNullAndRevokedAtIsNull(learner.getId(), "REFRESH")).isEmpty();
        assertThat(tokens.findTop20ByUser_IdAndTokenTypeOrderByCreatedAtDesc(learner.getId(), "REFRESH"))
                .hasSize(2).allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
        assertThat(users.findById(learner.getId()).orElseThrow().getLockedReason()).isEqualTo("Kiểm tra khóa");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + learner.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + learner.getEmail() + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        for (String token : new String[]{first.getRefreshToken(), second.getRefreshToken()}) {
            mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + token + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(first.getAccessToken())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        when(google.verify("dummy-google-token")).thenReturn(new GoogleTokenService.GoogleUserInfo(
                "dummy-google-id", learner.getEmail(), "Test", null));
        mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"dummy-google-token\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        assertThat(users.findById(learner.getId()).orElseThrow().getGoogleId()).isNull();
        action(learner.getId(), "unlock", "Đã kiểm tra", 200);
        assertThat(login(learner).getAccessToken()).isNotBlank();
        User unlocked = users.findById(learner.getId()).orElseThrow();
        assertThat(unlocked.getLockedAt()).isNull();
        assertThat(unlocked.getLockedReason()).isNull();
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first.getRefreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
        var entries = audit.list(superAdmin.getId(), null, "USER", learner.getId().toString(), "SUCCESS", null, null, 0, 20);
        assertThat(entries.getContent()).hasSize(2);
        assertThat(entries.getContent()).allSatisfy(entry -> {
            assertThat(entry.actorId()).isEqualTo(superAdmin.getId());
            assertThat(entry.actorEmail()).isEqualTo(superAdmin.getEmail());
            assertThat(entry.targetId()).isEqualTo(learner.getId().toString());
            assertThat(entry.changesJson()).contains("before", "after", "ACTIVE", "LOCKED");
        });
        assertThat(entries.getContent().get(0).action()).isEqualTo("USER_UNLOCK");
        assertThat(entries.getContent().get(0).reason()).isEqualTo("Đã kiểm tra");
        assertThat(entries.getContent().get(1).action()).isEqualTo("USER_LOCK");
        assertThat(entries.getContent().get(1).reason()).isEqualTo("Kiểm tra khóa");
    }

    @Test
    void selfLockAndLastAdminRejectionsSurviveRollback() throws Exception {
        action(superAdmin.getId(), "lock", "Không tự khóa", 409);
        // The last-admin guard is defensive: a second unlocked SUPER_ADMIN actor would
        // itself make the target no longer the last one. Exercise it at the service boundary.
        assertThatThrownBy(() -> admin.lock(learner.getId(), superAdmin.getId(), "Kiểm tra cuối"))
                .isInstanceOf(ApiException.class).hasMessageContaining("cuối cùng");
        assertThat(users.findById(superAdmin.getId()).orElseThrow().getLockedAt()).isNull();
        var rejected = audit.list(null, "USER_LOCK", "USER", superAdmin.getId().toString(), "REJECTED", null, null, 0, 20);
        assertThat(rejected.getTotalElements()).isEqualTo(2);
        assertThat(rejected.getContent()).extracting(entry -> entry.reason())
                .containsExactlyInAnyOrder("Không tự khóa", "Kiểm tra cuối");
    }

    @Test
    void repeatedActionsConflictAndDoNotChangeAccountState() throws Exception {
        action(learner.getId(), "unlock", "Chưa bị khóa", 409);
        action(learner.getId(), "lock", "Lý do ban đầu", 200);
        LocalDateTime lockedAt = users.findById(learner.getId()).orElseThrow().getLockedAt();
        action(learner.getId(), "lock", "Không ghi đè", 409);
        User locked = users.findById(learner.getId()).orElseThrow();
        assertThat(locked.getLockedAt()).isEqualTo(lockedAt);
        assertThat(locked.getLockedReason()).isEqualTo("Lý do ban đầu");
        action(learner.getId(), "unlock", "Mở khóa lại", 200);
        action(learner.getId(), "unlock", "Lặp mở khóa", 409);
    }

    @Test
    void reasonValidationRejectsMissingShortAndOversizedBodies() throws Exception {
        for (String operation : new String[]{"lock", "unlock"}) {
            mvc.perform(post("/api/admin/system/users/{id}/" + operation, learner.getId())
                            .header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
            for (String reason : new String[]{"abc", "    ab    ", " ", "x".repeat(501)}) {
                action(learner.getId(), operation, reason, 400);
            }
        }
        assertThat(logs.count()).isZero();
    }

    @Test
    void listsFilterPageAndNeverExposeCredentials() throws Exception {
        learner.setFullName("Nguyen Test Name");
        learner.setUsername("sample_user");
        learner.setPhoneNumber("0000000000");
        learner.setPremiumPlan(plan("MONTHLY"));
        learner.setPremiumExpiredAt(LocalDateTime.now().plusDays(1));
        learner.setGoogleId("dummy-private-google-id");
        users.save(learner);
        for (String q : new String[]{"LEARNER@", "SAMPLE_", "TEST NAME"}) {
            mvc.perform(get("/api/admin/system/users").header("Authorization", bearer(adminToken))
                            .param("q", q).param("role", "LEARNER").param("status", "ACTIVE")
                            .param("premium", "true").param("size", "999"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.size").value(100))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.content").doesNotExist())
                    .andExpect(jsonPath("$.data.items[0].premiumActive").value(true))
                    .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist())
                    .andExpect(jsonPath("$.data.items[0].googleId").doesNotExist())
                    .andExpect(jsonPath("$.data.items[0].phoneNumber").doesNotExist());
        }
        mvc.perform(get("/api/admin/system/users/{id}", learner.getId()).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phoneNumber").value("0000000000"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.googleId").doesNotExist());
        action(learner.getId(), "lock", "Lọc trạng thái", 200);
        mvc.perform(get("/api/admin/system/users").header("Authorization", bearer(adminToken)).param("status", "LOCKED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/admin/system/users").header("Authorization", bearer(adminToken)).param("premium", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/admin/system/audit-logs").header("Authorization", bearer(adminToken))
                        .param("actorId", superAdmin.getId().toString()).param("action", "USER_LOCK")
                        .param("targetType", "USER").param("targetId", learner.getId().toString())
                        .param("result", "SUCCESS").param("from", LocalDateTime.now().minusMinutes(1).toString())
                        .param("to", LocalDateTime.now().plusMinutes(1).toString()).param("size", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.content").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].actorEmail").value(superAdmin.getEmail()));
        for (String endpoint : new String[]{"/api/admin/system/users", "/api/admin/system/audit-logs"}) {
            mvc.perform(get(endpoint).header("Authorization", bearer(adminToken))
                            .param("page", "2").param("size", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2))
                    .andExpect(jsonPath("$.data.size").value(1))
                    .andExpect(jsonPath("$.data.totalElements").value(endpoint.endsWith("users") ? 2 : 1))
                    .andExpect(jsonPath("$.data.totalPages").value(endpoint.endsWith("users") ? 2 : 1))
                    .andExpect(jsonPath("$.data.items").isEmpty());
        }
        mvc.perform(get("/api/admin/system/users").header("Authorization", bearer(adminToken)).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/system/users/999999").header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void successfulAuditAndAccountChangeRollBackTogether() {
        login(learner);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            admin.lock(superAdmin.getId(), learner.getId(), "Thử rollback");
            status.setRollbackOnly();
        });
        assertThat(logs.count()).isZero();
        assertThat(users.findById(learner.getId()).orElseThrow().getLockedAt()).isNull();
        assertThat(tokens.findByUser_IdAndTokenTypeAndUsedAtIsNullAndRevokedAtIsNull(learner.getId(), "REFRESH"))
                .hasSize(1);
    }

    @Test
    void canLockAnotherAdminButLockedAdminsDoNotCountAsAvailable() {
        User secondAdmin = createUser("second-super", "SUPER_ADMIN");
        admin.lock(superAdmin.getId(), secondAdmin.getId(), "Khóa quản trị");
        assertThatThrownBy(() -> admin.lock(learner.getId(), superAdmin.getId(), "Kiểm tra cuối"))
                .isInstanceOf(ApiException.class).hasMessageContaining("cuối cùng");
        assertThat(users.findById(superAdmin.getId()).orElseThrow().getLockedAt()).isNull();
    }

    @Test
    void inFlightProfileUpdateCannotClearAnAdminLock() throws Exception {
        CountDownLatch profileLoaded = new CountDownLatch(1);
        CountDownLatch lockCommitted = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var update = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                User staleUser = users.findById(learner.getId()).orElseThrow();
                profileLoaded.countDown();
                try {
                    if (!lockCommitted.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Lock timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                staleUser.setFullName("Updated profile");
            }));
            try {
                assertThat(profileLoaded.await(10, TimeUnit.SECONDS)).isTrue();
                admin.lock(superAdmin.getId(), learner.getId(), "Khóa đồng thời");
            } finally {
                lockCommitted.countDown();
            }
            update.get(10, TimeUnit.SECONDS);
        }
        User updated = users.findById(learner.getId()).orElseThrow();
        assertThat(updated.getFullName()).isEqualTo("Updated profile");
        assertThat(updated.getLockedAt()).isNotNull();
        assertThat(updated.getLockedReason()).isEqualTo("Khóa đồng thời");
    }

    @Test
    void roleChangeAppliesImmediatelyAndIsAudited() throws Exception {
        String learnerToken = jwt(learner);
        mvc.perform(get("/api/admin/system/users").header("Authorization", bearer(learnerToken)))
                .andExpect(status().isForbidden());
        roleAction(learner.getId(), "SUPER_ADMIN", "Thăng quyền để kiểm tra", 200)
                .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"));
        // Authorities are rebuilt per request: the same token now carries the new role.
        mvc.perform(get("/api/admin/system/users").header("Authorization", bearer(learnerToken)))
                .andExpect(status().isOk());
        roleAction(learner.getId(), "SUPER_ADMIN", "Lặp lại vai trò", 409);
        roleAction(learner.getId(), "CONTENT_ADMIN", "Vai trò cũ không được gán", 400);
        roleAction(learner.getId(), "ADMIN", "Vai trò cũ không được gán", 400);
        roleAction(learner.getId(), "ROOT", "Vai trò lạ", 400);
        roles.findByName("CONTENT_CREATOR").ifPresent(roles::delete); // other tests may have created it
        roleAction(learner.getId(), "CONTENT_CREATOR", "Chưa có trong DB", 400);
        roleAction(superAdmin.getId(), "LEARNER", "Tự hạ quyền", 409);
        roleAction(learner.getId(), "LEARNER", "Hạ quyền lại", 200);
        assertThatThrownBy(() -> admin.changeRole(learner.getId(), superAdmin.getId(), "LEARNER", "Hạ người cuối"))
                .isInstanceOf(ApiException.class).hasMessageContaining("cuối cùng");
        mvc.perform(get("/api/admin/system/users/{id}", superAdmin.getId()).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"));
        var success = audit.list(null, "USER_ROLE_CHANGE", "USER", learner.getId().toString(), "SUCCESS", null, null, 0, 20);
        assertThat(success.getContent()).hasSize(2);
        assertThat(success.getContent().get(1).changesJson()).contains("LEARNER", "SUPER_ADMIN");
        var rejected = audit.list(null, "USER_ROLE_CHANGE", null, null, "REJECTED", null, null, 0, 20);
        assertThat(rejected.getTotalElements()).isEqualTo(3);
    }

    @Test
    void premiumGrantStacksWithoutPaymentAndNotifiesUser() throws Exception {
        PremiumPlan monthly = plan("MONTHLY");
        PremiumPlan free = plan("FREE");
        LocalDateTime before = LocalDateTime.now();
        grant(learner.getId(), monthly.getId(), 30, "Bù lỗi thanh toán", 200)
                .andExpect(jsonPath("$.data.premiumActive").value(true))
                .andExpect(jsonPath("$.data.premiumPlanName").value("MONTHLY"));
        LocalDateTime first = users.findById(learner.getId()).orElseThrow().getPremiumExpiredAt();
        assertThat(first).isBetween(before.plusDays(30), LocalDateTime.now().plusDays(30));
        grant(learner.getId(), monthly.getId(), 10, "Tặng thêm sự kiện", 200);
        assertThat(users.findById(learner.getId()).orElseThrow().getPremiumExpiredAt()).isEqualTo(first.plusDays(10));

        // An expired period does not stack: it restarts from now.
        User expired = createUser("expired", "LEARNER");
        expired.setPremiumPlan(monthly);
        expired.setPremiumExpiredAt(LocalDateTime.now().minusDays(5));
        users.save(expired);
        LocalDateTime start = LocalDateTime.now();
        grant(expired.getId(), monthly.getId(), 7, "Gia hạn cho tài khoản cũ", 200);
        assertThat(users.findById(expired.getId()).orElseThrow().getPremiumExpiredAt())
                .isBetween(start.plusDays(7), LocalDateTime.now().plusDays(7));

        grant(learner.getId(), free.getId(), 30, "Gói miễn phí", 400);
        grant(learner.getId(), 999999L, 30, "Gói không tồn tại", 400);
        grant(learner.getId(), monthly.getId(), 0, "Thiếu số ngày", 400);
        grant(learner.getId(), monthly.getId(), 366, "Quá số ngày", 400);
        grant(superAdmin.getId(), monthly.getId(), 30, "Tự cấp cho mình", 409);

        assertThat(payments.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE user_id = ? AND type = 'PREMIUM'",
                Long.class, learner.getId())).isEqualTo(2L);
        var granted = audit.list(superAdmin.getId(), "PREMIUM_GRANT", "USER", learner.getId().toString(), "SUCCESS",
                null, null, 0, 20);
        assertThat(granted.getContent()).hasSize(2);
        assertThat(granted.getContent().get(0).changesJson()).contains("\"stacked\":true", "\"durationDays\":10");
        assertThat(granted.getContent().get(1).changesJson()).contains("\"stacked\":false");
    }

    @Test
    void freePlanIsNotPremium() throws Exception {
        learner.setPremiumPlan(plan("FREE"));
        learner.setPremiumExpiredAt(LocalDateTime.now().plusYears(10));
        users.save(learner);
        mvc.perform(get("/api/admin/system/users/{id}", learner.getId()).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.premiumActive").value(false));
        mvc.perform(get("/api/admin/system/users").header("Authorization", bearer(adminToken)).param("premium", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/admin/system/premium-plans").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("FREE"));
        mvc.perform(get("/api/admin/system/premium-plans").header("Authorization", bearer(jwt(learner))))
                .andExpect(status().isForbidden());
    }

    @Test
    void premiumPlansCanBeCreatedEditedAndStoppedButNeverTheFreePlan() throws Exception {
        PremiumPlan free = plan("FREE");
        String plans = "/api/admin/system/premium-plans";
        planAction(post(plans), "STUDENT", "Gói sinh viên", "49000", 90, "Mở gói cho sinh viên", 200)
                .andExpect(jsonPath("$.data.active").value(true)).andExpect(jsonPath("$.data.free").value(false));
        Long id = this.plans.findAll().stream().filter(p -> p.getName().equals("STUDENT")).findFirst().orElseThrow().getId();
        // Names are unique in any letter case, FREE included; values are validated.
        planAction(post(plans), "student", null, "1", 30, "Trùng tên gói", 409);
        planAction(post(plans), "free", null, "0", 30, "Trùng tên gói FREE", 409);
        planAction(post(plans), "BAD", null, "-1", 30, "Giá âm không hợp lệ", 400);
        planAction(post(plans), "BAD", null, "1.234", 30, "Ba chữ số thập phân", 400);
        planAction(post(plans), "BAD", null, "1", 0, "Không có số ngày", 400);
        planAction(post(plans), "BAD", null, "1", 30, "x", 400);

        planAction(put(plans + "/{id}", id), "STUDENT_PLUS", "Gói sinh viên", "59000", 90, "Điều chỉnh giá", 200)
                .andExpect(jsonPath("$.data.name").value("STUDENT_PLUS"));
        planAction(put(plans + "/{id}", id), "STUDENT_PLUS", "Gói sinh viên", "59000.00", 90, "Không đổi gì", 400);
        planAction(put(plans + "/{id}", free.getId()), "FREE", null, "0", 30, "Sửa gói mặc định", 409);

        reasonAction(plans + "/" + id + "/deactivate", "Ngừng bán để thử", 200).andExpect(jsonPath("$.data.active").value(false));
        reasonAction(plans + "/" + id + "/deactivate", "Ngừng bán lần hai", 409);
        grant(learner.getId(), id, 30, "Cấp gói đã ngừng bán", 400);
        reasonAction(plans + "/" + id + "/activate", "Mở bán lại gói", 200).andExpect(jsonPath("$.data.active").value(true));
        reasonAction(plans + "/" + free.getId() + "/deactivate", "Ngừng gói mặc định", 409);
        grant(learner.getId(), id, 30, "Cấp gói đang bán", 200);
        mvc.perform(get(plans).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[1].name").value("STUDENT_PLUS"))
                .andExpect(jsonPath("$.data[1].activeSubscribers").value(1))
                .andExpect(jsonPath("$.data[0].free").value(true));
        mvc.perform(post(plans).header("Authorization", bearer(jwt(learner))).contentType(MediaType.APPLICATION_JSON)
                        .content(planJson("HACK", null, "0", 30, "Học viên tự tạo gói")))
                .andExpect(status().isForbidden());

        assertThat(audit.list(null, null, "PREMIUM_PLAN", id.toString(), "SUCCESS", null, null, 0, 20).getContent())
                .extracting(a -> a.action()).containsExactly("PREMIUM_PLAN_ACTIVATE", "PREMIUM_PLAN_DEACTIVATE",
                        "PREMIUM_PLAN_UPDATE", "PREMIUM_PLAN_CREATE");
        assertThat(audit.list(null, null, "PREMIUM_PLAN", null, "REJECTED", null, null, 0, 20).getTotalElements())
                .isEqualTo(5);
        assertThat(this.plans.findById(id).orElseThrow().getPrice()).isEqualByComparingTo("59000");
    }

    @Test
    void paymentsAreSearchableAndFeedTheDashboard() throws Exception {
        PremiumPlan monthly = plan("MONTHLY");
        User buyer = createUser("buyer", "LEARNER");
        Payment paid = payment(buyer, monthly, "ORD-1001", "PAID", LocalDateTime.now().minusDays(2));
        payment(buyer, monthly, "ORD-1002", "PENDING", null);
        payment(learner, monthly, "ORD-1003", "FAILED", null);
        String url = "/api/admin/system/payments";
        mvc.perform(get(url).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3));
        mvc.perform(get(url).header("Authorization", bearer(adminToken)).param("status", "PAID"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].orderCode").value("ORD-1001"))
                .andExpect(jsonPath("$.data.items[0].userEmail").value(buyer.getEmail()));
        mvc.perform(get(url).header("Authorization", bearer(adminToken)).param("q", "BUYER@"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get(url).header("Authorization", bearer(adminToken)).param("q", "ord-1003"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(url).header("Authorization", bearer(adminToken)).param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(url + "/{id}", paid.getId()).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.planName").value("MONTHLY"))
                .andExpect(jsonPath("$.data.durationDays").value(30));
        mvc.perform(get(url + "/999999").header("Authorization", bearer(adminToken))).andExpect(status().isNotFound());

        mvc.perform(get("/api/admin/system/dashboard").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalUsers").value(3))
                .andExpect(jsonPath("$.data.usersByRole.LEARNER").value(2))
                .andExpect(jsonPath("$.data.paymentsByStatus.PAID").value(1))
                .andExpect(jsonPath("$.data.paymentsByStatus.PENDING").value(1))
                .andExpect(jsonPath("$.data.paidLast30Days[0].count").value(1))
                .andExpect(jsonPath("$.data.paidLast30Days[0].amount").value(99000.0))
                .andExpect(jsonPath("$.data.plansOnSale").value(1))
                .andExpect(jsonPath("$.data.premiumActive").value(0));
    }

    @Test
    void couponsAndCreditPackagesCrudAreAuditedAndReferencedRowsCannotBeDeleted() throws Exception {
        String c = "/api/admin/system/coupons";
        String p = "/api/admin/system/credit-packages";
        mvc.perform(post(c).header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"save10\",\"discountType\":\"PERCENT\",\"discountValue\":10,\"appliesTo\":\"ALL\",\"reason\":\"Audit create\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("SAVE10"));
        Coupon coupon = coupons.findByCode("SAVE10").orElseThrow();
        mvc.perform(post(p).header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"CP100\",\"creditAmount\":100,\"price\":10000,\"reason\":\"Audit create\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.creditAmount").value(100));
        CreditPackage pack = packages.findAll().getFirst();
        mvc.perform(put(c + "/{id}", coupon.getId()).header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SAVE10\",\"discountType\":\"FIXED\",\"discountValue\":1000,\"appliesTo\":\"CREDIT\",\"reason\":\"Audit update\"}"))
                .andExpect(status().isOk());
        mvc.perform(put(p + "/{id}", pack.getId()).header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"CP100\",\"creditAmount\":120,\"price\":12000,\"reason\":\"Audit update\"}"))
                .andExpect(status().isOk());
        Payment cpOrder = payments.save(Payment.builder().user(learner).provider("PAYOS").orderCode("CP-USED")
                .status("PAID").productType("CREDIT").creditPackage(pack).creditAmountSnapshot(100)
                .coupon(coupon).couponCodeSnapshot("SAVE10").originalAmount(new BigDecimal("10000"))
                .discountAmount(new BigDecimal("1000")).amountPaid(new BigDecimal("9000"))
                .currency("VND").planNameSnapshot("CP100").paidAt(LocalDateTime.now()).build());
        mvc.perform(get("/api/admin/system/payments").param("productType", "CREDIT")
                .header("Authorization", bearer(adminToken))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].couponCode").value("SAVE10"));
        mvc.perform(get("/api/admin/system/payments").param("productType", "PREMIUM")
                .header("Authorization", bearer(adminToken))).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(delete(c + "/{id}", coupon.getId()).header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Audit delete\"}"))
                .andExpect(status().isConflict());
        mvc.perform(delete(p + "/{id}", pack.getId()).header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Audit delete\"}"))
                .andExpect(status().isConflict());
        assertThat(audit.list(null, "COUPON_DELETE", "COUPON", coupon.getId().toString(), "REJECTED", null, null, 0, 10).getTotalElements()).isEqualTo(1);
        assertThat(audit.list(null, "CREDIT_PACKAGE_DELETE", "CREDIT_PACKAGE", pack.getId().toString(), "REJECTED", null, null, 0, 10).getTotalElements()).isEqualTo(1);
        mvc.perform(get(c).header("Authorization", bearer(jwt(learner)))).andExpect(status().isForbidden());
        payments.delete(cpOrder);
        mvc.perform(delete(c + "/{id}", coupon.getId()).header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Audit delete\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete(p + "/{id}", pack.getId()).header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Audit delete\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void couponMoneyFieldsRequireWholeVndAndRejectsAreAudited() throws Exception {
        String url = "/api/admin/system/coupons";
        for (String fields : new String[]{
                "\"discountType\":\"FIXED\",\"discountValue\":1000.50",
                "\"discountType\":\"PERCENT\",\"discountValue\":10,\"maxDiscount\":1000.50",
                "\"discountType\":\"PERCENT\",\"discountValue\":10,\"minOrderAmount\":1000.50"}) {
            mvc.perform(post(url).header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"WHOLE1\"," + fields + ",\"appliesTo\":\"ALL\",\"reason\":\"Check whole VND\"}"))
                    .andExpect(status().isConflict());
        }
        assertThat(audit.list(null, "COUPON_CREATE", "COUPON", null, "REJECTED", null, null, 0, 10).getTotalElements()).isEqualTo(3);
        mvc.perform(post(url).header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"WHOLE1\",\"discountType\":\"FIXED\",\"discountValue\":1000.00,"
                        + "\"minOrderAmount\":2000.00,\"appliesTo\":\"ALL\",\"reason\":\"Check whole VND\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void manualCreditAdjustmentUsesLedgerAndAuditsInsufficientBalance() throws Exception {
        String url = "/api/admin/system/users/" + learner.getId() + "/credits";
        String longReason = "A".repeat(300);
        mvc.perform(post(url).header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":50,\"reason\":\"" + longReason + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.creditBalance").value(50));
        mvc.perform(post(url).header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":-20,\"reason\":\"Manual deduct\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.creditBalance").value(30));
        mvc.perform(post(url).header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":-40,\"reason\":\"Insufficient balance\"}"))
                .andExpect(status().isConflict());
        assertThat(users.findById(learner.getId()).orElseThrow().getCreditBalance()).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_credit_transaction WHERE user_id = ? AND reason IN ('ADMIN_GRANT','ADMIN_DEDUCT')",
                Long.class, learner.getId())).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT note FROM user_credit_transaction WHERE user_id = ? AND reason IN ('ADMIN_GRANT','ADMIN_DEDUCT')",
                String.class, learner.getId())).containsExactlyInAnyOrder("Quản trị viên điều chỉnh CP", "Quản trị viên điều chỉnh CP");
        assertThat(jdbc.queryForList("SELECT message FROM user_notification WHERE user_id = ? AND type = 'CREDIT'",
                String.class, learner.getId())).containsExactlyInAnyOrder("Quản trị viên điều chỉnh CP", "Quản trị viên điều chỉnh CP");
        assertThat(audit.list(null, "USER_CREDIT_ADJUST", "USER", learner.getId().toString(), "SUCCESS", null, null, 0, 10).getContent())
                .anySatisfy(entry -> assertThat(entry.reason()).isEqualTo(longReason));
        assertThat(audit.list(null, "USER_CREDIT_ADJUST", "USER", learner.getId().toString(), "REJECTED", null, null, 0, 10).getTotalElements()).isEqualTo(1);
        for (String amount : new String[]{"null", "0", "100001", "-100001"}) {
            mvc.perform(post(url).header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":" + amount + ",\"reason\":\"Invalid amount\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(audit.list(null, "USER_CREDIT_ADJUST", "USER", learner.getId().toString(), "REJECTED", null, null, 0, 10).getTotalElements()).isEqualTo(5);
        mvc.perform(post(url).header("Authorization", bearer(jwt(learner))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":10,\"reason\":\"Not allowed\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void masksChangesBeforePersisting() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> audit.record(
                superAdmin.getId(), "TEST", "USER", learner.getId().toString(), "Mask test", "SUCCESS",
                Map.of("nested", Map.of("api_key", "dummy-private-value", "status", "ACTIVE"))));
        assertThat(logs.findAll().getFirst().getChangesJson()).contains("***", "ACTIVE").doesNotContain("dummy-private-value");
    }

    private User createUser(String name, String roleName) {
        Role role = roles.findByName(roleName).orElseGet(() -> roles.save(Role.builder().name(roleName).build()));
        return users.save(User.builder().email(name + "@example.test").role(role).loginType("LOCAL")
                .passwordHash(passwords.encode(PASSWORD)).isVerified(true).build());
    }

    private PremiumPlan plan(String name) {
        return plans.save(PremiumPlan.builder().name(name).price("FREE".equals(name) ? BigDecimal.ZERO : new BigDecimal("9.99"))
                .durationDays("FREE".equals(name) ? 36500 : 30).build());
    }

    private Payment payment(User buyer, PremiumPlan plan, String order, String status, LocalDateTime paidAt) {
        return payments.save(Payment.builder().user(buyer).premiumPlan(plan).provider("PAYOS").orderCode(order)
                .status(status).amountPaid(new BigDecimal("99000")).currency("VND").planNameSnapshot(plan.getName())
                .durationDaysSnapshot(plan.getDurationDays()).paidAt(paidAt).build());
    }

    private static String planJson(String name, String description, String price, int days, String reason) {
        return "{\"name\":\"" + name + "\",\"description\":" + (description == null ? "null" : "\"" + description + "\"")
                + ",\"price\":" + price + ",\"durationDays\":" + days + ",\"reason\":\"" + reason + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions planAction(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String name,
            String description, String price, int days, String reason, int expectedStatus) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8")
                        .content(planJson(name, description, price, days, reason)))
                .andExpect(status().is(expectedStatus));
    }

    private org.springframework.test.web.servlet.ResultActions reasonAction(String url, String reason,
                                                                           int expectedStatus) throws Exception {
        return mvc.perform(post(url).header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8")
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private org.springframework.test.web.servlet.ResultActions roleAction(Long id, String role, String reason,
                                                                         int expectedStatus) throws Exception {
        return mvc.perform(post("/api/admin/system/users/{id}/role", id).header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8")
                        .content("{\"role\":\"" + role + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private org.springframework.test.web.servlet.ResultActions grant(Long id, Long planId, int days, String reason,
                                                                    int expectedStatus) throws Exception {
        return mvc.perform(post("/api/admin/system/users/{id}/premium-grants", id).header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8")
                        .content("{\"planId\":" + planId + ",\"durationDays\":" + days + ",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private LoginResponse login(User user) {
        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword(PASSWORD);
        return auth.login(request, null);
    }

    private String jwt(User user) {
        return JwtUtil.generateToken(user.getEmail(), authProperties.jwtSecret(), authProperties.accessTokenTtl());
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private void action(Long id, String operation, String reason, int expectedStatus) throws Exception {
        mvc.perform(post("/api/admin/system/users/{id}/" + operation, id).header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8")
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expectedStatus));
    }
}
