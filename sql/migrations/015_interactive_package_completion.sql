-- [JLAS-W0193] Task 8 — Package completion: persists the computed package score once a target package
-- completes (Vocabulary/Kanji/Grammar micro-check PASSED + Dialogue COMPLETED). Additive only; NULL until
-- the package actually completes. Score is computed server-side from real learning_quiz_session data
-- (round((vocabularyScore + kanjiScore + grammarScore) / count-of-skills-with-a-valid-score)) — Dialogue
-- never contributes to the numerator or denominator (it has no score field anywhere, by design).

ALTER TABLE learning_path_package
    ADD COLUMN score_percent INT UNSIGNED NULL AFTER completed_at,
    ADD CONSTRAINT chk_learning_package_score CHECK (score_percent IS NULL OR score_percent BETWEEN 0 AND 100);
