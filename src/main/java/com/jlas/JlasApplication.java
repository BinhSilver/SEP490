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
		"com.jlas.community.entity",
		"com.jlas.dictionary.entity",
		"com.jlas.flashcard.entity",
		"com.jlas.game.entity",
		"com.jlas.jlptpath.entity", // [JLAS-W0108]
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

	/** Learners are in Vietnam; override with APP_TIME_ZONE only if that ever changes. */
	static final String DEFAULT_TIME_ZONE = "Asia/Ho_Chi_Minh";

	public static void main(String[] args) {
		// Times are stored as LocalDateTime.now() without a zone and the browser reads them as Vietnam time. A host
		// on UTC (as production is) would make a notification created just now show "7 giờ trước" and move daily
		// resets to 07:00, so the clock is fixed before anything starts.
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(
				System.getenv().getOrDefault("APP_TIME_ZONE", DEFAULT_TIME_ZONE)));
		SpringApplication.run(JlasApplication.class, args);
	}

}
