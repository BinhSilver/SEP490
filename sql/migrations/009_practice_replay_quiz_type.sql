-- [JLAS-W0108] Task 10: non-credit replay sessions (PRACTICE_REPLAY) for finished activities, and widen
-- quiz_type to 30: LISTENING_MICRO_CHECK (21 chars) and FOUNDATION_MICRO_CHECK (22) exceeded VARCHAR(20).

SET NAMES utf8mb4;

ALTER TABLE learning_quiz_session
    DROP CHECK chk_quiz_session_type,
    MODIFY quiz_type VARCHAR(30) NOT NULL
        COMMENT 'GATE/LESSON/GRAMMAR/FLASHCARD/*_MICRO_CHECK/PRACTICE_REPLAY',
    ADD CONSTRAINT chk_quiz_session_type CHECK
        (quiz_type IN ('GATE','LESSON','GRAMMAR','FLASHCARD','VOCAB_MICRO_CHECK','KANJI_MICRO_CHECK',
            'GRAMMAR_MICRO_CHECK','READING_MICRO_CHECK','LISTENING_MICRO_CHECK','FOUNDATION_MICRO_CHECK',
            'PRACTICE_REPLAY'));
