package com.jlas.moderation.credit;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.RoleRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.moderation.entity.ModerationAuditLog;
import com.jlas.moderation.repository.ModerationAuditLogRepository;
import com.jlas.moderation.repository.UserCreditTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises W0250 revisions against the JLAS2-32 unique ledger constraint. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:credit_merge_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class UserCreditMergeIntegrationTest {
    @Autowired UserCreditService credits;
    @Autowired UserCreditTransactionRepository ledger;
    @Autowired ModerationAuditLogRepository audits;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;

    @Test
    void repeatedDictionaryRevisionsPreserveNetRewardAndUniqueLedger() {
        User user = user();
        credits.earn(user.getId(), 20, WORD_CONTRIBUTION_REWARD, "WORD_CONTRIBUTION", 101L, "Approval");
        for (int cycle = 0; cycle < 3; cycle++) {
            assertThat(settle(user, 0, revision(101L), 101L)).isEqualTo(-20);
            Long approval = revision(101L);
            assertThat(settle(user, 20, approval, 101L)).isEqualTo(20);
            assertThat(settle(user, 20, approval, 101L)).isZero();
            ledger.flush();
            assertThat(ledger.netAmount(user.getId(), "WORD_CONTRIBUTION", 101L)).isEqualTo(20);
        }
        assertThat(credits.balance(user.getId())).isEqualTo(20);
        assertThat(ledger.findByUserIdOrderByCreatedAtDesc(user.getId())).hasSize(7);
    }

    @Test
    void missionAwardsAndSpendsRemainIdempotent() {
        User user = user();
        assertThat(credits.earn(user.getId(), 50, DAILY_GOAL_REWARD, "DAILY_GOAL", 1L, "Goal").applied()).isTrue();
        assertThat(credits.earn(user.getId(), 50, DAILY_GOAL_REWARD, "DAILY_GOAL", 1L, "Retry").applied()).isFalse();
        assertThat(credits.spend(user.getId(), 10, STREAK_FREEZE, "STREAK_FREEZE", 2L, "Freeze").applied()).isTrue();
        assertThat(credits.spend(user.getId(), 10, STREAK_FREEZE, "STREAK_FREEZE", 2L, "Retry").applied()).isFalse();
        ledger.flush();
        assertThat(credits.balance(user.getId())).isEqualTo(40);
        assertThat(ledger.findByUserIdOrderByCreatedAtDesc(user.getId())).hasSize(2);
    }

    @Test
    void takeBackCapsAtAvailableBalanceAndSeparatesContributions() {
        User user = user();
        credits.earn(user.getId(), 20, WORD_CONTRIBUTION_REWARD, "WORD_CONTRIBUTION", 101L, "Approval");
        credits.spend(user.getId(), 15, VIDEO_UNLOCK, "VIDEO", 1L, "Unlock");
        assertThat(settle(user, 0, revision(101L), 101L)).isEqualTo(-5);
        assertThat(credits.balance(user.getId())).isZero();
        assertThat(settle(user, 20, revision(101L), 101L)).isEqualTo(5);
        credits.earn(user.getId(), 20, WORD_CONTRIBUTION_REWARD, "WORD_CONTRIBUTION", 102L, "Other approval");
        assertThat(settle(user, 0, revision(102L), 102L)).isEqualTo(-20);
        ledger.flush();
        assertThat(ledger.netAmount(user.getId(), "WORD_CONTRIBUTION", 101L)).isEqualTo(20);
        assertThat(ledger.netAmount(user.getId(), "WORD_CONTRIBUTION", 102L)).isZero();
        assertThat(credits.balance(user.getId())).isEqualTo(5);
    }

    private int settle(User user, int target, Long revision, Long contribution) {
        return credits.settle(user.getId(), target, WORD_CONTRIBUTION_REWARD,
                WORD_CONTRIBUTION_REVOKED, "WORD_CONTRIBUTION", contribution, revision, "Revision");
    }

    @Test
    void gameItemPurchaseChargesOnlyOnce() {
        User user = user();
        credits.earn(user.getId(), 100, ADMIN_GRANT, "TEST_GRANT", 1L, "Test balance");
        assertThat(credits.spend(user.getId(), 30, GAME_ITEM_PURCHASE, "GAME_ITEM", 12L, "Purchase").applied()).isTrue();
        assertThat(credits.spend(user.getId(), 30, GAME_ITEM_PURCHASE, "GAME_ITEM", 12L, "Retry").applied()).isFalse();
        ledger.flush();
        assertThat(credits.balance(user.getId())).isEqualTo(70);
    }

    private Long revision(Long contribution) {
        return audits.saveAndFlush(ModerationAuditLog.builder().actorRole("ADMIN")
                .action("DICTIONARY_REVISE").targetType("WORD_CONTRIBUTION").targetId(contribution)
                .reason("Integration test revision").build()).getId();
    }

    private User user() {
        Role role = roles.findByName("LEARNER").orElseGet(() -> roles.save(Role.builder().name("LEARNER").build()));
        return users.saveAndFlush(User.builder().email("credit-merge@example.com").username("credit-merge")
                .passwordHash("not-a-login-hash").role(role).loginType("LOCAL").creditBalance(0).isVerified(true).build());
    }
}
