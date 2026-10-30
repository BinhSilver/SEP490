-- JLAS-W0199: tiêu chí chấm điểm AI Call chi tiết (ngữ pháp, từ vựng, nhiệm vụ, độ tự nhiên).
-- Chạy sau jlas_seed_all.sql. Chạy lại được: cập nhật theo code, không nhân đôi.
-- Hàm chấm điểm nhận diện tiêu chí theo code (grammar / vocab / mission / natural), tên hiển thị là tiếng Việt.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO evaluation_rule (rule_type, code, name, description, priority, max_score, weight, is_enabled) VALUES
    ('SCORING', 'grammar_accuracy', 'Ngữ pháp', 'Trừ điểm theo số lỗi ngữ pháp/trợ từ AI phát hiện trong phiên.', 10, 100, 35, 1),
    ('SCORING', 'vocabulary_usage', 'Từ vựng', 'Trừ điểm theo số lỗi dùng từ AI phát hiện trong phiên.', 20, 100, 30, 1),
    ('SCORING', 'mission_completion', 'Hoàn thành nhiệm vụ', 'Theo tỉ lệ mục tiêu bắt buộc của kịch bản đã hoàn thành.', 30, 100, 20, 1),
    ('SCORING', 'naturalness', 'Độ tự nhiên', 'Trừ nhẹ theo tổng số lỗi, thể hiện độ trôi chảy chung.', 40, 100, 15, 1)
ON DUPLICATE KEY UPDATE rule_type = VALUES(rule_type), name = VALUES(name), description = VALUES(description),
    priority = VALUES(priority), max_score = VALUES(max_score), weight = VALUES(weight), is_enabled = VALUES(is_enabled);

-- Tiêu chí tổng quát cũ được thay bằng 4 tiêu chí chi tiết ở trên.
UPDATE evaluation_rule SET is_enabled = 0 WHERE code = 'overall_fluency';

-- Gắn 4 tiêu chí cho mọi kịch bản đang có.
INSERT IGNORE INTO scenario_evaluation_rule (scenario_id, rule_id)
SELECT s.id, r.id
FROM scenario s
JOIN evaluation_rule r ON r.code IN ('grammar_accuracy', 'vocabulary_usage', 'mission_completion', 'naturalness');
