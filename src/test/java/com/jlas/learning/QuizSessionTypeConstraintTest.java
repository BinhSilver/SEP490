package com.jlas.learning;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** [JLAS-W0193] RC Task 6 — a narrow, disclosed-limitation regression test for the {@code chk_quiz_session_type}
 * CHECK constraint in {@code jlas_schema.sql}. H2's {@code ddl-auto=create-drop} derives its schema from JPA
 * entities, not from this raw SQL file (documented at the constraint's own comment, and in the Task 6 rules-doc
 * entry for the KANJI_MICRO_CHECK/GRAMMAR_MICRO_CHECK gap it previously caused) — so no ordinary H2-backed
 * persistence test can catch a missing quiz_type value here. This test at least parses the constraint text
 * itself so a future accidental removal of a micro-check quiz_type is caught by the regression suite, even
 * without a real MySQL connection. It reproduces the exact bug this RC Task 6 run found and fixed: RC Task 4
 * added {@code READING_MICRO_CHECK}/{@code LISTENING_MICRO_CHECK} at the Java level
 * ({@code AssessmentComprehensionContentAdapter.microCheckType()}), but RC Task 2's schema migration (016)
 * never widened this constraint to match, so a real MySQL database would have rejected every comprehension
 * micro-check submit. Fixed in {@code sql/migrations/017_interactive_comprehension_quiz_type.sql}. */
class QuizSessionTypeConstraintTest {

    @Test void chkQuizSessionTypeAllowsEveryMicroCheckQuizTypeTheEngineCanIssue() throws IOException {
        String constraint = readConstraint();
        // Legacy quiz types, unaffected by this task.
        assertTrue(constraint.contains("'GATE'"));
        assertTrue(constraint.contains("'LESSON'"));
        assertTrue(constraint.contains("'GRAMMAR'"));
        assertTrue(constraint.contains("'FLASHCARD'"));
        // Every InteractiveStudyEngine-issued micro-check quiz_type (Vocabulary/Kanji/Grammar/Reading/Listening).
        assertTrue(constraint.contains("'VOCAB_MICRO_CHECK'"));
        assertTrue(constraint.contains("'KANJI_MICRO_CHECK'"));
        assertTrue(constraint.contains("'GRAMMAR_MICRO_CHECK'"));
        assertTrue(constraint.contains("'READING_MICRO_CHECK'"),
                "chk_quiz_session_type must allow READING_MICRO_CHECK (RC Task 4/6 gap)");
        assertTrue(constraint.contains("'LISTENING_MICRO_CHECK'"),
                "chk_quiz_session_type must allow LISTENING_MICRO_CHECK (RC Task 4/6 gap)");
    }

    // Migration 017 was folded into the consolidated V2 migration (004-017 → 004).
    @Test void migration017WidensTheSameConstraintToTheSameFinalValueSet() throws IOException {
        Path migration = repoRoot().resolve("sql/migrations/004_interactive_learning_path_v2_consolidated.sql");
        assertTrue(Files.exists(migration), "expected sql/migrations/004_interactive_learning_path_v2_consolidated.sql");
        String content = Files.readString(migration, StandardCharsets.UTF_8);
        assertTrue(content.contains("chk_quiz_session_type"));
        assertTrue(content.contains("'READING_MICRO_CHECK'"));
        assertTrue(content.contains("'LISTENING_MICRO_CHECK'"));
    }

    /** [JLAS-W0108] Task 10: replay sessions and the widened column must be in both schema and migration. */
    @Test void practiceReplayIsAllowedAndQuizTypeColumnFitsTheLongestQuizType() throws IOException {
        assertTrue(readConstraint().contains("'PRACTICE_REPLAY'"));
        String schema = Files.readString(repoRoot().resolve("jlas_schema.sql"), StandardCharsets.UTF_8);
        assertTrue(schema.contains("quiz_type        VARCHAR(30)"), "FOUNDATION_MICRO_CHECK is 22 chars");
        String migration = Files.readString(repoRoot().resolve("sql/migrations/009_practice_replay_quiz_type.sql"),
                StandardCharsets.UTF_8);
        assertTrue(migration.contains("'PRACTICE_REPLAY'") && migration.contains("VARCHAR(30)"));
    }

    private String readConstraint() throws IOException {
        String schema = Files.readString(repoRoot().resolve("jlas_schema.sql"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                "CONSTRAINT chk_quiz_session_type CHECK\\s*\\(([^)]*)\\)", Pattern.DOTALL).matcher(schema);
        assertTrue(matcher.find(), "chk_quiz_session_type constraint not found in jlas_schema.sql");
        return matcher.group(1);
    }

    private Path repoRoot() {
        return Path.of("").toAbsolutePath();
    }
}
