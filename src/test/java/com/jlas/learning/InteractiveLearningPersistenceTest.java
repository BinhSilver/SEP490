package com.jlas.learning;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.LearningPathPackageRepository;
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

@DataJpaTest(properties = {"spring.profiles.include=", "spring.datasource.url=jdbc:h2:mem:v2-persistence;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = InteractiveLearningPersistenceTest.Cfg.class)
class InteractiveLearningPersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired LearningPathPackageRepository packages;
    @Autowired UserLearningPathItemRepository items;

    @Test void storesVersionedPackagesAndScopesReadsToOwner() {
        var role = Role.builder().name("LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("v2-owner@jlas.test").loginType("LOCAL").build(); em.persist(owner);
        var stranger = User.builder().role(role).email("v2-other@jlas.test").loginType("LOCAL").build(); em.persist(stranger);
        var legacy = UserLearningPath.builder().user(owner).title("Legacy").status("SUPERSEDED").version(1).build(); em.persist(legacy);
        assertEquals(LearningExperienceVersion.LEGACY_V1, legacy.getExperienceVersion());
        var path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE").version(2)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build(); em.persist(path);
        var pkg = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING").dayNumber(1).orderIndex(1)
                .title("Ngày 1").estimatedMinutes(30).status(LearningPackageStatus.AVAILABLE).build(); em.persist(pkg);
        var activity = UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).orderIndex(1)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.LOCKED).build(); em.persist(activity);
        em.flush(); em.clear();

        assertTrue(packages.findOwnedById(pkg.getId(), owner.getId()).isPresent());
        assertTrue(packages.findOwnedById(pkg.getId(), stranger.getId()).isEmpty());
        assertEquals(LearningActivityType.VOCABULARY,
                items.findByLearningPackageIdOrderByOrderIndex(pkg.getId()).getFirst().getActivityType());
    }
}
