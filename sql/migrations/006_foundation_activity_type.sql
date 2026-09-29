-- [JLAS-W0108] Bang chu cai (Hiragana/Katakana) cho nguoi hoc "from-zero" (BELOW_N5): 1 activity_type
-- moi FOUNDATION (chi xuat hien trong package WARM_UP, khong phai 1 trong 4 phan bat buoc cua target
-- package - spec da duyet §2.2), 1 member_type moi FOUNDATION_CHARACTER (canonical = content_item.id
-- cua ky tu kana, tai su dung noi dung da so hoa san trong textbook "Bang chu cai tieng Nhat", khong
-- tao bang rieng), va 1 quiz_type moi FOUNDATION_MICRO_CHECK.

SET NAMES utf8mb4;

ALTER TABLE user_learning_path_item
    DROP CHECK chk_learning_path_item_activity_type,
    ADD CONSTRAINT chk_learning_path_item_activity_type CHECK
        (activity_type IS NULL OR activity_type IN
            ('VOCABULARY','KANJI','GRAMMAR','DIALOGUE','READING','LISTENING','FOUNDATION'));

ALTER TABLE learning_activity_member
    DROP CHECK chk_learning_activity_member_type,
    ADD CONSTRAINT chk_learning_activity_member_type CHECK
        (member_type IN
            ('VOCABULARY_ENTRY','KANJI','GRAMMAR_POINT','DIALOGUE_LINE','COMPREHENSION_QUESTION',
                'FOUNDATION_CHARACTER'));

ALTER TABLE learning_quiz_session
    DROP CHECK chk_quiz_session_type,
    ADD CONSTRAINT chk_quiz_session_type CHECK
        (quiz_type IN ('GATE','LESSON','GRAMMAR','FLASHCARD','VOCAB_MICRO_CHECK','KANJI_MICRO_CHECK',
            'GRAMMAR_MICRO_CHECK','READING_MICRO_CHECK','LISTENING_MICRO_CHECK','FOUNDATION_MICRO_CHECK'));
