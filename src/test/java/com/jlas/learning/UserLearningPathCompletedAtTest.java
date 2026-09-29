package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.UserLearningPath;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:path-completed-at-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = UserLearningPathCompletedAtTest.Cfg.class)
class UserLearningPathCompletedAtTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg { }

    @Autowired EntityManager em;

    @Test
    void pathStartsWithNullCompletedAtAndCanBeMarkedComplete() {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var user = User.builder().role(role).email("a@b.com").loginType("LOCAL").build();
        em.persist(user);
        var path = UserLearningPath.builder()
                .user(user).title("Test path").goal("JLPT").targetLevel("N4").currentLevel("N5")
                .status("ACTIVE").version(1).build();
        em.persist(path);
        em.flush();
        em.clear();

        var reloaded = em.find(UserLearningPath.class, path.getId());
        assertNull(reloaded.getCompletedAt());

        var stamp = LocalDateTime.now().withNano(0);
        reloaded.setStatus("COMPLETED");
        reloaded.setCompletedAt(stamp);
        em.flush();
        em.clear();

        var completed = em.find(UserLearningPath.class, path.getId());
        assertEquals("COMPLETED", completed.getStatus());
        assertEquals(stamp, completed.getCompletedAt());
    }
}
