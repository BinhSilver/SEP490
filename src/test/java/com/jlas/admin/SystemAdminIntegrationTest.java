package com.jlas.admin;

import com.jlas.admin.repository.AdminAuditLogRepository;
import com.jlas.admin.service.AdminAuditService;
import com.jlas.admin.service.AdminUserService;
import com.jlas.auth.dto.LoginRequest;
import com.jlas.auth.dto.LoginResponse;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.AuthTokenRepository;
import com.jlas.auth.repository.RoleRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.service.AuthService;
import com.jlas.auth.service.EmailService;
import com.jlas.auth.service.GoogleTokenService;
import com.jlas.auth.util.JwtUtil;
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
        users.deleteAll();
        superAdmin = createUser("super", "SUPER_ADMIN");
        learner = createUser("learner", "LEARNER");
        adminToken = jwt(superAdmin);
    }

    @Test
    void adminAndModerationPermissionsAreSeparate() throws Exception {
        User ordinaryAdmin = createUser("admin", "ADMIN");
        User moderator = createUser("moderator", "MODERATOR");
        for (User denied : new User[]{learner, ordinaryAdmin, moderator}) {
            mvc.perform(get("/api/admin/system/users").header("Authorization", bearer(jwt(denied))))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/system/audit-logs").header("Authorization", bearer(jwt(denied))))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/admin/system/users").header("Authorization", bearer(jwt(denied))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/system/users").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        for (String moderationOnly : new String[]{"/api/admin/moderation/queue", "/api/admin/moderation/appeals",
                "/api/admin/moderation/denylist", "/api/admin/moderation/sanctions/active", "/admin/moderation/queue"}) {
            mvc.perform(get(moderationOnly).header("Authorization", bearer(adminToken)))
                    .andExpect(status().isForbidden());
        }
        for (User allowed : new User[]{moderator, ordinaryAdmin}) {
            mvc.perform(get("/api/admin/moderation/queue").header("Authorization", bearer(jwt(allowed))))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/admin/system/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/system/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin%2Fsystem%2Fusers"));
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
