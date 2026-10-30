-- [JLAS-W0108] Stable nullable import keys keep curriculum seed reruns idempotent while
-- preserving module-owned and legacy records that do not participate in Learning Path imports.
ALTER TABLE textbook
    ADD COLUMN source_code VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_textbook_source_code (source_code);

ALTER TABLE unit
    ADD COLUMN source_code VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_unit_source_code (textbook_id, source_code);

ALTER TABLE lesson
    ADD COLUMN source_code VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_lesson_source_code (unit_id, source_code);

ALTER TABLE assessment_question
    ADD COLUMN source_code VARCHAR(100) NULL,
    ADD UNIQUE KEY uk_assessment_question_source_code (source_code);
