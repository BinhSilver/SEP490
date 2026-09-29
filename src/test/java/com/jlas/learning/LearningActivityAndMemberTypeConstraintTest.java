package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** [JLAS-W0193] RC Task 8 hardening — same disclosed-limitation posture as {@code QuizSessionTypeConstraintTest}
 * (RC Task 6), extended to the other two enum-backed CHECK constraints RC Task 2's migration 016 widened:
 * {@code chk_learning_path_item_activity_type} and {@code chk_learning_activity_member_type}. H2's
 * {@code ddl-auto=create-drop} derives its schema from the JPA entities (the Java enums below), not from
 * {@code jlas_schema.sql}'s raw {@code CHECK}, so a persistence test alone could never catch these two
 * constraints drifting from {@link LearningActivityType}/{@link LearningMemberType} the way the
 * {@code READING_MICRO_CHECK}/{@code LISTENING_MICRO_CHECK} gap (§2.18) went uncaught until this RC. Verified
 * present and in sync as of this hardening pass; this test guards against a future regression, not a bug
 * found now. */
class LearningActivityAndMemberTypeConstraintTest {

    @Test void chkLearningPathItemActivityTypeAllowsEveryLearningActivityTypeValue() throws IOException {
        String constraint = readConstraint("chk_learning_path_item_activity_type");
        for (LearningActivityType type : LearningActivityType.values()) {
            assertTrue(constraint.contains("'" + type.name() + "'"),
                    "chk_learning_path_item_activity_type must allow " + type.name());
        }
    }

    @Test void chkLearningActivityMemberTypeAllowsEveryLearningMemberTypeValue() throws IOException {
        String constraint = readConstraint("chk_learning_activity_member_type");
        for (LearningMemberType type : LearningMemberType.values()) {
            assertTrue(constraint.contains("'" + type.name() + "'"),
                    "chk_learning_activity_member_type must allow " + type.name());
        }
    }

    @Test void migration016WidensBothConstraintsToTheirCurrentValueSet() throws IOException {
        Path migration = repoRoot().resolve("sql/migrations/016_interactive_comprehension_activities.sql");
        assertTrue(Files.exists(migration), "expected sql/migrations/016_interactive_comprehension_activities.sql");
        String content = Files.readString(migration, StandardCharsets.UTF_8);
        for (LearningActivityType type : LearningActivityType.values()) {
            assertTrue(content.contains("'" + type.name() + "'"));
        }
        for (LearningMemberType type : LearningMemberType.values()) {
            assertTrue(content.contains("'" + type.name() + "'"));
        }
    }

    private String readConstraint(String name) throws IOException {
        String schema = Files.readString(repoRoot().resolve("jlas_schema.sql"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                "CONSTRAINT " + name + " CHECK[\\s\\S]*?\\(([^)]*)\\)", Pattern.DOTALL).matcher(schema);
        assertTrue(matcher.find(), name + " constraint not found in jlas_schema.sql");
        return matcher.group(1);
    }

    private Path repoRoot() {
        return Path.of("").toAbsolutePath();
    }
}
