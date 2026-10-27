-- [JLAS-W0108] FOUNDATION is a first-class stage for learners starting below N5.
ALTER TABLE user_learning_stage
    DROP CHECK chk_learning_stage_level,
    ADD CONSTRAINT chk_learning_stage_level
        CHECK (level IN ('FOUNDATION','N5','N4','N3','N2','N1'));
