-- [JLAS-PRO-CP] Kế hoạch gói Pro và CP, phần còn lại. Chạy lại an toàn;
-- ProCpPlanSchemaInitializer cũng tự tạo các bảng này khi khởi động.
--   1. shadowing_minutes_weekly: số phút video Shadowing đã tạo trong tuần (thứ Hai là ngày đầu tuần). Free 20, Pro 120
--      phút mỗi tuần; phần vượt 50 CP/phút khi người dùng xác nhận trên popup. Chỉ phút có dùng AI mới tính: video bật
--      furigana/dịch AI tính đúng số phút, video phải chuyển giọng nói thành chữ (file tải lên, YouTube không phụ đề)
--      tính gấp 3; video YouTube chỉ dùng phụ đề có sẵn không tính.
--   2. shadowing_create_charge: phút và CP đã trừ cho từng video, để hoàn lại khi người dùng xoá một video chưa xử lý
--      xong. Bấm "Thử lại" không trừ thêm.
--   3. cp_auto_pay: "Không hỏi lại hôm nay" theo tài khoản, để bật ở máy này thì máy khác cũng biết. Một dòng là
--      một chức năng đang tự trả CP trong ngày; tắt thì xoá dòng. Số CP đã tự dùng được tính từ sổ CP
--      (user_credit_transaction) kể từ enabled_at, nên không cần lưu ở đây.

CREATE TABLE IF NOT EXISTS shadowing_minutes_weekly (
    user_id      BIGINT UNSIGNED NOT NULL,
    week_start   DATE NOT NULL,
    minutes_used INT UNSIGNED NOT NULL DEFAULT 0,  -- phút trong hạn mức tuần (phần trả bằng CP không tính)
    PRIMARY KEY (user_id, week_start),
    CONSTRAINT fk_shadowing_minutes_weekly_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shadowing_create_charge (
    video_id      BIGINT UNSIGNED NOT NULL,
    user_id       BIGINT UNSIGNED NOT NULL,
    week_start    DATE NOT NULL,
    quota_minutes INT UNSIGNED NOT NULL,             -- phút lấy từ hạn mức tuần
    credit_paid   INT UNSIGNED NOT NULL,             -- CP đã trả cho phần vượt
    refunded_at   DATETIME(6) NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (video_id),
    KEY idx_shadowing_create_charge_user (user_id, created_at),
    CONSTRAINT fk_shadowing_create_charge_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cp_auto_pay (
    user_id    BIGINT UNSIGNED NOT NULL,
    feature    VARCHAR(30) NOT NULL,             -- aiCall | pronunciation | quickLookup | shadowingCreate
    pay_date   DATE NOT NULL,
    unit_cost  INT UNSIGNED NOT NULL,            -- giá một lần dùng lúc bật
    enabled_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, feature, pay_date),
    CONSTRAINT fk_cp_auto_pay_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
