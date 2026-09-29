-- [JLAS-W0193] Additive enum/check extension for Reading and Listening activities.
-- canonical_id for COMPREHENSION_QUESTION points to assessment_question.id; no second content table is created.

ALTER TABLE user_learning_path_item
    DROP CHECK chk_learning_path_item_activity_type,
    ADD CONSTRAINT chk_learning_path_item_activity_type CHECK
        (activity_type IS NULL OR activity_type IN
            ('VOCABULARY','KANJI','GRAMMAR','DIALOGUE','READING','LISTENING'));

ALTER TABLE learning_activity_member
    DROP CHECK chk_learning_activity_member_type,
    ADD CONSTRAINT chk_learning_activity_member_type CHECK
        (member_type IN
            ('VOCABULARY_ENTRY','KANJI','GRAMMAR_POINT','DIALOGUE_LINE','COMPREHENSION_QUESTION'));
