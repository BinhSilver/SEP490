-- [JLAS-W0108] Thoi diem lo trinh hoan thanh (chang cuoi dat final assessment); NULL = chua hoan
-- thanh. Dung lam ngay cap chung chi thanh tuu noi bo (khong suy tu updated_at, co the doi vi ly
-- do khac). Mo rong CHECK status de chap nhan gia tri COMPLETED moi.

ALTER TABLE user_learning_path
    ADD COLUMN completed_at DATETIME(6) NULL;

ALTER TABLE user_learning_path
    DROP CHECK chk_learning_path_status;

ALTER TABLE user_learning_path
    ADD CONSTRAINT chk_learning_path_status CHECK (status IN ('ACTIVE','SUPERSEDED','COMPLETED'));
