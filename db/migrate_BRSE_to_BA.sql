-- [JLAS-W0090] Rename Smart Test goal + situation tag code: BRSE -> BA (Bridge Software Engineer).
-- Run ONCE against an existing database that was seeded with the old 'BRSE' code.
-- Fresh installs: run the updated questionbank.sql instead (it already uses 'BA').

-- MySQL Workbench "safe update mode" (Error 1175) blocks UPDATEs whose WHERE
-- has no key column. Disable it for this session; restored at the end.
SET SQL_SAFE_UPDATES = 0;

UPDATE tag SET code = 'BA' WHERE code = 'BRSE';

UPDATE user_learning_survey SET goal = 'BA' WHERE goal = 'BRSE';

UPDATE smart_test_session SET goal = 'BA' WHERE goal = 'BRSE';

-- Denormalised metadata in assessment_question.context_json (additional_tags).
UPDATE assessment_question
SET context_json = REPLACE(CAST(context_json AS CHAR), '"BRSE"', '"BA"')
WHERE CAST(context_json AS CHAR) LIKE '%"BRSE"%';

SET SQL_SAFE_UPDATES = 1;
