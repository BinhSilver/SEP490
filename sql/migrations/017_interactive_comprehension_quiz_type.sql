-- [JLAS-W0193] RC Task 6: widen chk_quiz_session_type to allow READING_MICRO_CHECK/LISTENING_MICRO_CHECK.
-- RC Task 4 (InteractiveComprehensionServiceImpl / AssessmentComprehensionContentAdapter.microCheckType())
-- already issues LearningQuizSession rows with these quiz_type values via the shared InteractiveStudyEngine;
-- RC Task 2 (migration 016) only widened activity_type/member_type, not this constraint, so a real MySQL
-- database would reject a comprehension micro-check submit today. Same disclosed pattern as the
-- KANJI_MICRO_CHECK/GRAMMAR_MICRO_CHECK gap fixed in migration 012/013 (H2's ddl-auto=create-drop derives
-- schema from JPA entities, not from this raw SQL, so no existing H2-backed test caught either gap).

ALTER TABLE learning_quiz_session
    DROP CHECK chk_quiz_session_type,
    ADD CONSTRAINT chk_quiz_session_type CHECK
        (quiz_type IN ('GATE','LESSON','GRAMMAR','FLASHCARD','VOCAB_MICRO_CHECK','KANJI_MICRO_CHECK',
            'GRAMMAR_MICRO_CHECK','READING_MICRO_CHECK','LISTENING_MICRO_CHECK'));
