package com.jlas;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.profiles.include=")
@ActiveProfiles("test")
class JlasApplicationTests {

	@Test
	void contextLoads() {
	}

}
