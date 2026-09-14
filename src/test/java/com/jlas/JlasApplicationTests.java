package com.jlas;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class JlasApplicationTests {
	@Autowired
	Environment environment;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Test
	void contextLoads() {
	}

	@Test
	void testProfileDoesNotActivateLocalProfile() {
		assertThat(environment.getActiveProfiles()).containsExactly("test");
	}

	@Test
	void testProfileUsesH2Dialect() {
		var sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
		assertThat(sessionFactory.getJdbcServices().getDialect()).isInstanceOf(H2Dialect.class);
	}

}
