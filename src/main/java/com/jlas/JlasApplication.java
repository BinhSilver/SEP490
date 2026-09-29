package com.jlas;

import com.jlas.shadowing.repository.ShadowingPracticeSessionRepository;
import com.jlas.shadowing.repository.ShadowingSeriesRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

@SpringBootApplication
@EntityScan(basePackages = {
		"com.jlas.assessment.entity",
		"com.jlas.auth.entity",
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
@EnableJpaRepositories(
		basePackages = "com.jlas",
		excludeFilters = @ComponentScan.Filter(
				type = FilterType.ASSIGNABLE_TYPE,
				classes = {ShadowingPracticeSessionRepository.class, ShadowingSeriesRepository.class}
		)
)
public class JlasApplication {

	public static void main(String[] args) {
		SpringApplication.run(JlasApplication.class, args);
	}

}
