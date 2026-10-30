-- [JLAS2-20] Dua DB dang chay ve khop jlas_schema.sql hien tai o 6 cho con lech (do bang cu tao truoc khi schema
-- doi; cac thay doi [JLAS-W0108] ... chi sua thang jlas_schema.sql, khong co migration):
--   1) tag.chk_tag_type                              them 'TOPIC'   (seed_data_jlas.sql can: 20 tag TOPIC)
--   2) user_learning_path.chk_learning_path_status   them 'COMPLETED'
--   3) user_learning_path_item.chk_learning_path_item_kind  cho phep NULL
--   4) user_knowledge_weakness.chk_weakness_skill    them 'KANJI'
--   5) game_match.chk_game_match_type                them 'NEKO_CATCH'
--   6) user_credit_transaction.uk_credit_transaction_reference UNIQUE (user_id, reason, reference_type, reference_id)
-- Cach lam (giong 019): doc information_schema; CHECK chua dung thi DROP roi ADD dung dinh nghia trong schema, CHECK
-- da dung thi bo qua; CHECK moi rong hon CHECK cu nen du lieu cu khong vi pham. Index: chi them khi chua co VA khong
-- co dong trung (bo qua dong co cot NULL vi UNIQUE cho phep nhieu NULL); neu trung thi BO QUA rieng index nay va in
-- danh sach dong trung, KHONG xoa gi. Bang khong ton tai thi bo qua. Chay lai duoc. MySQL 8.0.16+.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------- 1) chk_tag_type
SET @j020_t = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tag');
SET @j020_c = (SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_tag_type');
SET @j020_ok = (@j020_c IS NOT NULL AND @j020_c REGEXP 'TOPIC');
SET @j020_sql = IF(@j020_t = 1 AND @j020_c IS NOT NULL AND NOT @j020_ok,
                   'ALTER TABLE tag DROP CHECK chk_tag_type', 'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;
SET @j020_sql = IF(@j020_t = 1 AND NOT @j020_ok,
                   'ALTER TABLE tag ADD CONSTRAINT chk_tag_type CHECK (tag_type IN (''VIDEO'',''KNOWLEDGE'',''SITUATION'',''SKILL'',''TOPIC''))',
                   'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;

-- ---------------------------------------------------------------- 2) chk_learning_path_status
SET @j020_t = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path');
SET @j020_c = (SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_learning_path_status');
SET @j020_ok = (@j020_c IS NOT NULL AND @j020_c REGEXP 'COMPLETED');
SET @j020_sql = IF(@j020_t = 1 AND @j020_c IS NOT NULL AND NOT @j020_ok,
                   'ALTER TABLE user_learning_path DROP CHECK chk_learning_path_status', 'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;
SET @j020_sql = IF(@j020_t = 1 AND NOT @j020_ok,
                   'ALTER TABLE user_learning_path ADD CONSTRAINT chk_learning_path_status CHECK (status IN (''ACTIVE'',''SUPERSEDED'',''COMPLETED''))',
                   'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;

-- ---------------------------------------------------------------- 3) chk_learning_path_item_kind
SET @j020_t = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item');
SET @j020_c = (SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_learning_path_item_kind');
SET @j020_ok = (@j020_c IS NOT NULL AND LOWER(@j020_c) REGEXP 'is null');
SET @j020_sql = IF(@j020_t = 1 AND @j020_c IS NOT NULL AND NOT @j020_ok,
                   'ALTER TABLE user_learning_path_item DROP CHECK chk_learning_path_item_kind', 'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;
SET @j020_sql = IF(@j020_t = 1 AND NOT @j020_ok,
                   'ALTER TABLE user_learning_path_item ADD CONSTRAINT chk_learning_path_item_kind CHECK (kind IS NULL OR kind IN (''CURRICULUM'',''REVIEW''))',
                   'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;

-- ---------------------------------------------------------------- 4) chk_weakness_skill
SET @j020_t = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_knowledge_weakness');
SET @j020_c = (SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_weakness_skill');
SET @j020_ok = (@j020_c IS NOT NULL AND @j020_c REGEXP 'KANJI');
SET @j020_sql = IF(@j020_t = 1 AND @j020_c IS NOT NULL AND NOT @j020_ok,
                   'ALTER TABLE user_knowledge_weakness DROP CHECK chk_weakness_skill', 'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;
SET @j020_sql = IF(@j020_t = 1 AND NOT @j020_ok,
                   'ALTER TABLE user_knowledge_weakness ADD CONSTRAINT chk_weakness_skill CHECK (skill IS NULL OR skill IN (''VOCABULARY'',''GRAMMAR'',''READING'',''LISTENING'',''SPEAKING'',''ONYOMI'',''KUNYOMI'',''KANJI''))',
                   'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;

-- ---------------------------------------------------------------- 5) chk_game_match_type
SET @j020_t = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'game_match');
SET @j020_c = (SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_game_match_type');
SET @j020_ok = (@j020_c IS NOT NULL AND @j020_c REGEXP 'NEKO_CATCH');
SET @j020_sql = IF(@j020_t = 1 AND @j020_c IS NOT NULL AND NOT @j020_ok,
                   'ALTER TABLE game_match DROP CHECK chk_game_match_type', 'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;
SET @j020_sql = IF(@j020_t = 1 AND NOT @j020_ok,
                   'ALTER TABLE game_match ADD CONSTRAINT chk_game_match_type CHECK (game_type IN (''CANNON_BATTLE'',''MEMORY_MATCH'',''AIR_DEFENSE'',''NEKO_CATCH''))',
                   'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;

-- ---------------------------------------------------------------- 6) uk_credit_transaction_reference
SET @j020_t = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_credit_transaction');
SET @j020_has = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'user_credit_transaction' AND INDEX_NAME = 'uk_credit_transaction_reference');
-- So nhom khoa bi trung (chi xet dong du 4 cot khac NULL). Bang chua co thi 0.
SET @j020_sql = IF(@j020_t = 1,
                   'SELECT COUNT(*) INTO @j020_dup FROM (SELECT 1 FROM user_credit_transaction WHERE user_id IS NOT NULL AND reason IS NOT NULL AND reference_type IS NOT NULL AND reference_id IS NOT NULL GROUP BY user_id, reason, reference_type, reference_id HAVING COUNT(*) > 1) d',
                   'SELECT 0 INTO @j020_dup');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;
SET @j020_sql = IF(@j020_t = 1 AND @j020_has = 0 AND @j020_dup = 0,
                   'ALTER TABLE user_credit_transaction ADD UNIQUE KEY uk_credit_transaction_reference (user_id, reason, reference_type, reference_id)',
                   'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;
-- Co trung: liet ke dong trung (KHONG xoa) de xu ly tay roi chay lai file nay.
SET @j020_sql = IF(@j020_t = 1 AND @j020_has = 0 AND @j020_dup > 0,
                   'SELECT ''BO QUA uk_credit_transaction_reference - dong trung:'' AS canh_bao, c.* FROM user_credit_transaction c JOIN (SELECT user_id, reason, reference_type, reference_id FROM user_credit_transaction WHERE user_id IS NOT NULL AND reason IS NOT NULL AND reference_type IS NOT NULL AND reference_id IS NOT NULL GROUP BY user_id, reason, reference_type, reference_id HAVING COUNT(*) > 1) d ON d.user_id = c.user_id AND d.reason = c.reason AND d.reference_type = c.reference_type AND d.reference_id = c.reference_id ORDER BY c.user_id, c.reason, c.reference_type, c.reference_id, c.id',
                   'DO 0');
PREPARE j020_stmt FROM @j020_sql; EXECUTE j020_stmt; DEALLOCATE PREPARE j020_stmt;

-- ---------------------------------------------------------------- ket qua
SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE()
  AND CONSTRAINT_NAME IN ('chk_tag_type', 'chk_learning_path_status', 'chk_learning_path_item_kind',
                          'chk_weakness_skill', 'chk_game_match_type')
ORDER BY CONSTRAINT_NAME;
SELECT @j020_dup AS so_nhom_trung_credit_reference,
       (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'user_credit_transaction' AND INDEX_NAME = 'uk_credit_transaction_reference')
           AS co_uk_credit_transaction_reference;
