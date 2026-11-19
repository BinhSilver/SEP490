-- [JLAS2-37] Community Hub: bai viet nguoi hoc tu dang (chi text, 1-5000 ky tu, tag cap do tuy chon).
-- community_post la "bang goc" cua content_type POST; trang thai cong dong, bo dem, binh luan, bao cao va tu an
-- dung chung community_content / community_comment / community_report (069), khong co he thong thu hai.
-- jlpt_level NULL = Chung. Xoa mem bang deleted_at. Chay lai an toan.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS community_post (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    author_id   BIGINT UNSIGNED NOT NULL,
    body        VARCHAR(5000) NOT NULL,
    jlpt_level  VARCHAR(4) NULL,
    edited_at   DATETIME(6) NULL,
    deleted_at  DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_community_post_feed (deleted_at, created_at),
    KEY idx_community_post_author (author_id),
    CONSTRAINT fk_community_post_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_community_post_level CHECK (jlpt_level IS NULL OR jlpt_level IN ('N5','N4','N3','N2','N1'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
