package com.jlas.learning;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningGrammarPracticeProgress;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningGrammarPracticeProgressRepository;
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

/** [JLAS-W0193] Task 6 — proves the new {@code learning_grammar_practice_progress} table and a
 * {@code GRAMMAR_MICRO_CHECK} quiz session are correctly owner/type-scoped and isolated from Vocabulary/
 * Kanji, the same way {@link InteractiveKanjiPersistenceTest} proved it for Kanji in Task 5. */
@DataJpaTest(properties = {"spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:v2-grammar-persistence;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = InteractiveGrammarPersistenceTest.Cfg.class)
class InteractiveGrammarPersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired UserLearningPathItemRepository items;
    @Autowired LearningActivityMemberRepository members;
    @Autowired LearningGrammarPracticeProgressRepository progress;
    @Autowired LearningQuizSessionRepository quizSessions;

    @Test void grammarMembershipDoesNotLeakIntoVocabularyActivityInSamePath() {
        User owner = persistUser("v2-grammar-owner@jlas.test");
        UserLearningPath path = UserLearningPath.builder().user(owner).title("V2").status("ACTIVE").version(2).build();
        em.persist(path);
        UserLearningPathItem vocabActivity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.STUDYING).build();
        em.persist(vocabActivity);
        UserLearningPathItem grammarActivity = UserLearningPathItem.builder().learningPath(path).orderIndex(2)
                .activityType(LearningActivityType.GRAMMAR).activityState(LearningActivityState.STUDYING).build();
        em.persist(grammarActivity);
        em.persist(LearningActivityMember.builder().activity(vocabActivity)
                .memberType(LearningMemberType.VOCABULARY_ENTRY).canonicalId(42L).orderIndex(1)
                .sourceRevision("rev-1").build());
        em.persist(LearningActivityMember.builder().activity(grammarActivity)
                .memberType(LearningMemberType.GRAMMAR_POINT).canonicalId(501L).orderIndex(1)
                .sourceRevision("rev-1").build());
        em.flush(); em.clear();

        var vocabMembers = members.findByActivityIdOrderByOrderIndex(vocabActivity.getId());
        var grammarMembers = members.findByActivityIdOrderByOrderIndex(grammarActivity.getId());
        assertEquals(1, vocabMembers.size());
        assertEquals(LearningMemberType.VOCABULARY_ENTRY, vocabMembers.get(0).getMemberType());
        assertEquals(1, grammarMembers.size());
        assertEquals(LearningMemberType.GRAMMAR_POINT, grammarMembers.get(0).getMemberType());
        assertEquals(501L, grammarMembers.get(0).getCanonicalId());
        assertTrue(items.findOwnedByIdForUpdate(grammarActivity.getId(), owner.getId()).isPresent());
    }

    @Test void practiceProgressIsUniquePerActivityAndGrammarPointAndPersistsBothFlags() {
        User owner = persistUser("v2-grammar-owner2@jlas.test");
        UserLearningPath path = UserLearningPath.builder().user(owner).title("V2").status("ACTIVE").version(2).build();
        em.persist(path);
        UserLearningPathItem activity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                .activityType(LearningActivityType.GRAMMAR).activityState(LearningActivityState.STUDYING).build();
        em.persist(activity);
        em.persist(LearningGrammarPracticeProgress.builder().activity(activity).grammarPointId(501L)
                .sourceRevision("rev-1").orderingDone(true).fillDone(false).build());
        em.flush(); em.clear();

        var stored = progress.findByActivityIdAndGrammarPointId(activity.getId(), 501L).orElseThrow();
        assertTrue(stored.isOrderingDone());
        assertFalse(stored.isFillDone());
        assertFalse(stored.isGateSatisfied());
    }

    @Test void grammarQuizSessionIsOwnerAndQuizTypeScopedSeparatelyFromKanji() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User owner = persistUser(role, "v2-grammar-owner3@jlas.test");
        User stranger = persistUser(role, "v2-grammar-other@jlas.test");
        UserLearningPath path = UserLearningPath.builder().user(owner).title("V2").status("ACTIVE").version(2).build();
        em.persist(path);
        UserLearningPathItem activity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                .activityType(LearningActivityType.GRAMMAR).activityState(LearningActivityState.STUDYING).build();
        em.persist(activity);
        LearningQuizSession snapshot = LearningQuizSession.builder().user(owner)
                .quizType("GRAMMAR_MICRO_CHECK").activityId(activity.getId()).batchIndex(0)
                .questionCount(4).questionsJson("[]").answerKeyJson("{}").build();
        em.persist(snapshot);
        em.flush(); em.clear();

        assertTrue(quizSessions.findOwnedByActivity(
                snapshot.getId(), owner.getId(), "GRAMMAR_MICRO_CHECK", activity.getId()).isPresent());
        assertTrue(quizSessions.findOwnedByActivity(
                snapshot.getId(), stranger.getId(), "GRAMMAR_MICRO_CHECK", activity.getId()).isEmpty());
        assertTrue(quizSessions.findOwnedByActivity(
                snapshot.getId(), owner.getId(), "KANJI_MICRO_CHECK", activity.getId()).isEmpty());
    }

    private User persistUser(String email) {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        return persistUser(role, email);
    }

    private User persistUser(Role role, String email) {
        User user = User.builder().role(role).email(email).loginType("LOCAL").build();
        em.persist(user);
        return user;
    }
}
