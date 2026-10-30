package com.jlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

@SpringBootApplication
@EntityScan(basePackages = {
		"com.jlas.admin.entity",
		"com.jlas.assessment.entity",
		"com.jlas.auth.entity",
		"com.jlas.conjugation",
		"com.jlas.billing.entity",
		"com.jlas.dictionary.entity",
		"com.jlas.flashcard.entity",
		"com.jlas.game.entity",
		"com.jlas.kaiwa.model",
		"com.jlas.learning.entity",
		"com.jlas.mission.entity",
		"com.jlas.moderation.entity",
		"com.jlas.moderation.model",
		"com.jlas.notification.entity",
		"com.jlas.shadowing.model"
})
@EnableJpaRepositories(basePackages = "com.jlas")
public class JlasApplication {

	public static void main(String[] args) {
		SpringApplication.run(JlasApplication.class, args);
	}

}
