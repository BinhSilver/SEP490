package com.jlas.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.mission.service.CreditLedgerService;
import com.jlas.mission.service.impl.CreditLedgerServiceImpl;
import com.jlas.moderation.entity.UserCreditTransaction;
import com.jlas.moderation.repository.UserCreditTransactionRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:credit-ledger-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = CreditLedgerServiceTest.CreditTestConfiguration.class)
class CreditLedgerServiceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.auth.repository", "com.jlas.moderation.repository"})
    @Import(CreditLedgerServiceImpl.class)
    static class CreditTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired CreditLedgerService service;
    @Autowired UserCreditTransactionRepository transactionRepository;

    private int userSeq = 0;

    @Test
    void awardAddsCreditsAndLedgerEntry() {
        var user = seedUser();
        em.flush();

        int balance = service.award(user.getId(), 50, "LESSON_COMPLETED", "LESSON", 10L);

        assertEquals(50, balance);
        assertEquals(50, service.balance(user.getId()));
        List<UserCreditTransaction> entries = transactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertEquals(1, entries.size());
        assertEquals(50, entries.get(0).getAmount());
    }

    @Test
    void replayedAwardIsIdempotent() {
        var user = seedUser();
        em.flush();

        service.award(user.getId(), 50, "LESSON_COMPLETED", "LESSON", 10L);
        int again = service.award(user.getId(), 50, "LESSON_COMPLETED", "LESSON", 10L);

        assertEquals(50, again);
        assertEquals(50, service.balance(user.getId()));
        assertEquals(1, transactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).size());
    }

    @Test
    void differentReferenceAwardsAgain() {
        var user = seedUser();
        em.flush();

        service.award(user.getId(), 50, "LESSON_COMPLETED", "LESSON", 10L);
        int balance = service.award(user.getId(), 50, "LESSON_COMPLETED", "LESSON", 11L);

        assertEquals(100, balance);
        assertEquals(2, transactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).size());
    }

    @Test
    void unreferencedAwardAlwaysApplies() {
        var user = seedUser();
        em.flush();

        service.award(user.getId(), 30, "BONUS", null, null);
        int balance = service.award(user.getId(), 30, "BONUS", null, null);

        assertEquals(60, balance);
        assertEquals(2, transactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).size());
    }

    @Test
    void nonPositiveAmountIsRejected() {
        var user = seedUser();
        em.flush();

        assertApiError(ErrorCode.BAD_REQUEST, () -> service.award(user.getId(), 0, "BONUS", null, null));
        assertApiError(ErrorCode.BAD_REQUEST, () -> service.award(user.getId(), -5, "BONUS", null, null));
    }

    @Test
    void balanceThrowsNotFoundForUnknownUser() {
        assertApiError(ErrorCode.NOT_FOUND, () -> service.balance(999999L));
    }

    private void assertApiError(ErrorCode code, Executable action) {
        ApiException ex = assertThrows(ApiException.class, action);
        assertEquals(code, ex.getErrorCode());
    }

    private User seedUser() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User user = User.builder().role(role)
                .email("learner" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        return user;
    }
}
