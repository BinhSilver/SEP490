-- [JLAS2-XX] Thumbnail giao trinh: them 3 cot vao bang textbook (khop jlas_schema.sql).
--   thumbnail_url   VARCHAR(500) NULL  duong dan anh do CONTENT_CREATOR tai len (/uploads/textbook-thumbnails/<uuid>.<ext>)
--   thumbnail_kanji VARCHAR(4)   NULL  chu Han hien mo tren thumbnail tu sinh (NULL = mac dinh 日)
--   theme_color     VARCHAR(7)   NULL  mau nen thumbnail tu sinh dang #RRGGBB (NULL = chon theo hash ten giao trinh)
-- Cach lam (giong 019/020): doc information_schema, cot nao chua co moi ADD; bang khong ton tai thi bo qua.
-- Chi them cot NULL nen du lieu cu khong bi anh huong. Chay lai duoc. MySQL 8.0+.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------- thumbnail_url
SET @j021_t = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'textbook');
SET @j021_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'textbook' AND COLUMN_NAME = 'thumbnail_url');
SET @j021_sql = IF(@j021_t = 1 AND @j021_c = 0,
                   'ALTER TABLE textbook ADD COLUMN thumbnail_url VARCHAR(500) NULL AFTER jlpt_level', 'DO 0');
PREPARE j021_stmt FROM @j021_sql; EXECUTE j021_stmt; DEALLOCATE PREPARE j021_stmt;

-- ---------------------------------------------------------------- thumbnail_kanji
SET @j021_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'textbook' AND COLUMN_NAME = 'thumbnail_kanji');
SET @j021_sql = IF(@j021_t = 1 AND @j021_c = 0,
                   'ALTER TABLE textbook ADD COLUMN thumbnail_kanji VARCHAR(4) NULL AFTER thumbnail_url', 'DO 0');
PREPARE j021_stmt FROM @j021_sql; EXECUTE j021_stmt; DEALLOCATE PREPARE j021_stmt;

-- ---------------------------------------------------------------- theme_color
SET @j021_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'textbook' AND COLUMN_NAME = 'theme_color');
SET @j021_sql = IF(@j021_t = 1 AND @j021_c = 0,
                   'ALTER TABLE textbook ADD COLUMN theme_color VARCHAR(7) NULL AFTER thumbnail_kanji', 'DO 0');
PREPARE j021_stmt FROM @j021_sql; EXECUTE j021_stmt; DEALLOCATE PREPARE j021_stmt;
