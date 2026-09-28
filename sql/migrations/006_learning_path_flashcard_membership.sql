-- [JLAS-W0108] P2d — membership version cho bộ flashcard warm-up.
-- Cột fingerprint membership (identity expression+reading đã khử trùng, sắp thứ tự) để phát hiện
-- membership thay đổi khi replan thêm mục yếu mới. Fingerprint khác cột này => WarmUpFlashcardService
-- regenerate bộ (mục mới vào trạng thái chưa nhớ, giữ tiến độ các thẻ cũ) và activity chưa hoàn thành.
-- Không xóa lịch sử cũ; chỉ thêm cột nullable.

ALTER TABLE user_learning_path
    ADD COLUMN warm_up_flashcard_version TEXT NULL;
