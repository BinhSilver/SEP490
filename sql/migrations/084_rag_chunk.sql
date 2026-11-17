-- [TUTOR] Chỉ mục tìm kiếm của gia sư AI, thay Qdrant: mỗi dòng là một đoạn giáo trình đã embed (bge-m3 qua OpenRouter).
-- MySQL vẫn là nguồn gốc của nội dung; bảng này dựng lại được bất cứ lúc nào bằng POST /api/admin/system/ai/rag/reindex.
-- App nạp cả bộ chỉ mục vào bộ nhớ (khoảng 4.000 đoạn × 1.024 số ≈ 17 MB) và tự tính cosine, nên không cần kho vector riêng.
--   id          : UUID cố định sinh từ khóa của đoạn (RagChunk.pointId), chạy lại không tạo bản trùng
--   collection  : bộ chỉ mục theo model embedding (ví dụ jlas_content_baai_bge_m3), đổi model thì sang bộ mới
--   vector      : float32 little-endian, dims số
--   payload     : JSON (chữ của đoạn, nguồn trích dẫn) dạng văn bản
-- Không có entity. Chạy lại an toàn.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS rag_chunk (
    id           CHAR(36)          NOT NULL,
    collection   VARCHAR(80)       NOT NULL,
    lesson_id    BIGINT            NOT NULL DEFAULT 0,
    source_type  VARCHAR(20)       NOT NULL,
    pattern_key  VARCHAR(255)      NOT NULL DEFAULT '',
    content_hash CHAR(64)          NOT NULL,
    dims         SMALLINT UNSIGNED NOT NULL,
    vector       MEDIUMBLOB        NOT NULL,
    payload      MEDIUMTEXT        NOT NULL,
    updated_at   DATETIME(6)       NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_rag_chunk_collection_lesson (collection, lesson_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
