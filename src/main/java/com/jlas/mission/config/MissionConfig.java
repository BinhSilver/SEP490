package com.jlas.mission.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides a replaceable clock for day-boundary logic in the mission module. */
@Configuration
public class MissionConfig {

    @Bean
    public Clock missionClock() {
        return Clock.systemDefaultZone();
    }
}
