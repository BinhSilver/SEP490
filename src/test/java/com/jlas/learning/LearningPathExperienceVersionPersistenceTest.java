package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.repository.UserLearningPathRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/** Regression: an ACTIVE legacy path must not block creation of the V2 generation. */
@DataJpaTest(properties = {"spring.profiles.include=", "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = LearningPathExperienceVersionPersistenceTest.Config.class)
class LearningPathExperienceVersionPersistenceTest {

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Config { }

    @Autowired EntityManager em;
    @Autowired UserLearningPathRepository paths;

    @Test
    void legacyPathDoesNotSatisfyInteractiveCreationIdentity() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User user = User.builder().role(role).email("cutover@test.local").loginType("LOCAL").build();
        em.persist(user);
        em.persist(UserLearningPath.builder().user(user).title("Legacy").goal("JLPT")
                .assessmentAttemptId(91L).assessmentRevision("rev-1").policyVersion("policy-1")
                .experienceVersion(LearningExperienceVersion.LEGACY_V1).status("ACTIVE").version(1).build());
        em.flush();

        assertTrue(paths
                .findByUserIdAndAssessmentAttemptIdAndAssessmentRevisionAndPolicyVersionAndExperienceVersionAndStatus(
                        user.getId(), 91L, "rev-1", "policy-1", LearningExperienceVersion.INTERACTIVE_V2, "ACTIVE")
                .isEmpty());
    }
}
