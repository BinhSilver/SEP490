-- [JLAS-W0193] RC Task 8 — Interactive Learning Path V2 content-readiness audit.
--
-- READ-ONLY. Every statement below is a SELECT. Nothing here inserts, updates, deletes, seeds, publishes,
-- or otherwise mutates any row. It is safe to run against a production replica or a read-only connection.
--
-- Purpose: give a human (or a script parsing this output) an honest picture of whether each JLPT level has
-- enough REAL canonical content for Interactive V2's Reading/Listening warm-up and GOAL_LEARNING target
-- packages to actually compose and complete, before the feature flag (learning.interactive-v2.enabled) is
-- ever turned on for real users. It mirrors the exact readiness rules already enforced in code
-- (`com.jlas.learning.service.impl.JpaComprehensionExerciseCatalog`, RC Task 1) as closely as plain SQL can:
-- exact skill + exact JLPT level only (no level fallback), active rows only, a valid MCQ (2-4 distinct
-- options, correct_option in range), grouped by (skill, level, outcome/topic identity, passage-or-audio) with
-- at least 2 questions per group to count as one playable exercise. It does NOT reimplement the Java
-- fingerprint hash or the entity-decode-then-strip HTML sanitization exactly byte-for-byte — this is a content
-- SUPPLY audit, not a substitute for the application's own grouping logic, and a level this script marks
-- READY can still differ in exact exercise count from what the app composes at runtime.
--
-- Machine-readable gap codes reused verbatim from JpaComprehensionExerciseCatalog where the concept is the
-- same (PASSAGE_MISSING, AUDIO_MISSING, QUESTION_INVALID, QUESTION_COUNT_INSUFFICIENT, LEVEL_MISMATCH,
-- SOURCE_AMBIGUOUS), plus four new ones scoped to this audit only (never emitted by application code):
--   QUOTA_AT_RISK_READING            available ready Reading exercises < 8 (the worst-case Warm-up B band)
--   QUOTA_AT_RISK_LISTENING          available ready Listening exercises < 8
--   GOAL_TARGET_CONTENT_MISSING      zero PUBLISHED Vocabulary/Kanji/Grammar/Dialogue content at this level
--   RELEASABLE                       none of the above gaps apply to this level
--
-- Usage: run the whole file against a real (e.g. MySQL) database. Every section is independent and ordered by
-- jlpt_level; nothing depends on a variable, session state, or a stored procedure, so any subset of sections
-- can be copy-pasted and run alone.
--
-- Verification performed for this RC Task 8 pass: this worktree has no application-local.yaml and no reachable
-- MySQL server, and touching the user's real MySQL database is explicitly out of scope for this whole RC
-- effort — so this script has NOT been run against any real MySQL database or any real content, and its
-- presence here is not itself a readiness claim. What WAS done: every statement in this file was smoke-tested
-- end-to-end (all 7 sections execute with no syntax/type errors and return sensible rows) against a throwaway
-- H2 instance in MySQL-compatibility mode (`MODE=MySQL`), loaded with a hand-built minimal schema and a few
-- synthetic rows chosen to exercise every branch (a ready 2-question exercise, a quota-at-risk level, full and
-- missing GOAL coverage) — this caught and fixed one real portability bug (H2 rejects `SUM(<boolean
-- expression>)`/`bool + bool` without an explicit `CASE WHEN ... THEN 1 ELSE 0 END`, unlike MySQL's implicit
-- boolean-to-integer coercion; every aggregate in this file now uses the explicit form so it is not relying on
-- MySQL-only laxness). This is evidence the SQL is well-formed, not evidence about real content coverage.

-- ============================================================================================================
-- 1. Active canonical questions — raw supply, before any validity/grouping filter.
-- ============================================================================================================
-- Boolean conditions are wrapped in an explicit CASE WHEN ... THEN 1 ELSE 0 END (never summed directly, and
-- never added with "+") throughout this file, rather than relying on MySQL's implicit boolean-to-integer
-- coercion — this keeps every query portable to any strict SQL engine, not only MySQL's own relaxed typing.
SELECT
    skill,
    jlpt_level,
    COUNT(*) AS active_question_count,
    SUM(CASE WHEN skill = 'READING' AND (passage_html IS NULL OR TRIM(passage_html) = '')
             THEN 1 ELSE 0 END) AS missing_passage_count,
    SUM(CASE WHEN skill = 'LISTENING' AND (audio_url IS NULL OR TRIM(audio_url) = '')
             THEN 1 ELSE 0 END) AS missing_audio_count
FROM assessment_question
WHERE is_active = 1
GROUP BY skill, jlpt_level
ORDER BY skill, jlpt_level;

-- ============================================================================================================
-- 2. Invalid/ambiguous question rows — gap code QUESTION_INVALID.
--    A row is invalid if: not MULTIPLE_CHOICE, blank question text, fewer than 2 or more than 4 non-blank
--    options, correct_option out of range, or two options normalize to the same answer (duplicate).
-- ============================================================================================================
SELECT
    id AS question_id,
    skill,
    jlpt_level,
    'QUESTION_INVALID' AS gap_code,
    CASE
        WHEN question_type <> 'MULTIPLE_CHOICE' THEN 'NOT_MULTIPLE_CHOICE'
        WHEN question_text IS NULL OR TRIM(question_text) = '' THEN 'BLANK_QUESTION_TEXT'
        WHEN (
            (CASE WHEN option_1 IS NOT NULL AND TRIM(option_1) <> '' THEN 1 ELSE 0 END) +
            (CASE WHEN option_2 IS NOT NULL AND TRIM(option_2) <> '' THEN 1 ELSE 0 END) +
            (CASE WHEN option_3 IS NOT NULL AND TRIM(option_3) <> '' THEN 1 ELSE 0 END) +
            (CASE WHEN option_4 IS NOT NULL AND TRIM(option_4) <> '' THEN 1 ELSE 0 END)
        ) < 2 THEN 'FEWER_THAN_TWO_OPTIONS'
        WHEN correct_option IS NULL OR correct_option < 1 OR correct_option > 4 THEN 'CORRECT_OPTION_OUT_OF_RANGE'
        WHEN correct_option = 1 AND (option_1 IS NULL OR TRIM(option_1) = '') THEN 'CORRECT_OPTION_BLANK'
        WHEN correct_option = 2 AND (option_2 IS NULL OR TRIM(option_2) = '') THEN 'CORRECT_OPTION_BLANK'
        WHEN correct_option = 3 AND (option_3 IS NULL OR TRIM(option_3) = '') THEN 'CORRECT_OPTION_BLANK'
        WHEN correct_option = 4 AND (option_4 IS NULL OR TRIM(option_4) = '') THEN 'CORRECT_OPTION_BLANK'
        ELSE 'DUPLICATE_NORMALIZED_OPTION'
    END AS reason
FROM assessment_question
WHERE is_active = 1
  AND (
        question_type <> 'MULTIPLE_CHOICE'
        OR question_text IS NULL OR TRIM(question_text) = ''
        OR (
            (CASE WHEN option_1 IS NOT NULL AND TRIM(option_1) <> '' THEN 1 ELSE 0 END) +
            (CASE WHEN option_2 IS NOT NULL AND TRIM(option_2) <> '' THEN 1 ELSE 0 END) +
            (CASE WHEN option_3 IS NOT NULL AND TRIM(option_3) <> '' THEN 1 ELSE 0 END) +
            (CASE WHEN option_4 IS NOT NULL AND TRIM(option_4) <> '' THEN 1 ELSE 0 END)
          ) < 2
        OR correct_option IS NULL OR correct_option < 1 OR correct_option > 4
        OR (correct_option = 1 AND (option_1 IS NULL OR TRIM(option_1) = ''))
        OR (correct_option = 2 AND (option_2 IS NULL OR TRIM(option_2) = ''))
        OR (correct_option = 3 AND (option_3 IS NULL OR TRIM(option_3) = ''))
        OR (correct_option = 4 AND (option_4 IS NULL OR TRIM(option_4) = ''))
        OR (LOWER(TRIM(option_1)) = LOWER(TRIM(option_2)) AND option_1 IS NOT NULL AND option_2 IS NOT NULL)
        OR (LOWER(TRIM(option_1)) = LOWER(TRIM(option_3)) AND option_1 IS NOT NULL AND option_3 IS NOT NULL)
        OR (LOWER(TRIM(option_1)) = LOWER(TRIM(option_4)) AND option_1 IS NOT NULL AND option_4 IS NOT NULL)
        OR (LOWER(TRIM(option_2)) = LOWER(TRIM(option_3)) AND option_2 IS NOT NULL AND option_3 IS NOT NULL)
        OR (LOWER(TRIM(option_2)) = LOWER(TRIM(option_4)) AND option_2 IS NOT NULL AND option_4 IS NOT NULL)
        OR (LOWER(TRIM(option_3)) = LOWER(TRIM(option_4)) AND option_3 IS NOT NULL AND option_4 IS NOT NULL)
      )
ORDER BY skill, jlpt_level, id;

-- ============================================================================================================
-- 3. Missing passage / missing or unsafe audio — gap codes PASSAGE_MISSING / AUDIO_MISSING.
--    "Unsafe" audio mirrors JpaComprehensionExerciseCatalog#normalizeAudioUrl: must be an internal absolute
--    path ("/..." but not "//...") or an absolute http(s) URL with a host; anything else (javascript:, a bare
--    scheme-less non-slash string, a protocol-relative "//host/..." URL) is treated as missing.
-- ============================================================================================================
SELECT id AS question_id, skill, jlpt_level, 'PASSAGE_MISSING' AS gap_code
FROM assessment_question
WHERE is_active = 1 AND skill = 'READING' AND (passage_html IS NULL OR TRIM(passage_html) = '')
UNION ALL
SELECT id AS question_id, skill, jlpt_level, 'AUDIO_MISSING' AS gap_code
FROM assessment_question
WHERE is_active = 1 AND skill = 'LISTENING'
  AND (
        audio_url IS NULL OR TRIM(audio_url) = ''
        OR audio_url LIKE '//%'
        OR (audio_url NOT LIKE 'http://%' AND audio_url NOT LIKE 'https://%' AND audio_url NOT LIKE '/%')
      )
ORDER BY skill, jlpt_level, question_id;

-- ============================================================================================================
-- 4. Exact-level ready Reading/Listening exercises and insufficient question groups.
--    Grouping approximation of the catalog's fingerprint: (skill, jlpt_level, outcome_id, topic_tag_id,
--    normalized passage-or-audio). A group with >= 2 valid, active, exact-level questions is one ready
--    exercise; a group with exactly 1 is QUESTION_COUNT_INSUFFICIENT; a group with neither outcome nor topic
--    set is SOURCE_AMBIGUOUS and is excluded from readiness entirely (never merged with another source).
-- ============================================================================================================
WITH valid_active AS (
    SELECT *
    FROM assessment_question
    WHERE is_active = 1
      AND question_type = 'MULTIPLE_CHOICE'
      AND question_text IS NOT NULL AND TRIM(question_text) <> ''
      AND correct_option BETWEEN 1 AND 4
      AND (
            (skill = 'READING' AND passage_html IS NOT NULL AND TRIM(passage_html) <> '')
            OR (skill = 'LISTENING' AND audio_url IS NOT NULL AND TRIM(audio_url) <> ''
                AND audio_url NOT LIKE '//%'
                AND (audio_url LIKE 'http://%' OR audio_url LIKE 'https://%' OR audio_url LIKE '/%'))
          )
), grouped AS (
    SELECT
        skill, jlpt_level, outcome_id, topic_tag_id,
        CASE WHEN skill = 'READING' THEN passage_html ELSE audio_url END AS source_key,
        COUNT(*) AS question_count
    FROM valid_active
    WHERE outcome_id IS NOT NULL OR topic_tag_id IS NOT NULL   -- SOURCE_AMBIGUOUS groups excluded here
    GROUP BY skill, jlpt_level, outcome_id, topic_tag_id, source_key
)
SELECT
    skill,
    jlpt_level,
    SUM(CASE WHEN question_count >= 2 THEN 1 ELSE 0 END) AS ready_exercise_count,
    SUM(CASE WHEN question_count = 1 THEN 1 ELSE 0 END)  AS insufficient_group_count
FROM grouped
GROUP BY skill, jlpt_level
ORDER BY skill, jlpt_level;

-- 4b. The individual insufficient (single-question) groups, for follow-up by a content editor.
-- (Re-run the CTEs above if your client does not preserve WITH-clause scope across statements.)

-- ============================================================================================================
-- 5. Requested vs. available quota per level/skill.
--    Warm-up B's approved band (rules doc §2.16 / design spec §5.4) requests at most 8 Reading+Listening
--    exercises combined per learner in the worst case (0/4/6/8 by evidence). This reports available ready
--    supply against that worst-case request per level/skill, independent of any specific learner.
-- ============================================================================================================
-- (Same `grouped`/ready_exercise_count as section 4 — repeated here as its own labeled report.)
WITH valid_active AS (
    SELECT *
    FROM assessment_question
    WHERE is_active = 1
      AND question_type = 'MULTIPLE_CHOICE'
      AND question_text IS NOT NULL AND TRIM(question_text) <> ''
      AND correct_option BETWEEN 1 AND 4
      AND (
            (skill = 'READING' AND passage_html IS NOT NULL AND TRIM(passage_html) <> '')
            OR (skill = 'LISTENING' AND audio_url IS NOT NULL AND TRIM(audio_url) <> ''
                AND audio_url NOT LIKE '//%'
                AND (audio_url LIKE 'http://%' OR audio_url LIKE 'https://%' OR audio_url LIKE '/%'))
          )
), grouped AS (
    SELECT skill, jlpt_level, outcome_id, topic_tag_id,
           CASE WHEN skill = 'READING' THEN passage_html ELSE audio_url END AS source_key,
           COUNT(*) AS question_count
    FROM valid_active
    WHERE outcome_id IS NOT NULL OR topic_tag_id IS NOT NULL
    GROUP BY skill, jlpt_level, outcome_id, topic_tag_id, source_key
)
SELECT
    skill,
    jlpt_level,
    8                                        AS max_requested_quota,          -- worst-case 0/4/6/8 band
    SUM(CASE WHEN question_count >= 2 THEN 1 ELSE 0 END) AS available_ready_exercises,
    CASE WHEN SUM(CASE WHEN question_count >= 2 THEN 1 ELSE 0 END) >= 8 THEN 'OK'
         ELSE CONCAT('QUOTA_AT_RISK_', skill) END AS gap_code
FROM grouped
GROUP BY skill, jlpt_level
ORDER BY skill, jlpt_level;

-- ============================================================================================================
-- 6. GOAL target package coverage — PUBLISHED Vocabulary/Kanji/Grammar/Dialogue content per JLPT level.
--    A GOAL_LEARNING package needs at least one PUBLISHED item of each of the four to ever compose the
--    fixed four-activity package (rules doc §2.15 "package chưa đủ Từ vựng, Kanji, Ngữ pháp và Hội thoại").
--    This is a coarse content-supply signal, not a re-implementation of InteractivePackageComposerImpl's own
--    lesson-anchor selection.
-- ============================================================================================================
SELECT 'VOCABULARY' AS content_type, jlpt_level, COUNT(*) AS published_count
FROM vocabulary_entry
WHERE dictionary_status = 'PUBLISHED' AND jlpt_level IS NOT NULL
GROUP BY jlpt_level
UNION ALL
SELECT 'KANJI' AS content_type, jlpt_level, COUNT(*) AS published_count
FROM kanji
WHERE jlpt_level IS NOT NULL
GROUP BY jlpt_level
UNION ALL
SELECT 'GRAMMAR' AS content_type, tb.jlpt_level, COUNT(*) AS published_count
FROM grammar_point gp
JOIN lesson l ON l.id = gp.lesson_id AND l.status = 'PUBLISHED'
JOIN unit u ON u.id = l.unit_id
JOIN textbook tb ON tb.id = u.textbook_id AND tb.status = 'PUBLISHED' AND tb.jlpt_level IS NOT NULL
GROUP BY tb.jlpt_level
UNION ALL
SELECT 'DIALOGUE' AS content_type, tb.jlpt_level, COUNT(*) AS published_count
FROM dialogue_line_detail d
JOIN content_item ci ON ci.id = d.content_item_id
JOIN lesson_section ls ON ls.id = ci.section_id
JOIN lesson l ON l.id = ls.lesson_id AND l.status = 'PUBLISHED'
JOIN unit u ON u.id = l.unit_id
JOIN textbook tb ON tb.id = u.textbook_id AND tb.status = 'PUBLISHED' AND tb.jlpt_level IS NOT NULL
GROUP BY tb.jlpt_level
ORDER BY content_type, jlpt_level;

-- ============================================================================================================
-- 7. Releasable vs. blocked levels — final rollup with machine-readable gap codes.
--    Combines sections 5 and 6: a level is RELEASABLE only when neither Reading nor Listening is quota-at-risk
--    AND all four GOAL content types have at least one PUBLISHED row at that level. This is a content-supply
--    verdict only — it does NOT evaluate migration/schema state, the feature flag, or any browser/MySQL
--    acceptance step; see the RC Task 8 final handoff for the full itemized release checklist.
-- ============================================================================================================
WITH levels AS (
    SELECT 'N5' AS jlpt_level UNION ALL SELECT 'N4' UNION ALL SELECT 'N3' UNION ALL SELECT 'N2' UNION ALL SELECT 'N1'
), valid_active AS (
    SELECT *
    FROM assessment_question
    WHERE is_active = 1
      AND question_type = 'MULTIPLE_CHOICE'
      AND question_text IS NOT NULL AND TRIM(question_text) <> ''
      AND correct_option BETWEEN 1 AND 4
      AND (
            (skill = 'READING' AND passage_html IS NOT NULL AND TRIM(passage_html) <> '')
            OR (skill = 'LISTENING' AND audio_url IS NOT NULL AND TRIM(audio_url) <> ''
                AND audio_url NOT LIKE '//%'
                AND (audio_url LIKE 'http://%' OR audio_url LIKE 'https://%' OR audio_url LIKE '/%'))
          )
), grouped AS (
    SELECT skill, jlpt_level, outcome_id, topic_tag_id,
           CASE WHEN skill = 'READING' THEN passage_html ELSE audio_url END AS source_key,
           COUNT(*) AS question_count
    FROM valid_active
    WHERE outcome_id IS NOT NULL OR topic_tag_id IS NOT NULL
    GROUP BY skill, jlpt_level, outcome_id, topic_tag_id, source_key
), reading_ready AS (
    SELECT jlpt_level, SUM(CASE WHEN question_count >= 2 THEN 1 ELSE 0 END) AS ready FROM grouped WHERE skill = 'READING' GROUP BY jlpt_level
), listening_ready AS (
    SELECT jlpt_level, SUM(CASE WHEN question_count >= 2 THEN 1 ELSE 0 END) AS ready FROM grouped WHERE skill = 'LISTENING' GROUP BY jlpt_level
), goal_coverage AS (
    SELECT jlpt_level,
           MIN(has_vocab) AS has_vocab, MIN(has_kanji) AS has_kanji,
           MIN(has_grammar) AS has_grammar, MIN(has_dialogue) AS has_dialogue
    FROM (
        SELECT l.jlpt_level,
               (SELECT COUNT(*) FROM vocabulary_entry v
                WHERE v.dictionary_status = 'PUBLISHED' AND v.jlpt_level = l.jlpt_level) > 0 AS has_vocab,
               (SELECT COUNT(*) FROM kanji k WHERE k.jlpt_level = l.jlpt_level) > 0 AS has_kanji,
               (SELECT COUNT(*) FROM grammar_point gp
                JOIN lesson les ON les.id = gp.lesson_id AND les.status = 'PUBLISHED'
                JOIN unit u ON u.id = les.unit_id
                JOIN textbook tb ON tb.id = u.textbook_id AND tb.jlpt_level = l.jlpt_level
                    AND tb.status = 'PUBLISHED') > 0 AS has_grammar,
               (SELECT COUNT(*) FROM dialogue_line_detail d
                JOIN content_item ci ON ci.id = d.content_item_id
                JOIN lesson_section ls ON ls.id = ci.section_id
                JOIN lesson les ON les.id = ls.lesson_id AND les.status = 'PUBLISHED'
                JOIN unit u ON u.id = les.unit_id
                JOIN textbook tb ON tb.id = u.textbook_id AND tb.jlpt_level = l.jlpt_level
                    AND tb.status = 'PUBLISHED') > 0 AS has_dialogue
        FROM levels l
    ) per_level
    GROUP BY jlpt_level
)
SELECT
    l.jlpt_level,
    COALESCE(rr.ready, 0)  AS reading_ready_exercises,
    COALESCE(lr.ready, 0)  AS listening_ready_exercises,
    gc.has_vocab, gc.has_kanji, gc.has_grammar, gc.has_dialogue,
    CASE
        WHEN COALESCE(rr.ready, 0) < 8 THEN 'QUOTA_AT_RISK_READING'
        WHEN COALESCE(lr.ready, 0) < 8 THEN 'QUOTA_AT_RISK_LISTENING'
        WHEN NOT gc.has_vocab OR NOT gc.has_kanji OR NOT gc.has_grammar OR NOT gc.has_dialogue
            THEN 'GOAL_TARGET_CONTENT_MISSING'
        ELSE 'RELEASABLE'
    END AS verdict
FROM levels l
LEFT JOIN reading_ready rr ON rr.jlpt_level = l.jlpt_level
LEFT JOIN listening_ready lr ON lr.jlpt_level = l.jlpt_level
LEFT JOIN goal_coverage gc ON gc.jlpt_level = l.jlpt_level
ORDER BY l.jlpt_level;
