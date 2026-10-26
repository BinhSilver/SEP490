-- [JLAS-W0108/W0193] Learning, Reading/Listening and Smart Test readiness checks.
-- Read-only: run against the selected J-LAS database after shared and Learning seeds.
-- Each section below is preserved from its original audit file.

-- ===== sql/audits/2026-09-29-interactive-v2-content-readiness-audit.sql =====
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

-- ===== sql/audits/2026-10-09-core-dialogue-audio-gaps.sql =====
-- Read-only Release B audit. Run against the intended J-LAS database.
-- These core lessons contain dialogue lines but no audio on the corresponding
-- content items. A digitized-book track is not a substitute unless its script
-- and source identity are independently matched and approved.
SELECT t.jlpt_level,
       l.id AS lesson_id,
       COUNT(DISTINCT d.content_item_id) AS dialogue_items,
       COUNT(DISTINCT a.owner_id) AS audio_items
FROM lesson l
JOIN unit u ON u.id = l.unit_id
JOIN textbook t ON t.id = u.textbook_id
JOIN lesson_section s ON s.lesson_id = l.id
JOIN content_item i ON i.section_id = s.id
JOIN dialogue_line_detail d ON d.content_item_id = i.id
LEFT JOIN content_audio a ON a.owner_type = 'CONTENT_ITEM'
    AND a.owner_id = i.id
    AND a.audio_url IS NOT NULL AND a.audio_url <> ''
WHERE t.id IN (4, 5, 6)
GROUP BY t.jlpt_level, l.id
HAVING dialogue_items > audio_items
ORDER BY t.jlpt_level, l.id;

-- ===== sql/audits/2026-10-12-smart-test-jlpt-readiness.sql =====
-- Read-only UAT gate. Run against the exact web database, with utf8mb4 enabled.
-- Smart Test requires five distinct questions per skill/batch. Kanji is
-- additionally deduplicated by context_json.lexeme/word in the selector.
-- A first-batch PASS here does not prove later adaptive batches are stocked.
-- A same-level second batch needs five more non-repeated Kanji lexemes.
SELECT q.jlpt_level,
       q.skill,
       COUNT(*) AS eligible_questions,
       COUNT(DISTINCT CASE WHEN q.skill = 'KANJI'
           THEN COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(q.context_json, '$.lexeme')), ''),
                         NULLIF(JSON_UNQUOTE(JSON_EXTRACT(q.context_json, '$.word')), ''),
                         CONCAT('question:', q.id))
           ELSE CONCAT('question:', q.id) END) AS selectable_items,
       CASE WHEN COUNT(DISTINCT CASE WHEN q.skill = 'KANJI'
           THEN COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(q.context_json, '$.lexeme')), ''),
                         NULLIF(JSON_UNQUOTE(JSON_EXTRACT(q.context_json, '$.word')), ''),
                         CONCAT('question:', q.id))
           ELSE CONCAT('question:', q.id) END) >= 5
           THEN 'FIRST_BATCH_READY' ELSE 'BLOCKED' END AS first_batch_gate,
       CASE WHEN q.skill = 'KANJI' AND COUNT(DISTINCT
           COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(q.context_json, '$.lexeme')), ''),
                    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(q.context_json, '$.word')), ''),
                    CONCAT('question:', q.id))) >= 10
           THEN 'SAME_LEVEL_SECOND_BATCH_READY'
           WHEN q.skill = 'KANJI' THEN 'SECOND_BATCH_NOT_PROVEN'
           ELSE 'NOT_EVALUATED' END AS same_level_second_batch_gate
FROM assessment_question q
JOIN tag t ON t.id = q.knowledge_tag_id
WHERE q.is_active = 1
  AND q.jlpt_level IN ('N5', 'N4', 'N3', 'N2', 'N1')
  AND q.skill IN ('KANJI', 'VOCABULARY', 'GRAMMAR', 'READING', 'LISTENING')
  AND (t.code = 'JLPT'
       OR JSON_CONTAINS(COALESCE(JSON_EXTRACT(q.context_json, '$.additional_tags'), JSON_ARRAY()),
                        JSON_QUOTE('JLPT')))
GROUP BY q.jlpt_level, q.skill
ORDER BY FIELD(q.jlpt_level, 'N5', 'N4', 'N3', 'N2', 'N1'),
         FIELD(q.skill, 'KANJI', 'VOCABULARY', 'GRAMMAR', 'READING', 'LISTENING');

-- Mechanical quality screen for the existing task-owned Kanji bank.
-- These counts do not establish linguistic accuracy or JLPT-level fitness.
SELECT q.jlpt_level,
       COUNT(*) AS active_bank_rows,
       COUNT(DISTINCT NULLIF(JSON_UNQUOTE(JSON_EXTRACT(q.context_json, '$.lexeme')), ''))
           AS distinct_lexemes,
       SUM(q.option_1 IS NULL OR q.option_2 IS NULL OR q.option_3 IS NULL OR q.option_4 IS NULL
           OR TRIM(q.option_1) = '' OR TRIM(q.option_2) = ''
           OR TRIM(q.option_3) = '' OR TRIM(q.option_4) = '') AS blank_option_rows,
       SUM(q.option_1 = q.option_2 OR q.option_1 = q.option_3 OR q.option_1 = q.option_4
           OR q.option_2 = q.option_3 OR q.option_2 = q.option_4
           OR q.option_3 = q.option_4) AS duplicate_option_rows,
       SUM(q.correct_option NOT BETWEEN 1 AND 4
           OR q.answer_text <> CASE q.correct_option
               WHEN 1 THEN q.option_1 WHEN 2 THEN q.option_2
               WHEN 3 THEN q.option_3 WHEN 4 THEN q.option_4 END)
           AS answer_key_mismatch_rows
FROM assessment_question q
WHERE q.is_active = 1 AND q.skill = 'KANJI'
  AND q.jlpt_level IN ('N5', 'N4', 'N3', 'N2', 'N1')
  AND JSON_UNQUOTE(JSON_EXTRACT(q.context_json, '$.seed_batch')) = 'KANJI_N5_N1_DIVERSE_V3'
GROUP BY q.jlpt_level
ORDER BY FIELD(q.jlpt_level, 'N5', 'N4', 'N3', 'N2', 'N1');

-- ===== sql/audits/learning-jlpt-local-source-checks.sql =====
-- Learning/JLPT local source diagnostics, consolidated from generated outputs on 2026-10-17.
-- Read-only queries. Run against an explicitly selected J-LAS database; some checks use local textbook IDs.

-- ===== 2026-10-14-jlpt-clone-id-audit.sql SHA256 361738e21253e5f3b6fd5e86d7e1602755a517742ea64c08450797a3748fa462 =====
SELECT 'textbook' AS table_name, MAX(id) AS max_id FROM textbook
UNION ALL SELECT 'unit',MAX(id) FROM unit
UNION ALL SELECT 'lesson',MAX(id) FROM lesson
UNION ALL SELECT 'lesson_section',MAX(id) FROM lesson_section
UNION ALL SELECT 'content_item',MAX(id) FROM content_item
UNION ALL SELECT 'grammar_point',MAX(id) FROM grammar_point
UNION ALL SELECT 'dialogue_role',MAX(id) FROM dialogue_role
UNION ALL SELECT 'dialogue_line_chunk',MAX(id) FROM dialogue_line_chunk
UNION ALL SELECT 'content_audio',MAX(id) FROM content_audio
UNION ALL SELECT 'lesson_kanji',MAX(id) FROM lesson_kanji;

SELECT t.id AS textbook_id, MIN(u.id) AS min_unit, MAX(u.id) AS max_unit,
       MIN(l.id) AS min_lesson, MAX(l.id) AS max_lesson,
       MIN(ls.id) AS min_section, MAX(ls.id) AS max_section,
       MIN(ci.id) AS min_item, MAX(ci.id) AS max_item
FROM textbook t
JOIN unit u ON u.textbook_id=t.id
JOIN lesson l ON l.unit_id=u.id
JOIN lesson_section ls ON ls.lesson_id=l.id
JOIN content_item ci ON ci.section_id=ls.id
WHERE t.id IN (2,4)
GROUP BY t.id;

-- ===== 2026-10-14-jlpt-curriculum-coverage.sql SHA256 079d396f5aeef43b5860ee40d1a3cc6a6ea03934c64b9398d0d94e6b20fbeb91 =====
-- Read-only inventory for the active local DB. Source rows remain untouched.
SELECT t.jlpt_level, t.status AS book_status, t.is_learning_path_source,
       COUNT(DISTINCT t.id) AS books,
       COUNT(DISTINCT l.id) AS lessons,
       COUNT(DISTINCT CASE WHEN l.status = 'PUBLISHED' THEN l.id END) AS published_lessons
FROM textbook t
LEFT JOIN unit u ON u.textbook_id = t.id
LEFT JOIN lesson l ON l.unit_id = u.id
WHERE t.jlpt_level IN ('N5','N4','N3')
GROUP BY t.jlpt_level, t.status, t.is_learning_path_source
ORDER BY FIELD(t.jlpt_level,'N5','N4','N3'), t.status, t.is_learning_path_source;

SELECT t.jlpt_level, COUNT(DISTINCT l.id) AS eligible_lessons,
       COUNT(DISTINCT CASE WHEN cv.vocabulary_id IS NOT NULL THEN l.id END) AS lessons_with_vocab,
       COUNT(DISTINCT gp.lesson_id) AS lessons_with_grammar,
       COUNT(DISTINCT CASE WHEN dl.content_item_id IS NOT NULL THEN l.id END) AS lessons_with_dialogue,
       COUNT(DISTINCT CASE WHEN ci.content_type = 'READING' THEN l.id END) AS lessons_with_reading,
       COUNT(DISTINCT CASE WHEN ci.content_type = 'LISTENING' THEN l.id END) AS lessons_with_listening
FROM textbook t
JOIN unit u ON u.textbook_id = t.id
JOIN lesson l ON l.unit_id = u.id
LEFT JOIN lesson_section ls ON ls.lesson_id = l.id
LEFT JOIN content_item ci ON ci.section_id = ls.id
LEFT JOIN content_vocabulary cv ON cv.content_item_id = ci.id
LEFT JOIN grammar_point gp ON gp.lesson_id = l.id
LEFT JOIN dialogue_line_detail dl ON dl.content_item_id = ci.id
WHERE t.jlpt_level IN ('N5','N4','N3') AND t.status = 'PUBLISHED'
  AND t.is_learning_path_source = 1 AND l.status = 'PUBLISHED'
GROUP BY t.jlpt_level
ORDER BY FIELD(t.jlpt_level,'N5','N4','N3');

-- ===== 2026-10-14-jlpt-n5-kanji-eligible-lexemes.sql SHA256 de94f9463822d82953e9193ac4ad60b30e81925cc22265c9e8595bb3796effb0 =====
SELECT DISTINCT COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(context_json, '$.lexeme')), 'null'),
                         NULLIF(JSON_UNQUOTE(JSON_EXTRACT(context_json, '$.word')), 'null')) AS lexeme
FROM assessment_question q
JOIN tag t ON t.id = q.knowledge_tag_id
WHERE q.jlpt_level = 'N5' AND q.skill = 'KANJI' AND q.is_active = 1
  AND (t.code = 'JLPT' OR JSON_CONTAINS(COALESCE(JSON_EXTRACT(q.context_json, '$.additional_tags'), JSON_ARRAY()), JSON_QUOTE('JLPT')))
ORDER BY lexeme;

-- ===== 2026-10-14-jlpt-source-book-ids.sql SHA256 2d62c617fe08eff879d5e7fd60eb28f12acb120e6284297dbbb5feed92179081 =====
SELECT id, title, jlpt_level, status, is_learning_path_source, source_code
FROM textbook
WHERE jlpt_level IN ('N5','N4','N3')
  AND (title LIKE 'Sekai%' OR source_code LIKE 'LP-OWNED-%' OR source_code LIKE 'LP-REVIEWED-%')
ORDER BY jlpt_level,id;

-- ===== 2026-10-14-jlpt-source-book-runtime-audit.sql SHA256 ee92a6581ec4008d69a7328b2c76cb2e7984cd831a9f2665f8d72f09ccb2688e =====
SELECT id, source_code, title, jlpt_level, status, is_learning_path_source
FROM textbook
WHERE jlpt_level IN ('N5','N4','N3')
ORDER BY jlpt_level, id;

SELECT t.id, t.title, t.jlpt_level, t.status, COUNT(DISTINCT l.id) AS lessons,
       COUNT(DISTINCT cv.vocabulary_id) AS distinct_vocabulary,
       COUNT(DISTINCT gp.id) AS grammar_points,
       COUNT(DISTINCT dld.content_item_id) AS dialogue_lines,
       COUNT(DISTINCT ca.id) AS item_audio
FROM textbook t
LEFT JOIN unit u ON u.textbook_id=t.id
LEFT JOIN lesson l ON l.unit_id=u.id
LEFT JOIN grammar_point gp ON gp.lesson_id=l.id
LEFT JOIN lesson_section ls ON ls.lesson_id=l.id
LEFT JOIN content_item ci ON ci.section_id=ls.id
LEFT JOIN content_vocabulary cv ON cv.content_item_id=ci.id
LEFT JOIN dialogue_line_detail dld ON dld.content_item_id=ci.id
LEFT JOIN content_audio ca ON ca.owner_type='CONTENT_ITEM' AND ca.owner_id=ci.id
WHERE t.jlpt_level IN ('N5','N4','N3') AND t.id < 100000
GROUP BY t.id,t.title,t.jlpt_level,t.status
ORDER BY t.jlpt_level,t.id;

-- ===== 2026-10-14-jlpt-source-readiness.sql SHA256 f5bb92c3fd592d41a84e9549b136caae969b6c8a11c4596012a864a7f56eabcb =====
SELECT t.id,t.title,
       COUNT(DISTINCT dld.content_item_id) AS dialogue_lines,
       COUNT(DISTINCT CASE WHEN ca.id IS NOT NULL THEN dld.content_item_id END) AS dialogue_with_audio,
       COUNT(DISTINCT CASE WHEN ci.content IS NULL OR TRIM(ci.content)='' THEN dld.content_item_id END) AS dialogue_blank_script,
       COUNT(DISTINCT CASE WHEN gp.pattern IS NULL OR TRIM(gp.pattern)='' OR gp.meaning IS NULL OR TRIM(gp.meaning)='' THEN gp.id END) AS grammar_blank,
       COUNT(DISTINCT CASE WHEN l.estimated_minutes IS NULL OR l.estimated_minutes=0 THEN l.id END) AS lessons_missing_duration
FROM textbook t
JOIN unit u ON u.textbook_id=t.id
JOIN lesson l ON l.unit_id=u.id
LEFT JOIN lesson_section ls ON ls.lesson_id=l.id
LEFT JOIN content_item ci ON ci.section_id=ls.id
LEFT JOIN dialogue_line_detail dld ON dld.content_item_id=ci.id
LEFT JOIN content_audio ca ON ca.owner_type='CONTENT_ITEM' AND ca.owner_id=dld.content_item_id AND ca.audio_url IS NOT NULL AND TRIM(ca.audio_url)<>''
LEFT JOIN grammar_point gp ON gp.lesson_id=l.id
WHERE t.id IN (2,4)
GROUP BY t.id,t.title;

-- ===== 2026-10-14-n4-compact-published-word-candidates.sql SHA256 9c355f12098338986fcdd603aa6e563d079a51c186377f2fe1d441b1ad8b3a49 =====
SELECT DISTINCT v.expression, v.reading
FROM vocabulary_entry v
JOIN content_vocabulary cv ON cv.vocabulary_id = v.id
JOIN content_item ci ON ci.id = cv.content_item_id
JOIN lesson_section ls ON ls.id = ci.section_id
JOIN lesson l ON l.id = ls.lesson_id
JOIN unit u ON u.id = l.unit_id
WHERE u.textbook_id = 2 AND v.jlpt_level = 'N4' AND v.dictionary_status = 'PUBLISHED'
  AND CHAR_LENGTH(v.expression) BETWEEN 2 AND 4
  AND v.expression REGEXP '[交特品注意動世質問説界発用晩別工場題文不赤険]'
ORDER BY v.expression
LIMIT 100;

-- ===== 2026-10-14-n4-draft-dictionary-crosscheck.sql SHA256 b06be6f6b96e2c9f114f1b36339506515f6240b11fa6008b8641e616766dd2b8 =====
SELECT expression, reading, jlpt_level, dictionary_status
FROM vocabulary_entry
WHERE expression IN ('交通','工場','文法','発音','駐車場','不良','出発','危険','工事','注意','説明','質問')
ORDER BY expression, reading;

-- ===== 2026-10-14-n4-source-approved-dictionary-candidates.sql SHA256 e5abf5cb6e61613ba5736d6cbe1cc33af9a0f2fe7ae5f5a352ad9b103d74a8ca =====
SELECT DISTINCT v.expression, v.reading, v.jlpt_level, v.dictionary_status
FROM vocabulary_entry v
JOIN content_vocabulary cv ON cv.vocabulary_id = v.id
JOIN content_item ci ON ci.id = cv.content_item_id
JOIN lesson_section ls ON ls.id = ci.section_id
JOIN lesson l ON l.id = ls.lesson_id
JOIN unit u ON u.id = l.unit_id
WHERE u.textbook_id = 2 AND v.jlpt_level = 'N4' AND v.dictionary_status = 'PUBLISHED'
  AND v.expression REGEXP '[一-龯]'
ORDER BY v.expression
LIMIT 60;

-- ===== 2026-10-14-n4-vocab-readiness.sql SHA256 5ac51c983557f7d27baf24644ae813498fdec0792397f7ab218354c26974e516 =====
SELECT v.dictionary_status, COUNT(DISTINCT v.id) AS words,
       COUNT(DISTINCT CASE WHEN cv.learning_meaning_vi IS NOT NULL THEN v.id END) AS with_context
FROM textbook t
JOIN unit u ON u.textbook_id=t.id
JOIN lesson l ON l.unit_id=u.id
JOIN lesson_section ls ON ls.lesson_id=l.id
JOIN content_item ci ON ci.section_id=ls.id
JOIN content_vocabulary cv ON cv.content_item_id=ci.id
JOIN vocabulary_entry v ON v.id=cv.vocabulary_id
WHERE t.id=2
GROUP BY v.dictionary_status;

-- ===== 2026-10-15-goal-source-coverage.sql SHA256 3b86ee3711afe38c536f9a02b4fa131f2bd1f326767dcf87d6af9520092b07f5 =====
SELECT t.id,t.title,t.jlpt_level,t.status,t.is_learning_path_source,
  COUNT(DISTINCT l.id) AS lessons,
  COUNT(DISTINCT cv.content_item_id,cv.vocabulary_id) AS vocabulary_rows,
  COUNT(DISTINCT gp.id) AS grammar_rows,
  COUNT(DISTINCT dld.content_item_id) AS dialogue_lines,
  COUNT(DISTINCT ca.id) AS audio_rows
FROM textbook t
LEFT JOIN unit u ON u.textbook_id=t.id
LEFT JOIN lesson l ON l.unit_id=u.id
LEFT JOIN lesson_section ls ON ls.lesson_id=l.id
LEFT JOIN content_item ci ON ci.section_id=ls.id
LEFT JOIN content_vocabulary cv ON cv.content_item_id=ci.id
LEFT JOIN grammar_point gp ON gp.lesson_id=l.id
LEFT JOIN dialogue_line_detail dld ON dld.content_item_id=ci.id
LEFT JOIN content_audio ca ON ca.owner_type='CONTENT_ITEM' AND ca.owner_id=ci.id
WHERE t.id IN (4,5,6,7,8,9)
GROUP BY t.id,t.title,t.jlpt_level,t.status,t.is_learning_path_source
ORDER BY t.id;
SELECT COUNT(*) AS goal_rows FROM learning_goal;
SELECT COUNT(*) AS scope_rows FROM lesson_learning_scope;

-- ===== 2026-10-15-jlpt-tag.sql SHA256 8360263120d69b53f4339f3d7e95396a3315b23909c703ee20ace98065de72ef =====
SELECT id,code,tag_type FROM tag WHERE code='JLPT';

-- ===== 2026-10-15-n4-readiness-after-owned.sql SHA256 b828de85261b679ec3e53cf0743ac0c86bb997ea33cf06f7e72f4c50f8c0f343 =====
SELECT q.jlpt_level,q.skill,
  COUNT(*) AS eligible_questions,
  COUNT(DISTINCT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(q.context_json,'$.lexeme')),
    JSON_UNQUOTE(JSON_EXTRACT(q.context_json,'$.word')))) AS distinct_lexemes
FROM assessment_question q
JOIN tag t ON t.id=q.knowledge_tag_id
WHERE q.is_active=1 AND q.skill='KANJI' AND q.jlpt_level='N4'
  AND (t.code='JLPT' OR JSON_CONTAINS(JSON_EXTRACT(q.context_json,'$.additional_tags'),JSON_QUOTE('JLPT')))
GROUP BY q.jlpt_level,q.skill;
SELECT COUNT(*) AS owned_rows,
  SUM(answer_text=ELT(correct_option,option_1,option_2,option_3,option_4)) AS valid_answers,
  SUM(BINARY option_1=BINARY option_2 OR BINARY option_1=BINARY option_3 OR BINARY option_1=BINARY option_4
    OR BINARY option_2=BINARY option_3 OR BINARY option_2=BINARY option_4 OR BINARY option_3=BINARY option_4) AS duplicate_options
FROM assessment_question WHERE source_code LIKE 'LP-OWNED-JLPT-N4-KANJI-READ-%';

-- ===== 2026-10-15-n4-smart-test-row-shape.sql SHA256 8be5f27e43cd92e4be35343c339d3310cf51875d58045bbd072e8cbe17b48de3 =====
SELECT q.id,q.source_code,q.knowledge_tag_id,q.skill,q.jlpt_level,q.difficulty,
       q.question_type,q.title,q.question_text,q.answer_text,q.option_1,q.option_2,
       q.option_3,q.option_4,q.correct_option,q.explanation,q.context_json,
       q.quiz_id,q.order_index,q.is_active
FROM assessment_question q
WHERE q.skill='KANJI' AND q.jlpt_level='N4'
ORDER BY q.id LIMIT 3;
SELECT id,code,tag_type FROM tag WHERE code='JLPT';
SELECT id,knowledge_tag_id,question_type,difficulty,question_text,answer_text,
       correct_option,context_json
FROM assessment_question
WHERE JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.seed_key'))='KANJI-DIVERSE-N4-003';

-- ===== 2026-10-15-n4-tag-inspect.sql SHA256 4bb7b09605d5b392f3ba50ee1afcecf9f1553894f979f5d9ae0bf092569e36f2 =====
SELECT id,code,tag_type FROM tag WHERE id IN (1001,1004) OR code LIKE '%KANJI%' LIMIT 20;
SELECT id,knowledge_tag_id,question_text FROM assessment_question WHERE skill='KANJI' AND jlpt_level='N4' LIMIT 4;

-- ===== sql/audits/learning-fingerprint.sql =====
-- [JLAS-W0108] V15 (2026-10-24): read-only fingerprint of the Learning data in one database, used before and after
-- every Learning write to jlas and on its rehearsal copy (the two outputs are compared line by line). Learner tables,
-- Learning-owned questions and learning-path books get a row count and an MD5 over their identifying columns.
-- Run: mysql --defaults-extra-file=<your cnf> -N -B <database> < sql/audits/learning-fingerprint.sql
-- 'smart_test_active_pool' is an id-range count of active questions, not the Smart Test selection itself (that one is
-- tag-based, see SmartTestQuestionSelector.matchesGoal).
SET SESSION group_concat_max_len = 1073741824;
select 'paths', count(*), md5(group_concat(concat(id,status,goal) order by id)) from user_learning_path
union all select 'packages', count(*), md5(group_concat(concat(id,status,order_index,coalesce(source_lesson_id,'')) order by id)) from learning_path_package
union all select 'path_items', count(*), md5(group_concat(concat(id,activity_state,coalesce(lesson_id,'')) order by id)) from user_learning_path_item
union all select 'members', count(*), md5(group_concat(concat(id,canonical_id,source_revision) order by id)) from learning_activity_member
union all select 'quiz_sessions', count(*), md5(group_concat(concat(id,status) order by id)) from learning_quiz_session
union all select 'assessment_answers', count(*), md5(group_concat(concat(id,question_id) order by id)) from assessment_answer
union all select 'lp_owned_questions', count(*), md5(group_concat(concat(id,question_text,option_1,option_2,coalesce(option_3,''),coalesce(option_4,''),correct_option,coalesce(explanation,''),coalesce(passage_html,''),is_active) order by id)) from assessment_question where source_code like 'LP-OWNED%'
union all select 'smart_test_active_pool', count(*), md5(group_concat(id order by id)) from assessment_question where is_active=1 and (id<1000001 or id>3999999)
union all select 'lp_textbooks', count(*), md5(group_concat(concat(id,status) order by id)) from textbook where is_learning_path_source=1
union all select 'users', count(*), md5(group_concat(id order by id)) from users
union all select 'vocab_entries', count(*), null from vocabulary_entry
union all select 'textbooks', count(*), null from textbook
union all select 'lessons', count(*), null from lesson
union all select 'questions', count(*), null from assessment_question
