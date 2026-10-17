package com.jlas.mission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MissionSchemaContractTest {

    @Test
    void canonicalSchemaAndIncrementalMigrationOwnMissionBookkeepingTables() throws Exception {
        String schema = Files.readString(Path.of("jlas_schema.sql"));
        Path migrationPath = Path.of("sql", "migrations", "013_mission_bookkeeping_tables.sql");
        assertTrue(Files.exists(migrationPath), "incremental mission migration is required");
        String migration = Files.readString(migrationPath);

        for (String table : List.of("learning_event_receipt", "user_time_zone", "pending_credit_award",
                "reward_celebration", "streak_freeze_day")) {
            assertTrue(schema.contains("CREATE TABLE " + table), table + " missing from canonical schema");
            assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS " + table),
                    table + " missing from compatibility migration");
        }
        assertTrue(schema.contains("PRIMARY KEY (event_id)"));
        assertTrue(schema.contains("PRIMARY KEY (user_id, freeze_date)"));
        assertTrue(schema.contains("UNIQUE KEY uk_pending_award_reference"));
    }

    @Test
    void applicationStartupDoesNotCreateOrAlterSchema() {
        assertFalse(Files.exists(Path.of("src", "main", "java", "com", "jlas", "mission", "config",
                "MissionSchemaInitializer.java")));
    }
}
