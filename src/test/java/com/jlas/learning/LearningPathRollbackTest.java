package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.LearningResourceCandidate;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.service.LearningCatalogSource;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.LearningService;
import com.jlas.learning.service.impl.LearningResourceSelectionServiceImpl;
import com.jlas.learning.service.impl.LearningScheduleServiceImpl;
import com.jlas.learning.service.impl.LearningServiceImpl;
import com.jlas.learning.service.impl.LearningClock;
import com.jlas.learning.service.impl.WeakTagPolicy;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves a failure mid-persist rolls back the whole path: no half-written path/items and the
 * previous ACTIVE path is left intact. Runs outside the test transaction so committed state is
 * observable, which the default rollback of {@code @DataJpaTest} cannot show.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:learning-path-rollback-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "jlas.learning.selection.minimum-assessed-questions=3",
        "jlas.learning.selection.wrong-ratio-threshold=0.5"
})
@ContextConfiguration(classes = LearningPathRollbackTest.RollbackConfiguration.class)
class LearningPathRollbackTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.dictionary.repository", "com.jlas.assessment.repository"})
    @Import({WeakTagPolicy.class, LearningResourceSelectionServiceImpl.class,
            LearningScheduleServiceImpl.class, LearningMapper.class, LearningServiceImpl.class, LearningClock.class})
    static class RollbackConfiguration {
        @Bean FakeProfileSource profileSource() {
            return new FakeProfileSource();
        }

        @Bean FakeScopeSource scopeSource() {
            return new FakeScopeSource();
        }

        /** Returns candidates whose lesson ids never exist in the DB, forcing an FK failure on flush. */
        @Bean LearningCatalogSource catalogSource() {
            return new GhostCatalogSource();
        }
    }

    static class FakeProfileSource implements LearningProfileSource {
        LearningProfileSnapshot snapshot;

        @Override
        public LearningProfileSnapshot loadForUser(Long userId, Long assessmentAttemptId) {
            return snapshot;
        }
    }

    static class FakeScopeSource implements LearningScopeSource {
        LearningCatalogScope scope;

        @Override
        public LearningCatalogScope resolve(Long userId, LearningProfileSnapshot profile) {
            return scope;
        }
    }

    static class GhostCatalogSource implements LearningCatalogSource {
        @Override
        public List<LearningResourceCandidate> findPublishedLessons(List<Long> textbookIds) {
            if (textbookIds.isEmpty()) return List.of();
            long bookId = textbookIds.get(0);
            return List.of(
                    new LearningResourceCandidate(999_999L, "Ghost warm-up", bookId, 1L,
                            Set.of("PARTICLE"), "N4", 15, 1, 1, null, null),
                    new LearningResourceCandidate(999_998L, "Ghost goal", bookId, 1L,
                            Set.of("TARGET"), "N4", 20, 1, 2, null, null));
        }
    }

    @Autowired EntityManager em;
    @Autowired LearningService service;
    @Autowired PlatformTransactionManager txManager;
    @Autowired FakeProfileSource profileSource;
    @Autowired FakeScopeSource scopeSource;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void itemFailureRollsBackWholePathAndKeepsOldActivePath() {
        Long userId = seedUserWithOldActivePath();

        profileSource.snapshot = profile(userId, 92L, "rev-2");
        scopeSource.scope = new LearningCatalogScope(List.of(1L), List.of(2L));

        assertThrows(RuntimeException.class, () -> service.create(userId, 92L));

        long[] counts = new TransactionTemplate(txManager).execute(status -> {
            long newPaths = em.createQuery(
                            "select count(p) from UserLearningPath p where p.assessmentAttemptId = 92", Long.class)
                    .getSingleResult();
            long activePaths = em.createQuery(
                            "select count(p) from UserLearningPath p where p.user.id = :uid and p.status = 'ACTIVE'",
                            Long.class)
                    .setParameter("uid", userId).getSingleResult();
            long items = em.createQuery("select count(i) from UserLearningPathItem i", Long.class)
                    .getSingleResult();
            return new long[] { newPaths, activePaths, items };
        });
        assertEquals(0L, counts[0], "no partial path for the failed attempt");
        assertEquals(1L, counts[1], "the previous ACTIVE path survives");
        assertEquals(0L, counts[2], "no partial items survive the rollback");
    }

    private Long seedUserWithOldActivePath() {
        return new TransactionTemplate(txManager).execute(status -> {
            Role role = Role.builder().name("LEARNER").build();
            em.persist(role);
            User user = User.builder().role(role).email("rollback@test.local").loginType("LOCAL").build();
            em.persist(user);
            em.flush();
            UserLearningPath old = UserLearningPath.builder()
                    .user(user).title("Old path").status("ACTIVE").version(1).build();
            em.persist(old);
            return user.getId();
        });
    }

    private LearningProfileSnapshot profile(Long userId, Long attemptId, String revision) {
        return new LearningProfileSnapshot(userId, attemptId, 10L, revision, true, true,
                "N4", "N4", "JLPT", 30, List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 5, 4)));
    }
}
