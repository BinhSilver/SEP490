-- [JLAS-W0108] P2 — activity progress độc lập source lesson.
-- Tách study (nội dung) khỏi assessment (đánh giá) trên user_learning_path_item;
-- không còn suy tiến độ item từ lesson_progress (nhiều item dùng chung source lesson bị complete cùng nhau).

ALTER TABLE user_learning_path_item
    ADD COLUMN study_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN assessment_status VARCHAR(20) NOT NULL DEFAULT 'NOT_READY';

ALTER TABLE user_learning_path_item
    ADD CONSTRAINT chk_learning_path_item_study_status
        CHECK (study_status IN ('NOT_STARTED','IN_PROGRESS','STUDIED')),
    ADD CONSTRAINT chk_learning_path_item_assessment_status
        CHECK (assessment_status IN ('NOT_READY','READY','ISSUED','SUBMITTED','PASSED','NEEDS_REVIEW'));

-- Backfill lịch sử cũ: item từng được hoàn thành qua lesson_progress (mô hình cũ) -> STUDIED.
-- Không xóa lịch sử cũ; chỉ chuyển tín hiệu hoàn thành sang cột mới một lần.
UPDATE user_learning_path_item i
JOIN lesson_progress p ON p.lesson_id = i.lesson_id AND p.status = 'COMPLETED'
SET i.study_status = 'STUDIED';
