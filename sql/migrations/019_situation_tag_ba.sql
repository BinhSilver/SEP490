-- [JLAS2-20] Dua lesson.situation_tag cua DB dang chay ve dung jlas_schema.sql hien tai ([JLAS-W0108] WORK -> BA).
-- DB tao tu schema cu co CHECK chk_lesson_situation_tag IN ('TRAVEL','WORK','FOUNDATION') va cac bai IT mang 'WORK';
-- seed chuan sql/seeds/seed_data_jlas.sql dung 'BA' nen nap len DB do se loi ERROR 3819 neu chua chay file nay.
--
-- Thu tu: bo CHECK cu (neu chua dung) -> doi 'WORK' thanh 'BA' -> them CHECK moi (TRAVEL, BA, FOUNDATION).
-- Chay lai duoc: DB da dung (CHECK da co 'BA', khong co 'WORK') thi khong ALTER gi, UPDATE khong khop dong nao.
-- MySQL 8.0.16+ (CHECK constraint). Chi doi bang lesson.

SET NAMES utf8mb4;
SET @jlas019_prev_safe_updates = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

SET @jlas019_clause = (SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
                       WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_lesson_situation_tag');
-- CHECK dung = co gia tri BA va khong con WORK.
SET @jlas019_ok = (@jlas019_clause IS NOT NULL
                   AND @jlas019_clause REGEXP '[^A-Za-z]BA[^A-Za-z]'
                   AND NOT @jlas019_clause REGEXP 'WORK');

-- 1) Bo CHECK cu (chi khi co CHECK va CHECK chua dung)
SET @jlas019_sql = IF(@jlas019_clause IS NOT NULL AND NOT @jlas019_ok,
                      'ALTER TABLE lesson DROP CHECK chk_lesson_situation_tag', 'DO 0');
PREPARE jlas019_stmt FROM @jlas019_sql;
EXECUTE jlas019_stmt;
DEALLOCATE PREPARE jlas019_stmt;

-- 2) Doi WORK -> BA
UPDATE lesson SET situation_tag = 'BA' WHERE situation_tag = 'WORK';

-- 3) Them CHECK moi giong jlas_schema.sql (chi khi chua dung)
SET @jlas019_sql = IF(@jlas019_ok,
                      'DO 0',
                      'ALTER TABLE lesson ADD CONSTRAINT chk_lesson_situation_tag CHECK (situation_tag IS NULL OR situation_tag IN (''TRAVEL'',''BA'',''FOUNDATION''))');
PREPARE jlas019_stmt FROM @jlas019_sql;
EXECUTE jlas019_stmt;
DEALLOCATE PREPARE jlas019_stmt;

SET SQL_SAFE_UPDATES = @jlas019_prev_safe_updates;

SELECT CHECK_CLAUSE AS chk_lesson_situation_tag FROM information_schema.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_lesson_situation_tag';
SELECT situation_tag, COUNT(*) AS so_bai FROM lesson GROUP BY situation_tag;
