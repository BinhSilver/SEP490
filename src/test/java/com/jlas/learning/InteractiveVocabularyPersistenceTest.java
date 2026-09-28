package com.jlas.learning;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningMicroCheckState;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.LearningStudyCard;
import com.jlas.learning.entity.LearningStudySession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:v2-vocab-persistence;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = InteractiveVocabularyPersistenceTest.Cfg.class)
class InteractiveVocabularyPersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired UserLearningPathItemRepository items;
    @Autowired LearningActivityMemberRepository members;
    @Autowired LearningStudySessionRepository studySessions;
    @Autowired LearningStudyCardRepository studyCards;
    @Autowired LearningMicroCheckStateRepository checkStates;
    @Autowired LearningQuizSessionRepository quizSessions;

    @Test void studySessionPersistsRevisionAndScopesReadsToOwner() {
        Fixture fx = seed();
        LearningStudySession session = LearningStudySession.builder()
                .activity(fx.activity()).sourceRevision("rev-1").build();
        em.persist(session);
        em.flush(); em.clear();

        assertTrue(studySessions.findByActivityId(fx.activityId()).isPresent());
        assertEquals("rev-1", studySessions.findByActivityId(fx.activityId()).get().getSourceRevision());
        assertTrue(studySessions.findOwnedByActivity(fx.activityId(), fx.ownerId()).isPresent());
        assertTrue(studySessions.findOwnedByActivity(fx.activityId(), fx.strangerId()).isEmpty());
    }

    @Test void studyCardPersistsWithUniqueSessionAndCanonicalId() {
        Fixture fx = seed();
        LearningStudySession session = LearningStudySession.builder()
                .activity(fx.activity()).sourceRevision("rev-1").build();
        em.persist(session);
        em.persist(LearningStudyCard.builder().session(session).canonicalId(42L).orderIndex(1).build());
        em.flush(); em.clear();

        assertEquals(1, studyCards.findBySessionIdOrderByOrderIndex(session.getId()).size());
        assertTrue(studyCards.findBySessionIdAndCanonicalId(session.getId(), 42L).isPresent());
        assertFalse(studyCards.findBySessionIdAndCanonicalId(session.getId(), 999L).isPresent());
    }

    @Test void microCheckStatePersistsPerBatch() {
        Fixture fx = seed();
        em.persist(LearningMicroCheckState.builder().activity(fx.activity()).batchIndex(0).build());
        em.persist(LearningMicroCheckState.builder().activity(fx.activity()).batchIndex(1).build());
        em.flush(); em.clear();

        assertEquals(2, checkStates.findByActivityIdOrderByBatchIndex(fx.activityId()).size());
        assertTrue(checkStates.findByActivityIdAndBatchIndex(fx.activityId(), 0).isPresent());
        assertTrue(checkStates.findByActivityIdAndBatchIndex(fx.activityId(), 1).isPresent());
    }

    @Test void quizSessionBatchIndexRoundTripsAndIsOwnerScoped() {
        Fixture fx = seed();
        LearningQuizSession snapshot = LearningQuizSession.builder().user(fx.owner())
                .quizType("VOCAB_MICRO_CHECK").activityId(fx.activityId()).batchIndex(0)
                .questionCount(10).questionsJson("[]").answerKeyJson("{}").build();
        em.persist(snapshot);
        em.flush(); em.clear();

        LearningQuizSession read = quizSessions.findOwnedByActivity(
                snapshot.getId(), fx.ownerId(), "VOCAB_MICRO_CHECK", fx.activityId()).orElseThrow();
        assertEquals(0, read.getBatchIndex());
        assertEquals(10, read.getQuestionCount());
        assertTrue(quizSessions.findOwnedByActivity(
                snapshot.getId(), fx.strangerId(), "VOCAB_MICRO_CHECK", fx.activityId()).isEmpty());
    }

    private Fixture seed() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User owner = User.builder().role(role).email("v2-vocab-owner@jlas.test").loginType("LOCAL").build();
        em.persist(owner);
        User stranger = User.builder().role(role).email("v2-vocab-other@jlas.test").loginType("LOCAL").build();
        em.persist(stranger);
        UserLearningPath path = UserLearningPath.builder().user(owner).title("V2").status("ACTIVE")
                .version(2).build();
        em.persist(path);
        UserLearningPathItem activity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.STUDYING).build();
        em.persist(activity);
        em.persist(LearningActivityMember.builder().activity(activity)
                .memberType(LearningMemberType.VOCABULARY_ENTRY).canonicalId(42L).orderIndex(1)
                .sourceRevision("rev-1").build());
        em.flush();
        return new Fixture(owner, stranger, activity);
    }

    private record Fixture(User owner, User stranger, UserLearningPathItem activity) {
        Long ownerId() {
            return owner.getId();
        }

        Long strangerId() {
            return stranger.getId();
        }

        Long activityId() {
            return activity.getId();
        }
    }
}
