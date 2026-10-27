-- [JLAS2-17] Anh trang tu vung / ngu phap / kanji: bang lesson_page_image (khop jlas_schema.sql). Chi THEM bang moi,
-- khong sua bang cu.
--   Nguon: ban xuat vnjpclub lesson_nguon.anh_trang (anh cua trang nguon tu vung / ngu phap / kanji — Somatome,
--   Shinkanzen, Speed Master, Mimikara ...), ghi boi tools/seed_vnjpclub.py theo dai ID cua seed (xoa / nap lai theo dai).
--   tab          VOCAB / GRAMMAR / KANJI — trang hoc hien anh o dau tab tuong ung (bam phong to).
--   image_url    duong dan tuong doi trong kho content.audio.path (vd vnjpclub/N3/somatome-n3-han-tu/x.png).
--   order_index  thu tu trong (bai, tab), theo trang nguon.
--   stable_code  ma on dinh: ma trang nguon + so thu tu anh (vd SOMATOME-N3-L001-SRC02-IMG01).
-- Chay TRUOC sql/seeds/seed_data_vnjpclub.sql / seed_data_minna.sql (hai seed ghi / xoa bang nay).
-- App van chay binh thuong khi CHUA chay migration nay: API anh trang tra available = false, trang hoc khong hien anh.
-- Chay lai duoc (CREATE TABLE IF NOT EXISTS). MySQL 8.0.16+.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lesson_page_image (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lesson_id    BIGINT UNSIGNED NOT NULL,
    tab          VARCHAR(20)   NOT NULL,
    image_url    VARCHAR(500)  NOT NULL,
    order_index  INT UNSIGNED  NOT NULL DEFAULT 0,
    stable_code  VARCHAR(255)  NOT NULL,
    created_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_lesson_page_image_code (stable_code),
    KEY idx_lesson_page_image_lesson (lesson_id, tab, order_index),
    CONSTRAINT fk_lesson_page_image_lesson FOREIGN KEY (lesson_id) REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT chk_lesson_page_image_tab CHECK (tab IN ('VOCAB','GRAMMAR','KANJI'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
