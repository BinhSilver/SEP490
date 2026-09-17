package com.jlas.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.mission.service.CreditLedgerService;
import com.jlas.mission.service.impl.CreditLedgerServiceImpl;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two concurrent awards of the same reference must serialize on the user-row lock, so the
 * credit is applied exactly once and a single ledger entry is persisted.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:credit-concurrency-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = CreditLedgerConcurrencyTest.ConcurrencyConfiguration.class)
class CreditLedgerConcurrencyTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.auth.repository", "com.jlas.moderation.repository"})
    @Import(CreditLedgerServiceImpl.class)
    static class ConcurrencyConfiguration { }

    @Autowired EntityManager em;
    @Autowired CreditLedgerService service;
    @Autowired PlatformTransactionManager txManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAwardsOfSameReferenceCreditOnce() throws Exception {
        Long userId = seedCommittedUser();

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        Runnable task = () -> {
            try {
                ready.countDown();
                go.await();
                results.add(service.award(userId, 50, "LESSON_COMPLETED", "LESSON", 10L));
            } catch (Throwable throwable) {
                errors.add(throwable);
            }
        };
        Thread first = new Thread(task);
        Thread second = new Thread(task);
        first.start();
        second.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS), "threads should be ready");
        go.countDown();
        first.join(30_000);
        second.join(30_000);

        assertEquals(2, results.size(), "both awards complete without error");
        assertTrue(errors.isEmpty(), "no award should be rejected: " + errors);
        assertEquals(List.of(50, 50), results, "both awards report the same balance");

        Long entryCount = new TransactionTemplate(txManager).execute(status -> em.createQuery(
                        "select count(t) from UserCreditTransaction t where t.user.id = :userId", Long.class)
                .setParameter("userId", userId)
                .getSingleResult());
        assertEquals(1L, entryCount, "exactly one ledger entry is persisted");
        assertEquals(50, service.balance(userId), "balance is credited exactly once");
    }

    private Long seedCommittedUser() {
        return new TransactionTemplate(txManager).execute(status -> {
            Role role = Role.builder().name("LEARNER").build();
            em.persist(role);
            User user = User.builder().role(role).email("concurrent-credit@test.local").loginType("LOCAL").build();
            em.persist(user);
            return user.getId();
        });
    }
}
