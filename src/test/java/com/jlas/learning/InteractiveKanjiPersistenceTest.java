package com.jlas.learning;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
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

/** [JLAS-W0193] Task 5 — proves the shared content-agnostic tables (already exercised by
 * {@link InteractiveVocabularyPersistenceTest} for Task 4) stay correctly isolated when a path has both a
 * VOCABULARY and a KANJI activity: membership never leaks across activity/member type, and a Kanji quiz session
 * is owner- and quiz-type-scoped exactly like Vocabulary's. */
@DataJpaTest(properties = {"spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:v2-kanji-persistence;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = InteractiveKanjiPersistenceTest.Cfg.class)
class InteractiveKanjiPersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired UserLearningPathItemRepository items;
    @Autowired LearningActivityMemberRepository members;
    @Autowired LearningQuizSessionRepository quizSessions;

    @Test void kanjiMembershipDoesNotLeakIntoVocabularyActivityInSamePath() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User owner = User.builder().role(role).email("v2-kanji-owner@jlas.test").loginType("LOCAL").build();
        em.persist(owner);
        UserLearningPath path = UserLearningPath.builder().user(owner).title("V2").status("ACTIVE").version(2).build();
        em.persist(path);
        UserLearningPathItem vocabActivity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.STUDYING).build();
        em.persist(vocabActivity);
        UserLearningPathItem kanjiActivity = UserLearningPathItem.builder().learningPath(path).orderIndex(2)
                .activityType(LearningActivityType.KANJI).activityState(LearningActivityState.STUDYING).build();
        em.persist(kanjiActivity);
        em.persist(LearningActivityMember.builder().activity(vocabActivity)
                .memberType(LearningMemberType.VOCABULARY_ENTRY).canonicalId(42L).orderIndex(1)
                .sourceRevision("rev-1").build());
        em.persist(LearningActivityMember.builder().activity(kanjiActivity)
                .memberType(LearningMemberType.KANJI).canonicalId(200L).orderIndex(1)
                .sourceRevision("rev-1").build());
        em.flush(); em.clear();

        var vocabMembers = members.findByActivityIdOrderByOrderIndex(vocabActivity.getId());
        var kanjiMembers = members.findByActivityIdOrderByOrderIndex(kanjiActivity.getId());
        assertEquals(1, vocabMembers.size());
        assertEquals(LearningMemberType.VOCABULARY_ENTRY, vocabMembers.get(0).getMemberType());
        assertEquals(1, kanjiMembers.size());
        assertEquals(LearningMemberType.KANJI, kanjiMembers.get(0).getMemberType());
        assertEquals(200L, kanjiMembers.get(0).getCanonicalId());
        assertTrue(items.findOwnedByIdForUpdate(kanjiActivity.getId(), owner.getId()).isPresent());
    }

    @Test void kanjiQuizSessionIsOwnerAndQuizTypeScopedSeparatelyFromVocabulary() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User owner = User.builder().role(role).email("v2-kanji-owner2@jlas.test").loginType("LOCAL").build();
        em.persist(owner);
        User stranger = User.builder().role(role).email("v2-kanji-other@jlas.test").loginType("LOCAL").build();
        em.persist(stranger);
        UserLearningPath path = UserLearningPath.builder().user(owner).title("V2").status("ACTIVE").version(2).build();
        em.persist(path);
        UserLearningPathItem activity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                .activityType(LearningActivityType.KANJI).activityState(LearningActivityState.STUDYING).build();
        em.persist(activity);
        LearningQuizSession snapshot = LearningQuizSession.builder().user(owner)
                .quizType("KANJI_MICRO_CHECK").activityId(activity.getId()).batchIndex(0)
                .questionCount(10).questionsJson("[]").answerKeyJson("{}").build();
        em.persist(snapshot);
        em.flush(); em.clear();

        assertTrue(quizSessions.findOwnedByActivity(
                snapshot.getId(), owner.getId(), "KANJI_MICRO_CHECK", activity.getId()).isPresent());
        assertTrue(quizSessions.findOwnedByActivity(
                snapshot.getId(), stranger.getId(), "KANJI_MICRO_CHECK", activity.getId()).isEmpty());
        // A Vocabulary lookup against the same activity id/user must not see the Kanji session.
        assertTrue(quizSessions.findOwnedByActivity(
                snapshot.getId(), owner.getId(), "VOCAB_MICRO_CHECK", activity.getId()).isEmpty());
    }
}
