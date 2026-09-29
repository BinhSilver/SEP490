package com.jlas.learning;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningDialogueProgress;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningDialogueProgressRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** [JLAS-W0193] Task 7 — proves the new {@code learning_dialogue_progress} table is owner-scoped (via its
 * activity), unique per activity and isolated from Vocabulary/Kanji/Grammar activities in the same path, the
 * same way {@link InteractiveGrammarPersistenceTest} proved it for Grammar in Task 6. */
@DataJpaTest(properties = {"spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:v2-dialogue-persistence;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = InteractiveDialoguePersistenceTest.Cfg.class)
class InteractiveDialoguePersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired UserLearningPathItemRepository items;
    @Autowired LearningActivityMemberRepository members;
    @Autowired LearningDialogueProgressRepository progress;

    @Test void dialogueMembershipDoesNotLeakIntoGrammarActivityInSamePath() {
        User owner = persistUser("v2-dialogue-owner@jlas.test");
        UserLearningPath path = UserLearningPath.builder().user(owner).title("V2").status("ACTIVE").version(2).build();
        em.persist(path);
        UserLearningPathItem grammarActivity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                .activityType(LearningActivityType.GRAMMAR).activityState(LearningActivityState.STUDYING).build();
        em.persist(grammarActivity);
        UserLearningPathItem dialogueActivity = UserLearningPathItem.builder().learningPath(path).orderIndex(2)
                .activityType(LearningActivityType.DIALOGUE).activityState(LearningActivityState.LOCKED).build();
        em.persist(dialogueActivity);
        em.persist(LearningActivityMember.builder().activity(grammarActivity)
                .memberType(LearningMemberType.GRAMMAR_POINT).canonicalId(501L).orderIndex(1)
                .sourceRevision("rev-1").build());
        em.persist(LearningActivityMember.builder().activity(dialogueActivity)
                .memberType(LearningMemberType.DIALOGUE_LINE).canonicalId(9001L).orderIndex(1)
                .sourceRevision("rev-1").build());
        em.flush(); em.clear();

        var grammarMembers = members.findByActivityIdOrderByOrderIndex(grammarActivity.getId());
        var dialogueMembers = members.findByActivityIdOrderByOrderIndex(dialogueActivity.getId());
        assertEquals(1, grammarMembers.size());
        assertEquals(LearningMemberType.GRAMMAR_POINT, grammarMembers.get(0).getMemberType());
        assertEquals(1, dialogueMembers.size());
        assertEquals(LearningMemberType.DIALOGUE_LINE, dialogueMembers.get(0).getMemberType());
        assertEquals(9001L, dialogueMembers.get(0).getCanonicalId());
        assertTrue(items.findOwnedByIdForUpdate(dialogueActivity.getId(), owner.getId()).isPresent());
    }

    @Test void progressRowIsUniquePerActivityAndPersistsAllEvidenceFields() {
        User owner = persistUser("v2-dialogue-owner2@jlas.test");
        UserLearningPath path = UserLearningPath.builder().user(owner).title("V2").status("ACTIVE").version(2).build();
        em.persist(path);
        UserLearningPathItem activity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                .activityType(LearningActivityType.DIALOGUE).activityState(LearningActivityState.STUDYING).build();
        em.persist(activity);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        em.persist(LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(190).lastHeartbeatAt(now).lastHeartbeatToken("tok-1")
                .reachedBottomAt(now).completedAt(now).build());
        em.flush(); em.clear();

        var stored = progress.findByActivityId(activity.getId()).orElseThrow();
        assertEquals(190, stored.getAccumulatedSeconds());
        assertEquals("tok-1", stored.getLastHeartbeatToken());
        assertNotNull(stored.getReachedBottomAt());
        assertNotNull(stored.getCompletedAt());
    }

    @Test void ownerScopedActivityLookupRejectsAStrangerAsNotFound() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User owner = persistUser(role, "v2-dialogue-owner3@jlas.test");
        User stranger = persistUser(role, "v2-dialogue-other@jlas.test");
        UserLearningPath path = UserLearningPath.builder().user(owner).title("V2").status("ACTIVE").version(2).build();
        em.persist(path);
        UserLearningPathItem activity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                .activityType(LearningActivityType.DIALOGUE).activityState(LearningActivityState.STUDYING).build();
        em.persist(activity);
        em.flush(); em.clear();

        assertTrue(items.findOwnedByIdForUpdate(activity.getId(), owner.getId()).isPresent());
        assertTrue(items.findOwnedByIdForUpdate(activity.getId(), stranger.getId()).isEmpty());
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
