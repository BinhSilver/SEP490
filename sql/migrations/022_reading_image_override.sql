-- [JLAS2-17] Anh doan doc: bang reading_image_override (khop jlas_schema.sql).
--   Khoa theo ma on dinh cua bai doc (content_source.stable_code cua content_item READING), khong theo id,
--   nen chay lai seed (seed_data_vnjpclub.sql / seed_data_minna.sql) KHONG lam mat anh da tai len.
--   image_url       /uploads/reading-images/<uuid>.<ext> (NULL + hide_seed_image = 1: an anh goc cua seed)
--   updated_by      users.id nguoi sua gan nhat; updated_at thoi diem sua.
-- App van chay binh thuong khi CHUA chay migration nay: chuc nang tai anh tu an, trang hoc dung anh seed.
-- Chay lai duoc (CREATE TABLE IF NOT EXISTS). MySQL 8.0+.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS reading_image_override (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    stable_code     VARCHAR(255)  NOT NULL,
    image_url       VARCHAR(500)  NULL,
    hide_seed_image TINYINT(1)    NOT NULL DEFAULT 0,
    updated_by      BIGINT UNSIGNED NULL,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reading_image_override_code (stable_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
