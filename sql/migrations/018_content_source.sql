-- [JLAS2-20] Bang content_source: nguon goc noi dung (da hinh, giong content_audio).
-- Tiep sau ban hop nhat 004-017. Chi tao bang moi, khong doi bang nao khac.
-- Chay lai duoc (IF NOT EXISTS). Ban day du cung nam trong jlas_schema.sql (sau content_audio).
--
-- Moi dong ghi mot muc noi dung (giao trinh/unit/bai/tu vung/mau ngu phap/content_item/kanji/
-- cau hoi) den tu nguon nao (SEKAI / VNJPCLUB / AI_GENERATED), ma on dinh ben nguon, URL goc,
-- tieu de goc va trang thai duyet (NGUON / CHUA_DUYET / DA_DUYET). entity_id tro toi bang theo
-- entity_type nen khong dat FK; seed/importer giu rang buoc.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS content_source (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    entity_type    VARCHAR(30)   NOT NULL,
    entity_id      BIGINT UNSIGNED NOT NULL,
    source_code    VARCHAR(30)   NOT NULL,
    stable_code    VARCHAR(255)  NOT NULL,
    source_url     VARCHAR(1000) NULL,
    original_title VARCHAR(500)  NULL,
    review_status  VARCHAR(20)   NOT NULL DEFAULT 'CHUA_DUYET',
    note           VARCHAR(1000) NULL,
    created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_source_code (source_code, stable_code),
    KEY idx_content_source_entity (entity_type, entity_id),
    CONSTRAINT chk_content_source_entity_type
        CHECK (entity_type IN ('TEXTBOOK','UNIT','LESSON','VOCABULARY','GRAMMAR_POINT',
                               'CONTENT_ITEM','KANJI','QUESTION')),
    CONSTRAINT chk_content_source_source_code
        CHECK (source_code IN ('SEKAI','VNJPCLUB','AI_GENERATED')),
    CONSTRAINT chk_content_source_review_status
        CHECK (review_status IN ('NGUON','CHUA_DUYET','DA_DUYET'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
