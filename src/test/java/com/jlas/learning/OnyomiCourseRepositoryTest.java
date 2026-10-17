package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.learning.repository.OnyomiCourseRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:onyomi-course-repository-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = OnyomiCourseRepositoryTest.RepositoryConfiguration.class)
class OnyomiCourseRepositoryTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackageClasses = OnyomiCourseRepository.class)
    static class RepositoryConfiguration { }

    @Autowired
    private OnyomiCourseRepository repository;

    @Test
    void exceptionQuestionQueryLoadsAndRunsAgainstJsonContext() {
        assertTrue(repository.findExceptionQuestions(List.of(-1L)).isEmpty());
    }
}
