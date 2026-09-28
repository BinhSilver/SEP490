-- [JLAS-W0108] P2c — bài nguồn (lesson_id) của learning path item trở thành nullable để activity
-- có thể trỏ tài nguyên khác (grammar_point / vocabulary_entry / reading-listening content_item)
-- mà không cần một lesson_id giả. Kèm CHECK: mỗi activity phải có ít nhất một tham chiếu hợp lệ.
-- Không xóa lịch sử cũ; chỉ nới lỏng ràng buộc cột.

-- NOTE: bỏ CHECK "ít nhất một tham chiếu hợp lệ" (chk_learning_path_item_reference) vì MySQL
-- 8.0.16+ cấm cột nằm trong CHECK đồng thời nằm trong FK có referential action
-- (grammar_point_id / vocabulary_entry_id) — lỗi 3823. Ràng buộc này được đảm bảo ở tầng ứng dụng
-- (LearningServiceImpl chọn đúng anchor), không cần DB-level.

ALTER TABLE user_learning_path_item
    MODIFY lesson_id BIGINT UNSIGNED NULL;
