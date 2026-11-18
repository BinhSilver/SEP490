-- [JLAS-SCHEMA-DRIFT] Cột mà entity đã dùng nhưng chưa có migration, nên DB dựng từ SQL bị Hibernate validate từ chối:
--   content_vocabulary.learning_meaning_vi / learning_example_ja / learning_example_vi (ContentVocabulary),
--   learning_activity_member.content_snapshot_json (LearningActivityMember),
--   learning_quiz_session.draft_answers_json (LearningQuizSession).
-- Thêm cột rỗng (TEXT NULL), không đổi dữ liệu có sẵn. jlpt_exam.passing_score phải là số nguyên (JlptExam.passingScore
-- là Integer, jlas_schema.sql khai INT UNSIGNED); DB nào đang để DECIMAL thì đổi về INT UNSIGNED, giá trị lẻ được làm tròn.
-- Chạy lại an toàn.
SET NAMES utf8mb4;

SET @drift_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'content_vocabulary' AND column_name = 'learning_meaning_vi');
SET @drift_ddl = IF(@drift_exists = 0, 'ALTER TABLE content_vocabulary ADD COLUMN learning_meaning_vi TEXT NULL', 'SELECT 1');
PREPARE drift_stmt FROM @drift_ddl;
EXECUTE drift_stmt;
DEALLOCATE PREPARE drift_stmt;

SET @drift_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'content_vocabulary' AND column_name = 'learning_example_ja');
SET @drift_ddl = IF(@drift_exists = 0, 'ALTER TABLE content_vocabulary ADD COLUMN learning_example_ja TEXT NULL', 'SELECT 1');
PREPARE drift_stmt FROM @drift_ddl;
EXECUTE drift_stmt;
DEALLOCATE PREPARE drift_stmt;

SET @drift_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'content_vocabulary' AND column_name = 'learning_example_vi');
SET @drift_ddl = IF(@drift_exists = 0, 'ALTER TABLE content_vocabulary ADD COLUMN learning_example_vi TEXT NULL', 'SELECT 1');
PREPARE drift_stmt FROM @drift_ddl;
EXECUTE drift_stmt;
DEALLOCATE PREPARE drift_stmt;

SET @drift_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'learning_activity_member' AND column_name = 'content_snapshot_json');
SET @drift_ddl = IF(@drift_exists = 0, 'ALTER TABLE learning_activity_member ADD COLUMN content_snapshot_json TEXT NULL', 'SELECT 1');
PREPARE drift_stmt FROM @drift_ddl;
EXECUTE drift_stmt;
DEALLOCATE PREPARE drift_stmt;

SET @drift_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'learning_quiz_session' AND column_name = 'draft_answers_json');
SET @drift_ddl = IF(@drift_exists = 0, 'ALTER TABLE learning_quiz_session ADD COLUMN draft_answers_json TEXT NULL', 'SELECT 1');
PREPARE drift_stmt FROM @drift_ddl;
EXECUTE drift_stmt;
DEALLOCATE PREPARE drift_stmt;

SET @drift_passing_type = (SELECT data_type FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'jlpt_exam' AND column_name = 'passing_score');
SET @drift_ddl = IF(@drift_passing_type IS NOT NULL AND @drift_passing_type <> 'int',
    'ALTER TABLE jlpt_exam MODIFY COLUMN passing_score INT UNSIGNED NOT NULL DEFAULT 95', 'SELECT 1');
PREPARE drift_stmt FROM @drift_ddl;
EXECUTE drift_stmt;
DEALLOCATE PREPARE drift_stmt;
