package com.jlas.mission.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Mission module wiring: properties, scheduling and the replaceable clock. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MissionProperties.class)
public class MissionConfig {

    @Bean
    public Clock missionClock() {
        return Clock.systemDefaultZone();
    }
}
