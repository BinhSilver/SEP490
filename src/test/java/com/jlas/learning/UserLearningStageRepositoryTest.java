package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.repository.UserLearningStageRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:stage-repo-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = UserLearningStageRepositoryTest.Cfg.class)
class UserLearningStageRepositoryTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired UserLearningStageRepository stages;

    @Test
    void findsOwnedStageByIdAndNotSomeoneElses() {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var owner = User.builder().role(role).email("owner@b.com").loginType("LOCAL").build();
        var stranger = User.builder().role(role).email("stranger@b.com").loginType("LOCAL").build();
        em.persist(owner);
        em.persist(stranger);
        var path = UserLearningPath.builder().user(owner).title("P").goal("JLPT")
                .targetLevel("N3").currentLevel("N5").status("ACTIVE").version(1).build();
        em.persist(path);
        var stage = UserLearningStage.builder().learningPath(path).level("N4").stageIndex(0)
                .status("ACTIVE").build();
        em.persist(stage);
        em.flush();
        em.clear();

        assertTrue(stages.findByIdAndLearningPathUserId(stage.getId(), owner.getId()).isPresent());
        assertTrue(stages.findByIdAndLearningPathUserId(stage.getId(), stranger.getId()).isEmpty());

        var byIndex = stages.findByLearningPathIdAndStageIndex(path.getId(), 0);
        assertTrue(byIndex.isPresent());
        assertEquals(stage.getId(), byIndex.get().getId());
        assertTrue(stages.findByLearningPathIdAndStageIndex(path.getId(), 1).isEmpty());
    }
}
