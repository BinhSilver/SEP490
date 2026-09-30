package com.jlas.mission.config;

import com.jlas.mission.enums.MissionCode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Inserts missing built-in missions. Existing rows are left untouched so tuned values survive restarts. */
@Component
@Profile("!test")
@Order(2)
public class MissionDefinitionSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public MissionDefinitionSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (MissionCode code : MissionCode.values()) {
            jdbc.update("INSERT IGNORE INTO mission_definition "
                            + "(code, mission_type, title, description, target_count, reward_credits, display_order, is_active) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                    code.name(), code.type(), code.title(), code.description(),
                    code.target(), code.reward(), code.displayOrder());
        }
    }
}
