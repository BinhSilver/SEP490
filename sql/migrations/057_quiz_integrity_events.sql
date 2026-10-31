-- =====================================================================
-- 057: giam sat gian lan cho bai kiem tra co tinh gio (kiem tra cuoi giai doan Onyomi).
--
-- Moi dong la mot su kien trong luot lam bai: roi tab, roi cua so, thoat toan man hinh, mo bai o tab
-- khac, tai lai trang giua chung (RESUME, do server ghi), sao chep / dan / chuot phai / phim mo DevTools,
-- het gio va tu nop bai. Server dem cac su kien "roi khoi bai" de khoa luot lam khi vuot gioi han.
--
-- Chay lai an toan. QuizIntegritySchemaInitializer ap dung cung DDL khi ung dung khoi dong.
-- =====================================================================
CREATE TABLE IF NOT EXISTS assessment_attempt_integrity_event (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    attempt_id  BIGINT UNSIGNED NOT NULL,
    event_type  VARCHAR(30) NOT NULL,
    detail      VARCHAR(255) NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_attempt_integrity_attempt (attempt_id, occurred_at),
    CONSTRAINT fk_attempt_integrity_attempt FOREIGN KEY (attempt_id)
        REFERENCES assessment_attempt(id) ON DELETE CASCADE,
    CONSTRAINT chk_attempt_integrity_type CHECK (event_type IN
        ('TAB_HIDDEN','WINDOW_BLUR','FULLSCREEN_EXIT','MULTI_TAB','RESUME',
         'COPY','PASTE','CONTEXT_MENU','DEVTOOLS','TIME_UP','AUTO_SUBMIT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
