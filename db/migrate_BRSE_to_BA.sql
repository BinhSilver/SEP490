-- [JLAS-W0090] Rename Smart Test goal + situation tag code: BRSE -> BA (Bridge Software Engineer).
-- Run ONCE against an existing database that was seeded with the old 'BRSE' code.
-- Fresh installs: run the updated questionbank.sql instead (it already uses 'BA').

UPDATE tag SET code = 'BA' WHERE code = 'BRSE';

UPDATE user_learning_survey SET goal = 'BA' WHERE goal = 'BRSE';

UPDATE smart_test_session SET goal = 'BA' WHERE goal = 'BRSE';

-- Denormalised metadata in assessment_question.context_json (additional_tags).
UPDATE assessment_question
SET context_json = REPLACE(CAST(context_json AS CHAR), '"BRSE"', '"BA"')
WHERE CAST(context_json AS CHAR) LIKE '%"BRSE"%';
