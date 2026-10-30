-- =============================================================================
-- JLAS: toàn bộ dữ liệu mẫu trong MỘT file
--
-- Gộp từ:
--   * sql/data_full.sql (feat-all, DB kotsukotsu) đã chuyển sang DB jlas:
--       - AI hội thoại (category, scenario, prompt, evaluation rule)
--       - từ điển (tag, kanji, từ vựng)
--   * giáo trình Onyomi sinh từ scripts/onyomi/onyomi_rules.json
--     (3 unit, 19 bài, 41 quy tắc, 317 ví dụ, 12 ngoại lệ, ~2080 câu hỏi 8 dạng;
--      mỗi bài 1 quiz luyện tập + 1 quiz ghép từ, mỗi giai đoạn 1 bài kiểm tra cuối)
--       - dữ liệu mẫu cho 2 tài khoản demo
--   * update/sql/seeds/flashcard_demo_5_sets.sql
--   * update/sql/seeds/stitch_progress_demo.sql
--   * update/sql/seeds/moderation_w0250_demo.sql
--
-- Chạy SAU khi đã có schema: jlas_schema.sql + sql/jlas_migrations_all.sql.  Ví dụ:
--   mysql -uroot -p --default-character-set=utf8mb4 < sql/seeds/jlas_seed_all.sql
-- Chạy lại nhiều lần được: không nhân đôi dữ liệu.
--
-- Tài khoản:
--   demo@jlab.local / admin@jlab.local / content@jlab.local (CONTENT_CREATOR)
--                                                 mật khẩu Password123!
--   admin@jlas.local, mod@jlas.local, learner.mute|ban|clean@jlas.local
--                                                 mật khẩu Test@12345
--
-- LƯU Ý trước khi chạy vào DB đang dùng:
--   * Phần 6 (stitch) GHI ĐÈ chuỗi ngày, mục tiêu và đặt credit_balance = 1250
--     cho huyphw2@gmail.com.
--   * Phần 6 (flashcard) xoá rồi tạo lại 5 bộ demo (moderation_summary =
--     DEMO_FLASHCARD_5SETS) của huyphw2@gmail.com; các bộ khác không bị động tới.
--   * Phần 7 (moderation) đặt lại vai trò + mật khẩu Test@12345 cho admin@jlas.local,
--     mod@jlas.local, learner.mute/ban/clean@jlas.local, đặt huypcpsn@gmail.com
--     thành ADMIN, và tạo lại hàng đợi/kháng nghị mẫu. Khác file gốc: KHÔNG
--     xoá các tài khoản test, nên bộ flashcard và dữ liệu test tay của họ được giữ.
--   Nếu không có huyphw2@gmail.com, flashcard/stitch dùng demo@jlab.local thay thế.
-- =============================================================================

USE jlas;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @SEED_PREVIOUS_SQL_SAFE_UPDATES = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

-- =============================================================================
-- 1) Vai trò, gói premium và 3 tài khoản demo
--    demo@jlab.local (LEARNER), admin@jlab.local (ADMIN) và content@jlab.local (CONTENT_CREATOR,
--    được mở toàn bộ lộ trình Onyomi để kiểm tra nội dung), mật khẩu: Password123!
-- =============================================================================
INSERT IGNORE INTO roles (name, description) VALUES
    ('LEARNER',     'Learner'),
    ('CONTENT_CREATOR', 'Người biên soạn nội dung'),
    ('MODERATOR',   'Kiem duyet noi dung cong dong, tu khoa cam, khang nghi, dong gop tu dien'),
    ('ADMIN',       'Quan tri: toan bo quyen kiem duyet va che tai nguoi dung');

INSERT IGNORE INTO premium_plans (name, description, price, duration_days, is_active) VALUES
    ('FREE',    'Free tier',       0.00, 36500, 1),
    ('MONTHLY', 'Monthly premium', 10000.00, 30,  1),
    ('YEARLY',  'Yearly premium',  100000.00, 365, 1);

-- [JLAS2-51] Demo CP packages; only after schema/migration 031.
INSERT IGNORE INTO credit_packages (name, description, credit_amount, price, is_active) VALUES
    ('CP 100', '100 CP', 100, 10000.00, 1),
    ('CP 500', '500 CP', 500, 45000.00, 1),
    ('CP 1200', '1200 CP', 1200, 100000.00, 1);

INSERT IGNORE INTO users
    (role_id, premium_plan_id, email, username, password_hash, login_type,
     full_name, gender, japanese_level, credit_balance, is_verified)
VALUES
    ((SELECT id FROM roles WHERE name = 'LEARNER'), (SELECT id FROM premium_plans WHERE name = 'FREE'),
     'demo@jlab.local', 'demo', '$2a$10$6rwQqFQw0OsYuuC2OMa3/uLm..ThobaeqDWqSaLAHQUG8GSoJHUmi',
     'LOCAL', 'Demo Learner', 'OTHER', 'N5', 52, 1),
    ((SELECT id FROM roles WHERE name = 'ADMIN'), (SELECT id FROM premium_plans WHERE name = 'MONTHLY'),
     'admin@jlab.local', 'admin', '$2a$10$6rwQqFQw0OsYuuC2OMa3/uLm..ThobaeqDWqSaLAHQUG8GSoJHUmi',
     'LOCAL', 'Demo Administrator', 'OTHER', 'N1', 0, 1),
    ((SELECT id FROM roles WHERE name = 'CONTENT_CREATOR'), (SELECT id FROM premium_plans WHERE name = 'FREE'),
     'content@jlab.local', 'content', '$2a$10$6rwQqFQw0OsYuuC2OMa3/uLm..ThobaeqDWqSaLAHQUG8GSoJHUmi',
     'LOCAL', 'Demo Content Admin', 'OTHER', 'N3', 0, 1);

SET @demo_user  = (SELECT id FROM users WHERE email = 'demo@jlab.local');
SET @demo_admin = (SELECT id FROM users WHERE email = 'admin@jlab.local');

-- =============================================================================
-- 2) AI hội thoại: nhóm, vai, prompt, kịch bản, tiêu chí chấm (từ data_full.sql)
-- =============================================================================
INSERT IGNORE INTO category
    (id, code, title, title_ja, description, icon, display_order, is_active)
VALUES
    (1, 'food-drink', 'Ăn uống', '食事', 'Gọi món, hỏi thành phần và thanh toán trong các quán ăn quen thuộc.', 'fa-solid fa-utensils', 1, 1),
    (2, 'introduction', 'Làm quen', '自己紹介', 'Chào hỏi, tự giới thiệu và bắt chuyện lịch sự với người mới gặp.', 'fa-solid fa-user-group', 2, 1),
    (3, 'daily-life', 'Đời sống hằng ngày', '日常生活', 'Những tình huống thực tế khi sống, học tập và mua sắm ở Nhật.', 'fa-solid fa-house', 3, 1),
    (4, 'transport', 'Di chuyển', '交通', 'Hỏi đường, mua vé và sử dụng phương tiện công cộng.', 'fa-solid fa-train-subway', 4, 1),
    (5, 'shopping', 'Mua sắm', '買い物', 'Mua hàng, thử đồ, đổi trả và thanh toán tự nhiên.', 'fa-solid fa-bag-shopping', 5, 1),
    (6, 'health-service', 'Sức khỏe & dịch vụ', '健康・サービス', 'Đặt lịch khám, mô tả triệu chứng và xử lý nhu cầu thiết yếu.', 'fa-solid fa-kit-medical', 6, 1),
    (7, 'work-study', 'Công việc & học tập', '仕事・学校', 'Giao tiếp đúng mực tại công ty, lớp học và trường học.', 'fa-solid fa-briefcase', 7, 1),
    (8, 'travel', 'Du lịch & lưu trú', '旅行・宿泊', 'Giao tiếp tại khách sạn và các tình huống đi lại của du khách.', 'fa-solid fa-suitcase-rolling', 8, 1)
ON DUPLICATE KEY UPDATE
    code = VALUES(code), title = VALUES(title), title_ja = VALUES(title_ja), description = VALUES(description),
    icon = VALUES(icon), display_order = VALUES(display_order), is_active = VALUES(is_active);

INSERT IGNORE INTO scenario_role
    (id, role_kind, code, name, description, personality, speech_style)
VALUES
    (1, 'AI', 'restaurant_staff', 'Nhân viên nhà hàng', 'Phục vụ một quán ăn gia đình tại Nhật.', 'Lịch sự, kiên nhẫn và chủ động xác nhận thông tin.', 'Dùng です・ます, câu ngắn rõ ràng, không dùng kính ngữ quá khó.'),
    (2, 'LEARNER', 'restaurant_guest', 'Khách dùng bữa', 'Người học gọi món và thanh toán.', NULL, NULL),
    (3, 'AI', 'new_friend', 'Người bạn mới', 'Một người Nhật cùng tham gia buổi gặp mặt nhỏ.', 'Cởi mở, tò mò vừa phải và khích lệ người học.', 'Nói tự nhiên ở mức N5, hỏi từng ý một.'),
    (4, 'LEARNER', 'newcomer', 'Người mới gặp', 'Người học tự giới thiệu và tìm điểm chung.', NULL, NULL),
    (5, 'AI', 'station_staff', 'Nhân viên nhà ga', 'Nhân viên trực quầy thông tin ga tàu.', 'Bình tĩnh, chính xác, luôn ưu tiên chỉ dẫn dễ làm theo.', 'Lịch sự chuẩn, dùng câu chỉ đường đơn giản.'),
    (6, 'LEARNER', 'commuter', 'Hành khách', 'Người học cần tìm đường đến điểm đến.', NULL, NULL),
    (7, 'AI', 'convenience_cashier', 'Nhân viên cửa hàng tiện lợi', 'Thu ngân của cửa hàng tiện lợi.', 'Nhanh nhẹn, thân thiện và xác nhận mọi lựa chọn ở quầy.', 'Mẫu câu giao dịch ngắn, phổ biến.'),
    (8, 'LEARNER', 'convenience_customer', 'Khách mua hàng', 'Người học mua đồ ăn và thanh toán.', NULL, NULL),
    (9, 'AI', 'apparel_clerk', 'Nhân viên cửa hàng quần áo', 'Tư vấn viên tại cửa hàng thời trang.', 'Lịch sự, hữu ích, không gây áp lực mua hàng.', 'Dùng từ vựng kích cỡ, màu sắc và thử đồ.'),
    (10, 'LEARNER', 'shopper', 'Khách mua quần áo', 'Người học tìm, thử và quyết định mua một món đồ.', NULL, NULL),
    (11, 'AI', 'clinic_receptionist', 'Lễ tân phòng khám', 'Lễ tân tiếp nhận bệnh nhân tại phòng khám nội khoa.', 'Trấn an, cẩn thận và tôn trọng riêng tư.', 'Nói chậm, xác nhận triệu chứng và thời gian.'),
    (12, 'LEARNER', 'patient', 'Bệnh nhân', 'Người học muốn đặt lịch khám vì cảm thấy không khỏe.', NULL, NULL),
    (13, 'AI', 'pharmacist', 'Dược sĩ', 'Dược sĩ tư vấn thuốc không kê đơn.', 'Điềm tĩnh, an toàn và không chẩn đoán bệnh.', 'Hỏi ngắn về triệu chứng, đưa khuyến nghị thận trọng.'),
    (14, 'LEARNER', 'pharmacy_customer', 'Khách mua thuốc', 'Người học mô tả triệu chứng nhẹ và hỏi cách dùng thuốc.', NULL, NULL),
    (15, 'AI', 'coworker', 'Đồng nghiệp', 'Đồng nghiệp Nhật cùng chuẩn bị cuộc họp.', 'Hợp tác, tôn trọng thời gian và mục tiêu chung.', 'Lịch sự vừa phải trong môi trường công sở.'),
    (16, 'LEARNER', 'office_worker', 'Nhân viên văn phòng', 'Người học đổi lịch họp và phân công việc.', NULL, NULL),
    (17, 'AI', 'teacher', 'Giáo viên', 'Giáo viên chủ nhiệm tại trường Nhật.', 'Quan tâm, rõ ràng và không phán xét.', 'Nói thân thiện nhưng đúng mực với học sinh.'),
    (18, 'LEARNER', 'student', 'Học sinh', 'Người học xin phép nghỉ học và hỏi bài tập.', NULL, NULL),
    (19, 'AI', 'hotel_receptionist', 'Lễ tân khách sạn', 'Lễ tân khách sạn hỗ trợ khách nhận phòng.', 'Chuyên nghiệp, hiếu khách và xác nhận giấy tờ rõ ràng.', 'Kính ngữ nhẹ, câu ngắn cho du khách.'),
    (20, 'LEARNER', 'hotel_guest', 'Khách lưu trú', 'Người học nhận phòng và hỏi thông tin cơ bản.', NULL, NULL)
ON DUPLICATE KEY UPDATE
    role_kind = VALUES(role_kind), code = VALUES(code), name = VALUES(name), description = VALUES(description),
    personality = VALUES(personality), speech_style = VALUES(speech_style);

INSERT IGNORE INTO prompt_template
    (id, code, name, system_prompt, version, is_active)
VALUES
    (1, 'scenario_practical_v2', 'Practical scenario prompt',
     'Bạn là {{ai_role_name}} trong kịch bản {{scenario_title}}. Giữ đúng vai, chỉ nói tiếng Nhật trong trường reply và điều chỉnh độ khó theo {{jlpt_level}}. Bối cảnh: {{background}}; địa điểm: {{location}}; thời điểm: {{time_of_day}}. Trạng thái hiện tại: {{state_name}}. Hướng dẫn của trạng thái: {{state_prompt}}. Mục tiêu cần hoàn thành: {{objectives}}. Từ vựng ưu tiên: {{vocabulary}}. Ngữ pháp ưu tiên: {{grammar}}. Hãy cho người học cơ hội tự nói; chỉ sửa nhẹ sau khi đã tiếp tục hội thoại. Chấp nhận cách diễn đạt tương đương tự nhiên. Chỉ đánh dấu objectivesMet khi người học thực sự đạt mục tiêu. Chỉ readyToAdvanceState khi xong trạng thái hiện tại và readyToEnd khi đã chào kết thúc.',
     '2.0', 1)
ON DUPLICATE KEY UPDATE
    code = VALUES(code), name = VALUES(name), system_prompt = VALUES(system_prompt),
    version = VALUES(version), is_active = VALUES(is_active);

INSERT INTO scenario
    (id, category_id, ai_role_id, learner_role_id, prompt_template_id,
     title, slug, description, jlpt_level, difficulty, estimated_minutes,
     background, location, time_of_day, flow_json,
     learning_content_json, thumbnail_url, is_active)
VALUES
    (1, 1, 1, 2, 1, 'Gọi món và thanh toán ở quán ăn', 'restaurant-order-and-pay',
     'Bạn vào quán ăn gia đình, gọi một món chính, nêu yêu cầu đơn giản và thanh toán lịch sự.', 'N5', 'EASY', 10,
     'Bạn có một bữa trưa ngắn trước giờ học và muốn gọi món không có nguyên liệu mình không ăn được.', 'Quán ăn gia đình gần ga', 'Buổi trưa',
     '{"version":1,"initialState":"GREETING","goals":[{"code":"TABLE","title":"Xác nhận số người","description":"Trả lời số khách và yêu cầu chỗ ngồi.","type":"MISSION","displayOrder":1,"required":true,"conditions":[{"type":"KEYWORD","value":"一人|ひとり|二人|ふたり"}]},{"code":"ORDER","title":"Gọi món chính","description":"Gọi một món bằng mẫu 〜をお願いします hoặc 〜をください.","type":"MISSION","displayOrder":2,"required":true,"conditions":[{"type":"KEYWORD","value":"お願いします|ください|ラーメン|定食|うどん"}]},{"code":"PAY","title":"Yêu cầu thanh toán","description":"Nói muốn tính tiền và cảm ơn.","type":"MISSION","displayOrder":3,"required":true,"conditions":[{"type":"KEYWORD","value":"お会計|ありがとう"}]}],"states":[{"code":"GREETING","name":"Chào và xếp chỗ","prompt":"Chào khách, hỏi số người và có thể mời ngồi.","allowedGoalCodes":["TABLE"],"next":"ORDERING","suggestedReplies":["一人です。","二人です。"]},{"code":"ORDERING","name":"Gọi món","prompt":"Đưa menu, hỏi khách muốn gọi gì và có dị ứng hay yêu cầu đơn giản không.","allowedGoalCodes":["ORDER"],"next":"CONFIRM","suggestedReplies":["ラーメンをお願いします。","水をください。"]},{"code":"CONFIRM","name":"Xác nhận đơn","prompt":"Nhắc lại đơn ngắn gọn, hỏi có cần thêm gì không.","allowedGoalCodes":["ORDER"],"next":"PAYMENT","suggestedReplies":["はい、お願いします。","それで大丈夫です。"]},{"code":"PAYMENT","name":"Thanh toán và chào tạm biệt","prompt":"Sau khi khách yêu cầu, hướng dẫn thanh toán và cảm ơn.","allowedGoalCodes":["PAY"],"next":"END","suggestedReplies":["お会計をお願いします。","ありがとうございました。"]}],"endings":{"success":{"code":"SUCCESS","title":"Hoàn thành bữa ăn","message":"ご来店ありがとうございました。またお待ちしています。","condition":"all_objectives","nextAction":"SHOW_RESULT"}}}',
     '{"vocabulary":[{"term":"注文","reading":"ちゅうもん","meaning":"gọi món","example":"注文をお願いします。","jlpt":"N5"},{"term":"お水","reading":"おみず","meaning":"nước lọc","example":"お水をください。","jlpt":"N5"},{"term":"苦手","reading":"にがて","meaning":"không ăn/không giỏi món gì","example":"辛いものは苦手です。","jlpt":"N4"},{"term":"お会計","reading":"おかいけい","meaning":"thanh toán","example":"お会計をお願いします。","jlpt":"N5"}],"grammar":[{"pattern":"〜をお願いします","explanation":"Cách lịch sự, tự nhiên để gọi món hoặc nhờ ai làm việc gì.","example":"カレーをお願いします。"},{"pattern":"〜は苦手です","explanation":"Nói một món hoặc việc mình không thích/không dùng được.","example":"辛いものは苦手です。"}],"exampleDialogue":[{"speaker":"ai","text":"いらっしゃいませ。何名様ですか。","translation":"Xin chào quý khách. Mấy người ạ?"},{"speaker":"learner","text":"一人です。ラーメンをお願いします。","translation":"Một người. Cho tôi ramen."},{"speaker":"ai","text":"かしこまりました。お会計はレジでお願いします。","translation":"Vâng ạ. Xin thanh toán ở quầy."}]}', NULL, 1),
    (2, 2, 3, 4, 1, 'Làm quen ở buổi gặp mặt', 'first-meeting-introduction',
     'Tự giới thiệu, nói nơi đến, sở thích và phản hồi lại câu chuyện của một người bạn mới.', 'N5', 'EASY', 8,
     'Bạn tham dự buổi giao lưu nhỏ dành cho người mới đến Nhật.', 'Quán cà phê cộng đồng', 'Buổi chiều',
     '{"version":1,"initialState":"GREETING","goals":[{"code":"NAME","title":"Nói tên","description":"Chào và tự giới thiệu tên.","type":"MISSION","displayOrder":1,"required":true,"conditions":[{"type":"KEYWORD","value":"はじめまして|です"}]},{"code":"ORIGIN","title":"Nói nơi đến","description":"Nói quốc gia hoặc thành phố của mình.","type":"MISSION","displayOrder":2,"required":true,"conditions":[{"type":"KEYWORD","value":"から来ました|ベトナム"}]},{"code":"HOBBY","title":"Chia sẻ sở thích","description":"Nói ít nhất một sở thích và hỏi lại bạn mới.","type":"MISSION","displayOrder":3,"required":true,"conditions":[{"type":"KEYWORD","value":"好きです|趣味|ですか"}]}],"states":[{"code":"GREETING","name":"Chào lần đầu","prompt":"Chào thân thiện và hỏi tên người học.","allowedGoalCodes":["NAME"],"next":"ORIGIN","suggestedReplies":["はじめまして。アンです。","よろしくお願いします。"]},{"code":"ORIGIN","name":"Nói nơi đến","prompt":"Hỏi người học đến từ đâu và phản hồi tích cực.","allowedGoalCodes":["ORIGIN"],"next":"HOBBY","suggestedReplies":["ベトナムから来ました。"]},{"code":"HOBBY","name":"Trao đổi sở thích","prompt":"Hỏi sở thích, sau đó nói một sở thích của bạn để tạo hội thoại hai chiều.","allowedGoalCodes":["HOBBY"],"next":"END","suggestedReplies":["音楽が好きです。","あなたの趣味は何ですか。"]}],"endings":{"success":{"code":"SUCCESS","title":"Đã làm quen","message":"お話しできてうれしかったです。また会いましょう。","condition":"all_objectives","nextAction":"SHOW_RESULT"}}}',
     '{"vocabulary":[{"term":"出身","reading":"しゅっしん","meaning":"quê quán","example":"出身はベトナムです。","jlpt":"N5"},{"term":"趣味","reading":"しゅみ","meaning":"sở thích","example":"趣味は音楽です。","jlpt":"N5"},{"term":"映画","reading":"えいが","meaning":"phim","example":"映画が好きです。","jlpt":"N5"},{"term":"よろしくお願いします","reading":"よろしくおねがいします","meaning":"rất mong được làm quen","example":"どうぞよろしくお願いします。","jlpt":"N5"}],"grammar":[{"pattern":"〜から来ました","explanation":"Nói nơi mình đến từ.","example":"ベトナムから来ました。"},{"pattern":"〜が好きです","explanation":"Nói điều mình thích.","example":"日本の映画が好きです。"}],"exampleDialogue":[{"speaker":"ai","text":"はじめまして。お名前は何ですか。","translation":"Rất vui được gặp bạn. Bạn tên gì?"},{"speaker":"learner","text":"はじめまして。ミンです。ベトナムから来ました。","translation":"Rất vui được gặp. Tôi là Minh, đến từ Việt Nam."},{"speaker":"ai","text":"ミンさんですね。趣味は何ですか。","translation":"Bạn Minh phải không. Sở thích của bạn là gì?"}]}', NULL, 1),
    (3, 4, 5, 6, 1, 'Hỏi đường và đổi tuyến ở nhà ga', 'station-directions-and-transfer',
     'Hỏi nhân viên ga cách đi đến một điểm đến, xác nhận tuyến tàu và sân ga cần đến.', 'N5', 'EASY', 10,
     'Bạn lần đầu đi tàu một mình và cần đến Shibuya trước một cuộc hẹn.', 'Quầy thông tin nhà ga', 'Buổi sáng',
     '{"version":1,"initialState":"DESTINATION","goals":[{"code":"DESTINATION","title":"Nêu điểm đến","description":"Nói nơi cần đến và xin hướng dẫn.","type":"MISSION","displayOrder":1,"required":true,"conditions":[{"type":"KEYWORD","value":"渋谷|行きたい|行けば"}]},{"code":"LINE","title":"Xác nhận tuyến","description":"Hỏi hoặc nhắc lại đúng tên tuyến tàu.","type":"MISSION","displayOrder":2,"required":true,"conditions":[{"type":"KEYWORD","value":"線|山手線|乗ります"}]},{"code":"PLATFORM","title":"Xác nhận sân ga","description":"Hỏi số sân ga hoặc cảm ơn sau khi hiểu chỉ dẫn.","type":"MISSION","displayOrder":3,"required":true,"conditions":[{"type":"KEYWORD","value":"番線|ありがとう|わかりました"}]}],"states":[{"code":"DESTINATION","name":"Nêu điểm đến","prompt":"Chào hành khách và hỏi cần hỗ trợ gì.","allowedGoalCodes":["DESTINATION"],"next":"LINE","suggestedReplies":["渋谷駅に行きたいです。","どう行けばいいですか。"]},{"code":"LINE","name":"Chọn tuyến tàu","prompt":"Chỉ tuyến phù hợp, sau đó yêu cầu hành khách xác nhận để tránh nhầm.","allowedGoalCodes":["LINE"],"next":"PLATFORM","suggestedReplies":["山手線に乗りますか。","はい、山手線ですね。"]},{"code":"PLATFORM","name":"Tìm sân ga","prompt":"Thông báo sân ga và hướng đi, hỏi người học đã hiểu chưa.","allowedGoalCodes":["PLATFORM"],"next":"END","suggestedReplies":["何番線ですか。","わかりました。ありがとうございます。"]}],"endings":{"success":{"code":"SUCCESS","title":"Đã tìm được đường","message":"お気をつけて。渋谷まで約二十分です。","condition":"all_objectives","nextAction":"SHOW_RESULT"}}}',
     '{"vocabulary":[{"term":"駅","reading":"えき","meaning":"nhà ga","example":"渋谷駅に行きたいです。","jlpt":"N5"},{"term":"線","reading":"せん","meaning":"tuyến tàu","example":"山手線に乗ります。","jlpt":"N5"},{"term":"乗り換え","reading":"のりかえ","meaning":"đổi/chuyển tuyến","example":"新宿で乗り換えます。","jlpt":"N4"},{"term":"番線","reading":"ばんせん","meaning":"sân ga số","example":"三番線です。","jlpt":"N4"}],"grammar":[{"pattern":"〜に行きたいです","explanation":"Nói nơi muốn đến.","example":"渋谷に行きたいです。"},{"pattern":"どう行けばいいですか","explanation":"Hỏi lịch sự cách đi đến một nơi.","example":"新宿までどう行けばいいですか。"}],"exampleDialogue":[{"speaker":"ai","text":"はい、どうされましたか。","translation":"Vâng, bạn cần gì ạ?"},{"speaker":"learner","text":"渋谷駅に行きたいです。どう行けばいいですか。","translation":"Tôi muốn đến ga Shibuya. Đi thế nào ạ?"},{"speaker":"ai","text":"山手線の外回りに乗ってください。三番線です。","translation":"Hãy đi tuyến Yamanote vòng ngoài. Sân ga số 3."}]}', NULL, 1),
    (4, 3, 7, 8, 1, 'Mua đồ ăn ở cửa hàng tiện lợi', 'convenience-store-checkout',
     'Chọn đồ ăn, yêu cầu hâm nóng, quyết định có cần túi và thanh toán tại konbini.', 'N5', 'EASY', 8,
     'Bạn mua cơm hộp và đồ uống trên đường về nhà.', 'Cửa hàng tiện lợi', 'Buổi tối',
     '{"version":1,"initialState":"CHECKOUT","goals":[{"code":"ITEM","title":"Nói món đã chọn","description":"Đặt ít nhất một món lên quầy và xác nhận.","type":"MISSION","displayOrder":1,"required":true,"conditions":[{"type":"KEYWORD","value":"お弁当|おにぎり|これ"}]},{"code":"HEAT","title":"Trả lời về việc hâm nóng","description":"Đồng ý hoặc từ chối hâm nóng món ăn.","type":"MISSION","displayOrder":2,"required":true,"conditions":[{"type":"KEYWORD","value":"温め|お願いします|大丈夫"}]},{"code":"BAG_PAY","title":"Chọn túi và thanh toán","description":"Trả lời về túi, trả tiền và cảm ơn.","type":"MISSION","displayOrder":3,"required":true,"conditions":[{"type":"KEYWORD","value":"袋|カード|現金|ありがとう"}]}],"states":[{"code":"CHECKOUT","name":"Đặt hàng lên quầy","prompt":"Chào khách và xác nhận các món ở quầy.","allowedGoalCodes":["ITEM"],"next":"HEATING","suggestedReplies":["これをお願いします。","お弁当です。"]},{"code":"HEATING","name":"Hỏi hâm nóng","prompt":"Hỏi khách có muốn hâm nóng cơm hộp không.","allowedGoalCodes":["HEAT"],"next":"BAG_PAYMENT","suggestedReplies":["はい、温めてください。","いいえ、大丈夫です。"]},{"code":"BAG_PAYMENT","name":"Túi và thanh toán","prompt":"Hỏi về túi, thông báo tổng tiền và nhận thanh toán.","allowedGoalCodes":["BAG_PAY"],"next":"END","suggestedReplies":["袋をお願いします。","カードでお願いします。"]}],"endings":{"success":{"code":"SUCCESS","title":"Mua hàng xong","message":"ありがとうございました。お気をつけて。","condition":"all_objectives","nextAction":"SHOW_RESULT"}}}',
     '{"vocabulary":[{"term":"お弁当","reading":"おべんとう","meaning":"cơm hộp","example":"お弁当を一つください。","jlpt":"N5"},{"term":"温める","reading":"あたためる","meaning":"hâm nóng","example":"温めますか。","jlpt":"N4"},{"term":"レジ袋","reading":"レジぶくろ","meaning":"túi đựng hàng","example":"レジ袋をお願いします。","jlpt":"N4"},{"term":"現金","reading":"げんきん","meaning":"tiền mặt","example":"現金で払います。","jlpt":"N5"}],"grammar":[{"pattern":"〜てください","explanation":"Đề nghị lịch sự ai đó làm việc gì.","example":"温めてください。"},{"pattern":"〜でお願いします","explanation":"Nêu phương thức thanh toán hoặc cách thực hiện.","example":"カードでお願いします。"}],"exampleDialogue":[{"speaker":"ai","text":"お弁当、温めますか。","translation":"Bạn có muốn hâm nóng cơm hộp không?"},{"speaker":"learner","text":"はい、お願いします。袋もお願いします。","translation":"Vâng, làm ơn. Cho tôi túi luôn."},{"speaker":"ai","text":"かしこまりました。合計は六百円です。","translation":"Vâng ạ. Tổng cộng là 600 yên."}]}', NULL, 1),
    (5, 5, 9, 10, 1, 'Mua quần áo: hỏi cỡ và thử đồ', 'clothes-shopping-size-fitting',
     'Tìm một chiếc áo, hỏi cỡ/màu, thử đồ và quyết định mua hay không.', 'N4', 'MEDIUM', 12,
     'Bạn cần mua áo khoác nhẹ cho chuyến đi sắp tới nhưng chưa chắc kích cỡ phù hợp.', 'Cửa hàng quần áo', 'Buổi chiều',
     '{"version":1,"initialState":"FINDING","goals":[{"code":"ITEM","title":"Nêu món muốn tìm","description":"Nói loại quần áo và màu mong muốn.","type":"MISSION","displayOrder":1,"required":true,"conditions":[{"type":"KEYWORD","value":"ジャケット|シャツ|黒|青"}]},{"code":"SIZE","title":"Hỏi kích cỡ","description":"Hỏi còn kích cỡ phù hợp hay không.","type":"MISSION","displayOrder":2,"required":true,"conditions":[{"type":"KEYWORD","value":"サイズ|ありますか|M|L"}]},{"code":"FITTING","title":"Thử và quyết định","description":"Xin thử đồ rồi nói quyết định mua hoặc cân nhắc.","type":"MISSION","displayOrder":3,"required":true,"conditions":[{"type":"KEYWORD","value":"試着|買います|考えます"}]}],"states":[{"code":"FINDING","name":"Tìm sản phẩm","prompt":"Hỏi khách đang tìm loại quần áo nào, màu nào.","allowedGoalCodes":["ITEM"],"next":"SIZE","suggestedReplies":["黒いジャケットを探しています。"]},{"code":"SIZE","name":"Hỏi kích cỡ","prompt":"Đề xuất kích cỡ và kiểm tra hàng trong kho.","allowedGoalCodes":["SIZE"],"next":"FITTING","suggestedReplies":["Mサイズはありますか。","Lサイズも見たいです。"]},{"code":"FITTING","name":"Thử đồ và quyết định","prompt":"Chỉ phòng thử đồ, sau đó hỏi khách cảm thấy thế nào và có mua không.","allowedGoalCodes":["FITTING"],"next":"END","suggestedReplies":["試着してもいいですか。","これを買います。"]}],"endings":{"success":{"code":"SUCCESS","title":"Hoàn thành mua sắm","message":"ありがとうございます。レジへどうぞ。","condition":"all_objectives","nextAction":"SHOW_RESULT"}}}',
     '{"vocabulary":[{"term":"試着","reading":"しちゃく","meaning":"thử quần áo","example":"試着してもいいですか。","jlpt":"N4"},{"term":"サイズ","reading":"サイズ","meaning":"kích cỡ","example":"Mサイズはありますか。","jlpt":"N5"},{"term":"在庫","reading":"ざいこ","meaning":"hàng tồn/kho","example":"在庫を確認します。","jlpt":"N3"},{"term":"似合う","reading":"にあう","meaning":"hợp với","example":"よく似合っています。","jlpt":"N4"}],"grammar":[{"pattern":"〜はありますか","explanation":"Hỏi một sản phẩm/kích cỡ có sẵn không.","example":"黒はありますか。"},{"pattern":"〜してもいいですか","explanation":"Xin phép làm việc gì.","example":"試着してもいいですか。"}],"exampleDialogue":[{"speaker":"ai","text":"何をお探しですか。","translation":"Bạn đang tìm gì ạ?"},{"speaker":"learner","text":"黒いジャケットを探しています。Mサイズはありますか。","translation":"Tôi tìm áo khoác đen. Có cỡ M không?"},{"speaker":"ai","text":"はい、ございます。試着室はあちらです。","translation":"Có ạ. Phòng thử đồ ở phía kia."}]}', NULL, 1),
    (6, 6, 11, 12, 1, 'Đặt lịch khám tại phòng khám', 'clinic-appointment',
     'Nói triệu chứng cơ bản, thời gian bị khó chịu và đặt một lịch khám phù hợp.', 'N4', 'MEDIUM', 12,
     'Bạn bị cảm nhẹ từ hôm qua và muốn khám ở phòng khám gần nhà.', 'Quầy tiếp nhận phòng khám', 'Buổi sáng',
     '{"version":1,"initialState":"SYMPTOM","goals":[{"code":"SYMPTOM","title":"Mô tả triệu chứng","description":"Nói ít nhất một triệu chứng như sốt, đau họng hoặc ho.","type":"MISSION","displayOrder":1,"required":true,"conditions":[{"type":"KEYWORD","value":"熱|喉|せき|頭"}]},{"code":"DURATION","title":"Nói thời điểm bắt đầu","description":"Nói triệu chứng bắt đầu từ khi nào.","type":"MISSION","displayOrder":2,"required":true,"conditions":[{"type":"KEYWORD","value":"昨日から|今日から|二日"}]},{"code":"BOOKING","title":"Chốt lịch hẹn","description":"Chọn một khung giờ và xác nhận lịch.","type":"MISSION","displayOrder":3,"required":true,"conditions":[{"type":"KEYWORD","value":"時|お願いします|大丈夫"}]}],"states":[{"code":"SYMPTOM","name":"Nêu lý do đến khám","prompt":"Chào bệnh nhân, hỏi hôm nay có vấn đề gì.","allowedGoalCodes":["SYMPTOM"],"next":"DURATION","suggestedReplies":["喉が痛いです。","せきが出ます。"]},{"code":"DURATION","name":"Hỏi thời gian và mức độ","prompt":"Hỏi triệu chứng bắt đầu khi nào; không chẩn đoán, chỉ tiếp nhận.","allowedGoalCodes":["DURATION"],"next":"BOOKING","suggestedReplies":["昨日からです。","二日前からです。"]},{"code":"BOOKING","name":"Đặt lịch","prompt":"Đưa hai khung giờ trong ngày, yêu cầu người học chọn một giờ.","allowedGoalCodes":["BOOKING"],"next":"END","suggestedReplies":["十時でお願いします。","はい、大丈夫です。"]}],"endings":{"success":{"code":"SUCCESS","title":"Đã đặt lịch khám","message":"では、十時にお待ちしています。保険証をお持ちください。","condition":"all_objectives","nextAction":"SHOW_RESULT"}}}',
     '{"vocabulary":[{"term":"喉","reading":"のど","meaning":"cổ họng","example":"喉が痛いです。","jlpt":"N4"},{"term":"せき","reading":"せき","meaning":"ho","example":"せきが出ます。","jlpt":"N4"},{"term":"熱","reading":"ねつ","meaning":"sốt","example":"熱があります。","jlpt":"N4"},{"term":"保険証","reading":"ほけんしょう","meaning":"thẻ bảo hiểm","example":"保険証を持ってきました。","jlpt":"N3"}],"grammar":[{"pattern":"〜が痛いです","explanation":"Nói bộ phận cơ thể bị đau.","example":"頭が痛いです。"},{"pattern":"〜からです","explanation":"Nói thời điểm bắt đầu.","example":"昨日からです。"}],"exampleDialogue":[{"speaker":"ai","text":"今日はどうされましたか。","translation":"Hôm nay bạn gặp vấn đề gì?"},{"speaker":"learner","text":"昨日から喉が痛くて、せきが出ます。","translation":"Từ hôm qua tôi đau họng và bị ho."},{"speaker":"ai","text":"わかりました。十時か十一時はいかがですか。","translation":"Tôi hiểu rồi. 10 giờ hoặc 11 giờ có được không?"}]}', NULL, 1),
    (7, 6, 13, 14, 1, 'Hỏi thuốc cảm ở nhà thuốc', 'pharmacy-cold-medicine',
     'Mô tả triệu chứng nhẹ, nói nhu cầu về thuốc và xác nhận cách dùng cơ bản.', 'N4', 'MEDIUM', 10,
     'Bạn bị đau họng nhẹ và ho, muốn hỏi thuốc không kê đơn tại nhà thuốc.', 'Nhà thuốc gần khu dân cư', 'Buổi tối',
     '{"version":1,"initialState":"SYMPTOM","goals":[{"code":"SYMPTOM","title":"Nói triệu chứng nhẹ","description":"Mô tả ho hoặc đau họng.","type":"MISSION","displayOrder":1,"required":true,"conditions":[{"type":"KEYWORD","value":"喉|せき|痛い"}]},{"code":"REQUEST","title":"Hỏi thuốc phù hợp","description":"Nói muốn một loại thuốc phù hợp với triệu chứng.","type":"MISSION","displayOrder":2,"required":true,"conditions":[{"type":"KEYWORD","value":"薬|ください|ありますか"}]},{"code":"USAGE","title":"Xác nhận cách dùng","description":"Nhắc lại số lần uống hoặc hỏi cách dùng.","type":"MISSION","displayOrder":3,"required":true,"conditions":[{"type":"KEYWORD","value":"一日|回|飲みます|わかりました"}]}],"states":[{"code":"SYMPTOM","name":"Mô tả triệu chứng","prompt":"Hỏi khách đang khó chịu ở đâu; không chẩn đoán bệnh.","allowedGoalCodes":["SYMPTOM"],"next":"REQUEST","suggestedReplies":["喉が痛くて、せきが出ます。"]},{"code":"REQUEST","name":"Chọn thuốc","prompt":"Đề xuất thuốc OTC phù hợp ở mức hội thoại giả định, hỏi có dị ứng/đang dùng thuốc nào không.","allowedGoalCodes":["REQUEST"],"next":"USAGE","suggestedReplies":["この症状の薬はありますか。"]},{"code":"USAGE","name":"Xác nhận cách dùng","prompt":"Nói cách dùng giả định trên nhãn và mời khách đọc kỹ hướng dẫn.","allowedGoalCodes":["USAGE"],"next":"END","suggestedReplies":["一日三回ですね。","わかりました。"]}],"endings":{"success":{"code":"SUCCESS","title":"Đã hiểu cách dùng","message":"お大事にしてください。症状がひどい場合は受診してください。","condition":"all_objectives","nextAction":"SHOW_RESULT"}}}',
     '{"vocabulary":[{"term":"薬","reading":"くすり","meaning":"thuốc","example":"薬はありますか。","jlpt":"N4"},{"term":"症状","reading":"しょうじょう","meaning":"triệu chứng","example":"症状を教えてください。","jlpt":"N3"},{"term":"飲む","reading":"のむ","meaning":"uống thuốc","example":"食後に飲んでください。","jlpt":"N5"},{"term":"お大事に","reading":"おだいじに","meaning":"chúc mau khỏe","example":"お大事にしてください。","jlpt":"N4"}],"grammar":[{"pattern":"〜が出ます","explanation":"Nói một triệu chứng xuất hiện.","example":"せきが出ます。"},{"pattern":"一日〜回","explanation":"Nói số lần sử dụng trong một ngày.","example":"一日三回飲みます。"}],"exampleDialogue":[{"speaker":"ai","text":"どのような症状ですか。","translation":"Bạn có triệu chứng như thế nào?"},{"speaker":"learner","text":"喉が痛くて、せきが出ます。薬はありますか。","translation":"Tôi đau họng và ho. Có thuốc nào không?"},{"speaker":"ai","text":"こちらはいかがですか。一日三回、食後に飲んでください。","translation":"Loại này được không? Uống ba lần một ngày sau bữa ăn."}]}', NULL, 1),
    (8, 7, 15, 16, 1, 'Đổi lịch họp với đồng nghiệp', 'office-reschedule-meeting',
     'Xin đổi lịch họp vì có việc trùng, đề xuất thời gian khác và xác nhận phần việc cần chuẩn bị.', 'N4', 'MEDIUM', 12,
     'Bạn phát hiện cuộc họp chiều mai trùng với một lịch hẹn khách hàng đã có trước.', 'Văn phòng công ty', 'Buổi sáng',
     '{"version":1,"initialState":"APOLOGY","goals":[{"code":"APOLOGY","title":"Xin lỗi và nêu việc trùng lịch","description":"Xin lỗi vì không thể dự giờ họp cũ.","type":"MISSION","displayOrder":1,"required":true,"conditions":[{"type":"KEYWORD","value":"すみません|都合|参加できません"}]},{"code":"PROPOSE","title":"Đề xuất thời gian khác","description":"Đưa ra một thời điểm thay thế cụ thể.","type":"MISSION","displayOrder":2,"required":true,"conditions":[{"type":"KEYWORD","value":"時|明日|午後|いかがですか"}]},{"code":"CONFIRM","title":"Xác nhận chuẩn bị","description":"Xác nhận lịch mới và phần tài liệu/việc cần làm.","type":"MISSION","displayOrder":3,"required":true,"conditions":[{"type":"KEYWORD","value":"資料|準備|お願いします|わかりました"}]}],"states":[{"code":"APOLOGY","name":"Mở lời xin đổi lịch","prompt":"Đồng nghiệp hỏi về cuộc họp ngày mai; tạo cơ hội để người học xin lỗi và nêu xung đột.","allowedGoalCodes":["APOLOGY"],"next":"PROPOSE","suggestedReplies":["すみません、都合が悪くなりました。"]},{"code":"PROPOSE","name":"Đề xuất lịch mới","prompt":"Hỏi người học có thể họp vào giờ nào khác; thương lượng một phương án cụ thể.","allowedGoalCodes":["PROPOSE"],"next":"CONFIRM","suggestedReplies":["明日の午後三時はいかがですか。"]},{"code":"CONFIRM","name":"Xác nhận công việc","prompt":"Xác nhận lịch mới và hỏi ai sẽ chuẩn bị tài liệu.","allowedGoalCodes":["CONFIRM"],"next":"END","suggestedReplies":["資料を準備します。","よろしくお願いします。"]}],"endings":{"success":{"code":"SUCCESS","title":"Đã chốt lịch họp","message":"では、明日の午後三時にお願いします。","condition":"all_objectives","nextAction":"SHOW_RESULT"}}}',
     '{"vocabulary":[{"term":"都合","reading":"つごう","meaning":"tình hình/việc bận","example":"都合が悪くなりました。","jlpt":"N3"},{"term":"参加する","reading":"さんかする","meaning":"tham gia","example":"会議に参加します。","jlpt":"N4"},{"term":"資料","reading":"しりょう","meaning":"tài liệu","example":"資料を準備します。","jlpt":"N3"},{"term":"変更","reading":"へんこう","meaning":"thay đổi","example":"時間を変更できますか。","jlpt":"N3"}],"grammar":[{"pattern":"〜ことはできますか","explanation":"Hỏi lịch sự liệu có thể làm việc gì không.","example":"時間を変更することはできますか。"},{"pattern":"〜はいかがですか","explanation":"Đề xuất thời gian/phương án lịch sự.","example":"午後三時はいかがですか。"}],"exampleDialogue":[{"speaker":"ai","text":"明日の会議ですが、二時からで大丈夫ですか。","translation":"Về cuộc họp ngày mai, 2 giờ có ổn không?"},{"speaker":"learner","text":"すみません、その時間は参加できません。午後三時はいかがですか。","translation":"Xin lỗi, giờ đó tôi không tham gia được. 3 giờ chiều được không?"},{"speaker":"ai","text":"大丈夫です。では、資料の準備をお願いします。","translation":"Được. Vậy nhờ bạn chuẩn bị tài liệu."}]}', NULL, 1),
    (9, 7, 17, 18, 1, 'Xin nghỉ học và hỏi bài tập', 'school-absence-and-homework',
     'Xin phép nghỉ học vì không khỏe, nói thời gian nghỉ và hỏi bài tập cần theo kịp.', 'N5', 'EASY', 10,
     'Bạn nhắn/gọi cho giáo viên vào buổi sáng vì không thể đến lớp hôm nay.', 'Phòng giáo viên hoặc cuộc gọi lớp học', 'Buổi sáng',
     '{"version":1,"initialState":"IDENTIFY","goals":[{"code":"IDENTIFY","title":"Nêu tên và lớp","description":"Chào giáo viên, nói tên hoặc lớp của mình.","type":"MISSION","displayOrder":1,"required":true,"conditions":[{"type":"KEYWORD","value":"です|クラス"}]},{"code":"ABSENCE","title":"Xin nghỉ học","description":"Nói hôm nay không khỏe và xin nghỉ.","type":"MISSION","displayOrder":2,"required":true,"conditions":[{"type":"KEYWORD","value":"休みます|体調|悪い"}]},{"code":"HOMEWORK","title":"Hỏi bài tập","description":"Hỏi bài tập hoặc nội dung cần xem lại.","type":"MISSION","displayOrder":3,"required":true,"conditions":[{"type":"KEYWORD","value":"宿題|教えて|ありますか"}]}],"states":[{"code":"IDENTIFY","name":"Chào giáo viên","prompt":"Giáo viên nghe máy và hỏi ai đang gọi.","allowedGoalCodes":["IDENTIFY"],"next":"ABSENCE","suggestedReplies":["ミンです。N5クラスです。"]},{"code":"ABSENCE","name":"Xin nghỉ","prompt":"Hỏi người học có thể đến lớp không; phản hồi quan tâm.","allowedGoalCodes":["ABSENCE"],"next":"HOMEWORK","suggestedReplies":["今日は体調が悪いので、休みます。"]},{"code":"HOMEWORK","name":"Hỏi bài tập","prompt":"Nói ngắn gọn nội dung buổi học, tạo cơ hội để người học hỏi bài tập.","allowedGoalCodes":["HOMEWORK"],"next":"END","suggestedReplies":["宿題を教えてください。","宿題はありますか。"]}],"endings":{"success":{"code":"SUCCESS","title":"Đã báo nghỉ học","message":"わかりました。お大事に。宿題は後で送ります。","condition":"all_objectives","nextAction":"SHOW_RESULT"}}}',
     '{"vocabulary":[{"term":"体調","reading":"たいちょう","meaning":"tình trạng sức khỏe","example":"体調が悪いです。","jlpt":"N4"},{"term":"休む","reading":"やすむ","meaning":"nghỉ","example":"今日は学校を休みます。","jlpt":"N5"},{"term":"宿題","reading":"しゅくだい","meaning":"bài tập về nhà","example":"宿題はありますか。","jlpt":"N5"},{"term":"後で","reading":"あとで","meaning":"sau đó","example":"後で見ます。","jlpt":"N5"}],"grammar":[{"pattern":"〜ので、〜","explanation":"Nêu lý do nhẹ nhàng, lịch sự.","example":"体調が悪いので、休みます。"},{"pattern":"〜を教えてください","explanation":"Nhờ ai đó cho biết/hướng dẫn.","example":"宿題を教えてください。"}],"exampleDialogue":[{"speaker":"ai","text":"はい、先生です。どちらさまですか。","translation":"Vâng, cô đây. Ai gọi vậy?"},{"speaker":"learner","text":"ミンです。今日は体調が悪いので、休みます。","translation":"Em là Minh. Hôm nay em không khỏe nên xin nghỉ."},{"speaker":"ai","text":"わかりました。宿題は後でクラスに送ります。","translation":"Cô hiểu rồi. Cô sẽ gửi bài tập vào lớp sau."}]}', NULL, 1),
    (10, 8, 19, 20, 1, 'Nhận phòng khách sạn', 'hotel-check-in',
     'Nói tên đặt phòng, xác nhận số đêm lưu trú, xuất trình hộ chiếu và hỏi giờ ăn sáng/Wi-Fi.', 'N5', 'EASY', 10,
     'Bạn đến khách sạn sau chuyến bay và có đặt phòng trước bằng tên của mình.', 'Quầy lễ tân khách sạn', 'Buổi tối',
     '{"version":1,"initialState":"RESERVATION","goals":[{"code":"RESERVATION","title":"Nêu tên đặt phòng","description":"Nói đã đặt phòng và nêu tên.","type":"MISSION","displayOrder":1,"required":true,"conditions":[{"type":"KEYWORD","value":"予約|です"}]},{"code":"STAY","title":"Xác nhận số đêm","description":"Xác nhận số đêm hoặc ngày trả phòng.","type":"MISSION","displayOrder":2,"required":true,"conditions":[{"type":"KEYWORD","value":"泊|一泊|二泊"}]},{"code":"INFO","title":"Hỏi thông tin thiết yếu","description":"Hỏi giờ ăn sáng hoặc Wi-Fi.","type":"MISSION","displayOrder":3,"required":true,"conditions":[{"type":"KEYWORD","value":"朝食|Wi-Fi|何時"}]}],"states":[{"code":"RESERVATION","name":"Xác nhận đặt phòng","prompt":"Chào khách, hỏi có đặt phòng trước không và tên khách.","allowedGoalCodes":["RESERVATION"],"next":"STAY","suggestedReplies":["予約しています。ミンです。"]},{"code":"STAY","name":"Xác nhận thời gian lưu trú","prompt":"Xác nhận số đêm, ngày trả phòng và xin hộ chiếu.","allowedGoalCodes":["STAY"],"next":"INFO","suggestedReplies":["二泊です。","パスポートです。"]},{"code":"INFO","name":"Hỏi thông tin khách sạn","prompt":"Trao chìa khóa, mời khách hỏi về bữa sáng hoặc Wi-Fi.","allowedGoalCodes":["INFO"],"next":"END","suggestedReplies":["朝食は何時ですか。","Wi-Fiはありますか。"]}],"endings":{"success":{"code":"SUCCESS","title":"Nhận phòng thành công","message":"こちらが鍵です。ごゆっくりお過ごしください。","condition":"all_objectives","nextAction":"SHOW_RESULT"}}}',
     '{"vocabulary":[{"term":"予約","reading":"よやく","meaning":"đặt trước","example":"予約しています。","jlpt":"N4"},{"term":"泊","reading":"はく","meaning":"đêm lưu trú","example":"二泊です。","jlpt":"N4"},{"term":"朝食","reading":"ちょうしょく","meaning":"bữa sáng","example":"朝食は何時ですか。","jlpt":"N4"},{"term":"鍵","reading":"かぎ","meaning":"chìa khóa","example":"鍵をお願いします。","jlpt":"N5"}],"grammar":[{"pattern":"〜ています","explanation":"Nói một việc đã được thực hiện và vẫn có hiệu lực, như đặt phòng trước.","example":"予約しています。"},{"pattern":"〜は何時ですか","explanation":"Hỏi giờ hoạt động/dịch vụ.","example":"朝食は何時ですか。"}],"exampleDialogue":[{"speaker":"ai","text":"いらっしゃいませ。ご予約はありますか。","translation":"Xin chào. Bạn có đặt phòng trước không?"},{"speaker":"learner","text":"はい、予約しています。ミンです。二泊です。","translation":"Có, tôi đã đặt trước. Tôi là Minh. Tôi ở 2 đêm."},{"speaker":"ai","text":"ありがとうございます。朝食は七時から十時までです。","translation":"Cảm ơn bạn. Bữa sáng từ 7 đến 10 giờ."}]}', NULL, 1)
ON DUPLICATE KEY UPDATE
    category_id = VALUES(category_id), ai_role_id = VALUES(ai_role_id), learner_role_id = VALUES(learner_role_id),
    prompt_template_id = VALUES(prompt_template_id), title = VALUES(title), slug = VALUES(slug),
    description = VALUES(description), jlpt_level = VALUES(jlpt_level), difficulty = VALUES(difficulty),
    estimated_minutes = VALUES(estimated_minutes), background = VALUES(background), location = VALUES(location),
    time_of_day = VALUES(time_of_day), flow_json = VALUES(flow_json),
    learning_content_json = VALUES(learning_content_json), thumbnail_url = VALUES(thumbnail_url), is_active = VALUES(is_active);

-- Mỗi kịch bản dùng một ảnh nền minh họa riêng trên trang hội thoại.
UPDATE scenario
SET thumbnail_url = CASE id
    WHEN 1 THEN '/images/scenarios/restaurant-order-and-pay.png'
    WHEN 2 THEN '/images/scenarios/first-meeting-introduction.png'
    WHEN 3 THEN '/images/scenarios/station-directions-and-transfer.png'
    WHEN 4 THEN '/images/scenarios/convenience-store-checkout.png'
    WHEN 5 THEN '/images/scenarios/clothes-shopping-size-fitting.png'
    WHEN 6 THEN '/images/scenarios/clinic-appointment.png'
    WHEN 7 THEN '/images/scenarios/pharmacy-cold-medicine.png'
    WHEN 8 THEN '/images/scenarios/office-reschedule-meeting.png'
    WHEN 9 THEN '/images/scenarios/school-absence-and-homework.png'
    WHEN 10 THEN '/images/scenarios/hotel-check-in.png'
    ELSE thumbnail_url
END
WHERE id BETWEEN 1 AND 10;

-- Lời dẫn chuyển cảnh do giao diện hiển thị, không phải lời thoại của AI.
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.states[1].transitionText', 'Sau khi được dẫn tới bàn…',
    '$.states[2].transitionText', 'Sau khi bạn chọn món…',
    '$.states[3].transitionText', 'Sau khi dùng bữa…') WHERE id = 1;
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.states[1].transitionText', 'Sau phần chào hỏi ban đầu…',
    '$.states[2].transitionText', 'Khi cuộc trò chuyện trở nên thoải mái hơn…') WHERE id = 2;
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.states[1].transitionText', 'Sau khi bạn nêu điểm đến…',
    '$.states[2].transitionText', 'Sau khi xác nhận tuyến tàu…') WHERE id = 3;
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.states[1].transitionText', 'Tại quầy thanh toán…',
    '$.states[2].transitionText', 'Sau khi nhân viên chuẩn bị món ăn…') WHERE id = 4;
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.states[1].transitionText', 'Sau khi tìm được mẫu áo phù hợp…',
    '$.states[2].transitionText', 'Một lát sau, tại phòng thử đồ…') WHERE id = 5;
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.states[1].transitionText', 'Trong lúc làm thủ tục tiếp nhận…',
    '$.states[2].transitionText', 'Sau khi cung cấp thông tin triệu chứng…') WHERE id = 6;
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.states[1].transitionText', 'Sau khi mô tả triệu chứng…',
    '$.states[2].transitionText', 'Sau khi chọn được loại thuốc phù hợp…') WHERE id = 7;
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.states[1].transitionText', 'Sau khi trình bày việc trùng lịch…',
    '$.states[2].transitionText', 'Sau khi thống nhất giờ họp mới…') WHERE id = 8;
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.states[1].transitionText', 'Sau khi xác nhận tên và lớp…',
    '$.states[2].transitionText', 'Sau khi giáo viên đồng ý cho nghỉ…') WHERE id = 9;
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.states[1].transitionText', 'Sau khi tìm thấy thông tin đặt phòng…',
    '$.states[2].transitionText', 'Sau khi hoàn tất thủ tục nhận phòng…') WHERE id = 10;

INSERT IGNORE INTO evaluation_rule
    (id, rule_type, code, name, description, priority, max_score, weight, is_enabled)
VALUES
    (1, 'FEEDBACK', 'grammar_basic', 'Ngữ pháp cơ bản', 'Kiểm tra trợ từ và mẫu câu cơ bản.', 1, NULL, NULL, 1),
    (2, 'SCORING', 'overall_fluency', 'Độ trôi chảy', 'Chấm điểm mức độ hoàn thành hội thoại.', 1, 100, 100, 1);

INSERT IGNORE INTO scenario_evaluation_rule (scenario_id, rule_id) VALUES
    (1, 1), (1, 2), (2, 1), (2, 2), (3, 1), (3, 2), (4, 1), (4, 2),
    (5, 1), (5, 2), (6, 1), (6, 2), (7, 1), (7, 2), (8, 1), (8, 2),
    (9, 1), (9, 2), (10, 1), (10, 2);

-- =============================================================================
-- 3) Từ điển dùng chung: tag, kanji mẫu, từ vựng
--    Không dùng ID cứng: tra theo mã/chữ để không đè lên dữ liệu đã có.
-- =============================================================================
INSERT IGNORE INTO tag (code, name, tag_type) VALUES
    ('VIDEO-N5',               'N5',                 'VIDEO'),
    ('VIDEO-RESTAURANT',       'Restaurant',         'VIDEO'),
    ('VIDEO-LISTENING',        'Listening',          'VIDEO');

INSERT IGNORE INTO kanji (`character`, unicode_code, meaning_vi, han_viet, jlpt_level) VALUES
    ('海', '6D77', 'biển',         'HẢI',  'N4'),
    ('会', '4F1A', 'gặp; hội họp', 'HỘI',  'N4'),
    ('学', '5B66', 'học',          'HỌC',  'N5');

INSERT IGNORE INTO kanji_reading (kanji_id, reading_type, reading_text, is_common)
SELECT k.id, r.reading_type, r.reading_text, 1
FROM (
    SELECT '海' AS ch, 'ON' AS reading_type, 'カイ' AS reading_text
    UNION ALL SELECT '海', 'KUN', 'うみ'
    UNION ALL SELECT '会', 'ON',  'カイ'
    UNION ALL SELECT '会', 'ON',  'エ'
    UNION ALL SELECT '会', 'KUN', 'あう'
    UNION ALL SELECT '学', 'ON',  'ガク'
    UNION ALL SELECT '学', 'KUN', 'まなぶ'
) r
JOIN kanji k ON k.`character` = r.ch COLLATE utf8mb4_unicode_ci;

INSERT IGNORE INTO vocabulary_entry
    (expression, normalized_expression, reading, normalized_reading, meaning, word_type, jlpt_level, example_ja, example_vi)
VALUES
    ('学校', '学校', 'がっこう', 'がっこう', 'trường học', 'NOUN', 'N5', '学校へ行きます。', 'Tôi đi đến trường.'),
    ('先生', '先生', 'せんせい', 'せんせい', 'giáo viên', 'NOUN', 'N5', '先生に聞きます。', 'Tôi hỏi giáo viên.'),
    ('お願いします', 'お願いします', 'おねがいします', 'おねがいします', 'làm ơn / nhờ bạn', 'PHRASE', 'N5', 'これをお願いします。', 'Làm ơn cho tôi món này.'),
    ('海外', '海外', 'かいがい', 'かいがい', 'nước ngoài', 'NOUN', 'N4', '海外で働きます。', 'Tôi làm việc ở nước ngoài.'),
    ('経験', '経験', 'けいけん', 'けいけん', 'kinh nghiệm', 'NOUN', 'N4', NULL, NULL);

-- Từ/cụm giao tiếp dùng để tách câu và tra cứu trong AI Theo Kịch Bản.
-- Thuật toán ưu tiên cụm dài nhất, ví dụ 何名様 thay vì 何 + 名 + 様.
INSERT INTO vocabulary_entry
    (expression, normalized_expression, reading, normalized_reading, meaning,
     word_type, jlpt_level, example_ja, example_vi, usage_scope, dictionary_status)
VALUES
    ('いらっしゃいませ', 'いらっしゃいませ', 'いらっしゃいませ', 'いらっしゃいませ', 'Xin chào quý khách', 'PHRASE', 'N5', 'いらっしゃいませ。何名様ですか。', 'Xin chào quý khách. Quý khách có mấy người ạ?', 'BOTH', 'PUBLISHED'),
    ('何名様', '何名様', 'なんめいさま', 'なんめいさま', 'quý khách có mấy người', 'PHRASE', 'N4', '何名様ですか。', 'Quý khách có mấy người ạ?', 'BOTH', 'PUBLISHED'),
    ('はじめまして', 'はじめまして', 'はじめまして', 'はじめまして', 'rất vui được gặp lần đầu', 'PHRASE', 'N5', 'はじめまして。ミンです。', 'Rất vui được gặp bạn. Tôi là Minh.', 'BOTH', 'PUBLISHED'),
    ('お名前は何ですか', 'お名前は何ですか', 'おなまえはなんですか', 'おなまえはなんですか', 'bạn tên là gì', 'PHRASE', 'N5', 'お名前は何ですか。', 'Bạn tên là gì?', 'BOTH', 'PUBLISHED'),
    ('どうされましたか', 'どうされましたか', 'どうされましたか', 'どうされましたか', 'bạn cần hỗ trợ gì / bạn thấy thế nào', 'PHRASE', 'N4', 'はい、どうされましたか。', 'Vâng, bạn cần hỗ trợ gì ạ?', 'BOTH', 'PUBLISHED'),
    ('渋谷駅', '渋谷駅', 'しぶやえき', 'しぶやえき', 'ga Shibuya', 'PROPER_NOUN', 'N5', '渋谷駅に行きたいです。', 'Tôi muốn đến ga Shibuya.', 'BOTH', 'PUBLISHED'),
    ('山手線', '山手線', 'やまのてせん', 'やまのてせん', 'tuyến Yamanote', 'PROPER_NOUN', 'N4', '山手線に乗ります。', 'Tôi đi tuyến Yamanote.', 'BOTH', 'PUBLISHED'),
    ('何番線', '何番線', 'なんばんせん', 'なんばんせん', 'sân ga số mấy', 'PHRASE', 'N4', '何番線ですか。', 'Sân ga số mấy ạ?', 'BOTH', 'PUBLISHED'),
    ('温めますか', '温めますか', 'あたためますか', 'あたためますか', 'có hâm nóng không', 'PHRASE', 'N4', 'お弁当、温めますか。', 'Bạn có muốn hâm nóng cơm hộp không?', 'BOTH', 'PUBLISHED'),
    ('レジ袋', 'レジ袋', 'レジぶくろ', 'レジぶくろ', 'túi đựng hàng tại quầy', 'NOUN', 'N4', 'レジ袋はご利用ですか。', 'Bạn có dùng túi đựng hàng không?', 'BOTH', 'PUBLISHED'),
    ('何をお探しですか', '何をお探しですか', 'なにをおさがしですか', 'なにをおさがしですか', 'bạn đang tìm gì', 'PHRASE', 'N4', '何をお探しですか。', 'Bạn đang tìm gì ạ?', 'BOTH', 'PUBLISHED'),
    ('試着してもいいですか', '試着してもいいですか', 'しちゃくしてもいいですか', 'しちゃくしてもいいですか', 'tôi thử đồ được không', 'PHRASE', 'N4', 'このジャケットを試着してもいいですか。', 'Tôi thử chiếc áo khoác này được không?', 'BOTH', 'PUBLISHED'),
    ('今日はどうされましたか', '今日はどうされましたか', 'きょうはどうされましたか', 'きょうはどうされましたか', 'hôm nay bạn gặp vấn đề gì', 'PHRASE', 'N4', '今日はどうされましたか。', 'Hôm nay bạn gặp vấn đề gì?', 'BOTH', 'PUBLISHED'),
    ('喉が痛い', '喉が痛い', 'のどがいたい', 'のどがいたい', 'đau họng', 'PHRASE', 'N4', '昨日から喉が痛いです。', 'Tôi đau họng từ hôm qua.', 'BOTH', 'PUBLISHED'),
    ('昨日から', '昨日から', 'きのうから', 'きのうから', 'từ hôm qua', 'PHRASE', 'N5', '昨日からせきが出ます。', 'Tôi bị ho từ hôm qua.', 'BOTH', 'PUBLISHED'),
    ('どのような症状ですか', 'どのような症状ですか', 'どのようなしょうじょうですか', 'どのようなしょうじょうですか', 'bạn có triệu chứng như thế nào', 'PHRASE', 'N3', 'どのような症状ですか。', 'Bạn có triệu chứng như thế nào?', 'BOTH', 'PUBLISHED'),
    ('一日三回', '一日三回', 'いちにちさんかい', 'いちにちさんかい', 'ba lần một ngày', 'PHRASE', 'N4', '一日三回、食後に飲んでください。', 'Hãy uống ba lần một ngày sau bữa ăn.', 'BOTH', 'PUBLISHED'),
    ('明日の会議', '明日の会議', 'あしたのかいぎ', 'あしたのかいぎ', 'cuộc họp ngày mai', 'PHRASE', 'N4', '明日の会議は二時からです。', 'Cuộc họp ngày mai bắt đầu lúc hai giờ.', 'BOTH', 'PUBLISHED'),
    ('午後三時', '午後三時', 'ごごさんじ', 'ごごさんじ', 'ba giờ chiều', 'TIME_PHRASE', 'N5', '午後三時はいかがですか。', 'Ba giờ chiều được không?', 'BOTH', 'PUBLISHED'),
    ('大丈夫ですか', '大丈夫ですか', 'だいじょうぶですか', 'だいじょうぶですか', 'có ổn không', 'PHRASE', 'N5', '二時からで大丈夫ですか。', 'Bắt đầu từ hai giờ có ổn không?', 'BOTH', 'PUBLISHED'),
    ('体調が悪い', '体調が悪い', 'たいちょうがわるい', 'たいちょうがわるい', 'tình trạng sức khỏe không tốt', 'PHRASE', 'N4', '今日は体調が悪いです。', 'Hôm nay tôi không khỏe.', 'BOTH', 'PUBLISHED'),
    ('宿題を教えてください', '宿題を教えてください', 'しゅくだいをおしえてください', 'しゅくだいをおしえてください', 'hãy cho tôi biết bài tập', 'PHRASE', 'N5', '宿題を教えてください。', 'Hãy cho tôi biết bài tập.', 'BOTH', 'PUBLISHED'),
    ('どちらさまですか', 'どちらさまですか', 'どちらさまですか', 'どちらさまですか', 'xin hỏi ai đang gọi', 'PHRASE', 'N4', 'はい、どちらさまですか。', 'Vâng, xin hỏi ai đang gọi?', 'BOTH', 'PUBLISHED'),
    ('ご予約はありますか', 'ご予約はありますか', 'ごよやくはありますか', 'ごよやくはありますか', 'bạn có đặt trước không', 'PHRASE', 'N4', 'ご予約はありますか。', 'Bạn có đặt phòng trước không?', 'BOTH', 'PUBLISHED'),
    ('二泊', '二泊', 'にはく', 'にはく', 'hai đêm lưu trú', 'COUNTER_PHRASE', 'N4', '二泊です。', 'Tôi ở hai đêm.', 'BOTH', 'PUBLISHED'),
    ('朝食は何時ですか', '朝食は何時ですか', 'ちょうしょくはなんじですか', 'ちょうしょくはなんじですか', 'bữa sáng lúc mấy giờ', 'PHRASE', 'N4', '朝食は何時ですか。', 'Bữa sáng lúc mấy giờ?', 'BOTH', 'PUBLISHED')
ON DUPLICATE KEY UPDATE
    reading = VALUES(reading), normalized_reading = VALUES(normalized_reading),
    meaning = VALUES(meaning), word_type = VALUES(word_type), jlpt_level = VALUES(jlpt_level),
    example_ja = VALUES(example_ja), example_vi = VALUES(example_vi),
    usage_scope = VALUES(usage_scope), dictionary_status = VALUES(dictionary_status);

-- =============================================================================
-- 4) Giáo trình Onyomi (sinh từ scripts/onyomi/onyomi_rules.json)
-- =============================================================================
-- Giáo trình Onyomi (Hán Việt → âm On). SINH TỰ ĐỘNG từ scripts/onyomi/onyomi_rules.json:
-- sửa dữ liệu ở file JSON rồi chạy lại scripts/onyomi/generate_onyomi.py, không sửa tay file này.
-- Chạy lại được: giáo trình/unit/bài/quy tắc được cập nhật tại chỗ theo khoá tự nhiên; ví dụ,
-- mẫu và câu hỏi của các bài này được xoá rồi tạo lại (kèm các lượt làm quiz cũ của chúng).

SET @onyomi_book = (SELECT id FROM textbook WHERE title = 'Amon / Âm On-Kun' ORDER BY id LIMIT 1);
INSERT INTO textbook (title, description, jlpt_level, status, version)
SELECT 'Amon / Âm On-Kun', 'Giáo trình luyện đoán âm On của chữ Hán từ âm Hán Việt: âm đầu, âm cuối và vần.', 'N5', 'PUBLISHED', 1 FROM DUAL WHERE @onyomi_book IS NULL;
SET @onyomi_book = COALESCE(@onyomi_book, LAST_INSERT_ID());
UPDATE textbook SET description = 'Giáo trình luyện đoán âm On của chữ Hán từ âm Hán Việt: âm đầu, âm cuối và vần.', status = 'PUBLISHED' WHERE id = @onyomi_book;

INSERT INTO tag (code, name, tag_type) VALUES
    ('KNOWLEDGE-ONYOMI-INITIAL', 'Quy tắc âm đầu Onyomi', 'KNOWLEDGE'),
    ('KNOWLEDGE-ONYOMI-FINAL', 'Quy tắc âm cuối Onyomi', 'KNOWLEDGE'),
    ('KNOWLEDGE-ONYOMI-RHYME', 'Quy tắc chuyển vần Onyomi', 'KNOWLEDGE')
ON DUPLICATE KEY UPDATE name = VALUES(name), tag_type = VALUES(tag_type);
SET @onyomi_tag_1 = (SELECT id FROM tag WHERE code = 'KNOWLEDGE-ONYOMI-INITIAL');
SET @onyomi_tag_2 = (SELECT id FROM tag WHERE code = 'KNOWLEDGE-ONYOMI-FINAL');
SET @onyomi_tag_3 = (SELECT id FROM tag WHERE code = 'KNOWLEDGE-ONYOMI-RHYME');

INSERT INTO unit (textbook_id, title, description, order_index) VALUES
    (@onyomi_book, 'Giai đoạn 1: Âm đầu', 'Phụ âm đầu Hán Việt thường tương ứng với hàng nào trong âm On.', 1),
    (@onyomi_book, 'Giai đoạn 2: Âm cuối', 'Phụ âm cuối Hán Việt (-m, -n, -c, -ch, -t, -p) để lại dấu vết ở cuối âm On.', 2),
    (@onyomi_book, 'Giai đoạn 3: Chuyển vần', 'Các vần Hán Việt thường gặp và vần âm On tương ứng.', 3)
ON DUPLICATE KEY UPDATE title = VALUES(title), description = VALUES(description);
SET @onyomi_unit_1 = (SELECT id FROM unit WHERE textbook_id = @onyomi_book AND order_index = 1);
SET @onyomi_unit_2 = (SELECT id FROM unit WHERE textbook_id = @onyomi_book AND order_index = 2);
SET @onyomi_unit_3 = (SELECT id FROM unit WHERE textbook_id = @onyomi_book AND order_index = 3);

INSERT INTO lesson (unit_id, knowledge_tag_id, title, description, order_index, estimated_minutes,
                    lesson_type, skill, situation_tag, is_review, status) VALUES
    (@onyomi_unit_1, @onyomi_tag_1, 'Âm đầu C, K, QU → K', 'C, K, QU thường thành hàng カ (K).', 1, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_1, @onyomi_tag_1, 'Âm đầu H, NG → K/G', 'H thường thành カ/ガ, NG thành ガ.', 2, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_1, @onyomi_tag_1, 'Âm đầu L, N, NH → R/N/J', 'L thành ラ, N giữ ナ, NH thành ナ hoặc ジ.', 3, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_1, @onyomi_tag_1, 'Âm đầu S, T, X → S/SH', 'S, T, X đều dồn về hàng サ (S/SH).', 4, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_1, @onyomi_tag_1, 'Âm đầu TH, TR → S/T/CH', 'TH thành サ hoặc タ, TR thành チ hoặc タ.', 5, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_1, @onyomi_tag_1, 'Âm đầu KH, GI → K/G', 'KH và GI cũng dồn về hàng カ/ガ.', 6, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_1, @onyomi_tag_1, 'Âm đầu B, PH → H/B', 'B và PH thường thành hàng ハ; B đôi khi giữ バ.', 7, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_1, @onyomi_tag_1, 'Âm đầu M, V → M/B', 'M thành マ hoặc バ; V thành バ, マ hoặc mất phụ âm.', 8, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_1, @onyomi_tag_1, 'Âm đầu Đ, D → T/D/Y', 'Đ thành タ/ダ, D thành hàng ヤ.', 9, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_1, @onyomi_tag_1, 'Âm đầu CH và nguyên âm đầu', 'CH thành サ/シャ; chữ bắt đầu bằng nguyên âm giữ nguyên âm hoặc thành ヤ.', 10, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_2, @onyomi_tag_2, 'Âm cuối M, N → ン', '-M và -N đều thành ン.', 1, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_2, @onyomi_tag_2, 'Âm cuối C → ク', '-C thường thành ク.', 2, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_2, @onyomi_tag_2, 'Âm cuối CH → キ/ク', '-ICH/-ÊCH thành キ, -ACH thành ク.', 3, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_2, @onyomi_tag_2, 'Âm cuối T → ツ/チ, P → ウ', '-T thành ツ hoặc チ; -P thành trường âm ウ.', 4, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_3, @onyomi_tag_3, 'Vần AN, AM → アン', 'AN và AM đều thành アン.', 1, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_3, @onyomi_tag_3, 'Vần ÂN, ÂM → イン', 'ÂN và ÂM thường thành イン.', 2, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_3, @onyomi_tag_3, 'Vần IÊN, IÊM → エン', 'IÊN và IÊM thành エン.', 3, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_3, @onyomi_tag_3, 'Vần ANG, ƯƠNG, ÔNG → オウ', 'Các vần -NG thường thành trường âm オウ (đôi khi ウウ).', 4, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED'),
    (@onyomi_unit_3, @onyomi_tag_3, 'Vần ANH, ÊNH, INH → エイ', 'Các vần -NH thường thành エイ.', 5, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED')
ON DUPLICATE KEY UPDATE knowledge_tag_id = VALUES(knowledge_tag_id), title = VALUES(title),
    description = VALUES(description), estimated_minutes = VALUES(estimated_minutes),
    lesson_type = VALUES(lesson_type), skill = VALUES(skill), status = VALUES(status);
SET @onyomi_lesson_1_1 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_1 AND order_index = 1);
SET @onyomi_lesson_1_2 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_1 AND order_index = 2);
SET @onyomi_lesson_1_3 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_1 AND order_index = 3);
SET @onyomi_lesson_1_4 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_1 AND order_index = 4);
SET @onyomi_lesson_1_5 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_1 AND order_index = 5);
SET @onyomi_lesson_1_6 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_1 AND order_index = 6);
SET @onyomi_lesson_1_7 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_1 AND order_index = 7);
SET @onyomi_lesson_1_8 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_1 AND order_index = 8);
SET @onyomi_lesson_1_9 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_1 AND order_index = 9);
SET @onyomi_lesson_1_10 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_1 AND order_index = 10);
SET @onyomi_lesson_2_1 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_2 AND order_index = 1);
SET @onyomi_lesson_2_2 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_2 AND order_index = 2);
SET @onyomi_lesson_2_3 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_2 AND order_index = 3);
SET @onyomi_lesson_2_4 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_2 AND order_index = 4);
SET @onyomi_lesson_3_1 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_3 AND order_index = 1);
SET @onyomi_lesson_3_2 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_3 AND order_index = 2);
SET @onyomi_lesson_3_3 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_3 AND order_index = 3);
SET @onyomi_lesson_3_4 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_3 AND order_index = 4);
SET @onyomi_lesson_3_5 = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_3 AND order_index = 5);
SET @onyomi_first_lesson = @onyomi_lesson_1_1;

SET @onyomi_quiz_1_1 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_1_1 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_1_1, NULL, 'Luyện tập: Âm đầu C, K, QU → K', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_1_1 IS NULL;
SET @onyomi_quiz_1_1 = COALESCE(@onyomi_quiz_1_1, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm đầu C, K, QU → K', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_1_1;
SET @onyomi_quiz_1_2 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_1_2 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_1_2, NULL, 'Luyện tập: Âm đầu H, NG → K/G', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_1_2 IS NULL;
SET @onyomi_quiz_1_2 = COALESCE(@onyomi_quiz_1_2, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm đầu H, NG → K/G', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_1_2;
SET @onyomi_quiz_1_3 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_1_3 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_1_3, NULL, 'Luyện tập: Âm đầu L, N, NH → R/N/J', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_1_3 IS NULL;
SET @onyomi_quiz_1_3 = COALESCE(@onyomi_quiz_1_3, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm đầu L, N, NH → R/N/J', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_1_3;
SET @onyomi_quiz_1_4 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_1_4 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_1_4, NULL, 'Luyện tập: Âm đầu S, T, X → S/SH', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_1_4 IS NULL;
SET @onyomi_quiz_1_4 = COALESCE(@onyomi_quiz_1_4, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm đầu S, T, X → S/SH', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_1_4;
SET @onyomi_quiz_1_5 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_1_5 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_1_5, NULL, 'Luyện tập: Âm đầu TH, TR → S/T/CH', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_1_5 IS NULL;
SET @onyomi_quiz_1_5 = COALESCE(@onyomi_quiz_1_5, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm đầu TH, TR → S/T/CH', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_1_5;
SET @onyomi_quiz_1_6 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_1_6 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_1_6, NULL, 'Luyện tập: Âm đầu KH, GI → K/G', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_1_6 IS NULL;
SET @onyomi_quiz_1_6 = COALESCE(@onyomi_quiz_1_6, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm đầu KH, GI → K/G', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_1_6;
SET @onyomi_quiz_1_7 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_1_7 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_1_7, NULL, 'Luyện tập: Âm đầu B, PH → H/B', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_1_7 IS NULL;
SET @onyomi_quiz_1_7 = COALESCE(@onyomi_quiz_1_7, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm đầu B, PH → H/B', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_1_7;
SET @onyomi_quiz_1_8 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_1_8 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_1_8, NULL, 'Luyện tập: Âm đầu M, V → M/B', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_1_8 IS NULL;
SET @onyomi_quiz_1_8 = COALESCE(@onyomi_quiz_1_8, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm đầu M, V → M/B', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_1_8;
SET @onyomi_quiz_1_9 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_1_9 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_1_9, NULL, 'Luyện tập: Âm đầu Đ, D → T/D/Y', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_1_9 IS NULL;
SET @onyomi_quiz_1_9 = COALESCE(@onyomi_quiz_1_9, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm đầu Đ, D → T/D/Y', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_1_9;
SET @onyomi_quiz_1_10 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_1_10 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_1_10, NULL, 'Luyện tập: Âm đầu CH và nguyên âm đầu', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_1_10 IS NULL;
SET @onyomi_quiz_1_10 = COALESCE(@onyomi_quiz_1_10, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm đầu CH và nguyên âm đầu', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_1_10;
SET @onyomi_quiz_2_1 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_2_1 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_2_1, NULL, 'Luyện tập: Âm cuối M, N → ン', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_2_1 IS NULL;
SET @onyomi_quiz_2_1 = COALESCE(@onyomi_quiz_2_1, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm cuối M, N → ン', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_2_1;
SET @onyomi_quiz_2_2 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_2_2 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_2_2, NULL, 'Luyện tập: Âm cuối C → ク', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_2_2 IS NULL;
SET @onyomi_quiz_2_2 = COALESCE(@onyomi_quiz_2_2, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm cuối C → ク', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_2_2;
SET @onyomi_quiz_2_3 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_2_3 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_2_3, NULL, 'Luyện tập: Âm cuối CH → キ/ク', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_2_3 IS NULL;
SET @onyomi_quiz_2_3 = COALESCE(@onyomi_quiz_2_3, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm cuối CH → キ/ク', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_2_3;
SET @onyomi_quiz_2_4 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_2_4 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_2_4, NULL, 'Luyện tập: Âm cuối T → ツ/チ, P → ウ', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_2_4 IS NULL;
SET @onyomi_quiz_2_4 = COALESCE(@onyomi_quiz_2_4, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Âm cuối T → ツ/チ, P → ウ', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_2_4;
SET @onyomi_quiz_3_1 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_3_1 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_3_1, NULL, 'Luyện tập: Vần AN, AM → アン', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_3_1 IS NULL;
SET @onyomi_quiz_3_1 = COALESCE(@onyomi_quiz_3_1, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Vần AN, AM → アン', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_3_1;
SET @onyomi_quiz_3_2 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_3_2 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_3_2, NULL, 'Luyện tập: Vần ÂN, ÂM → イン', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_3_2 IS NULL;
SET @onyomi_quiz_3_2 = COALESCE(@onyomi_quiz_3_2, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Vần ÂN, ÂM → イン', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_3_2;
SET @onyomi_quiz_3_3 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_3_3 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_3_3, NULL, 'Luyện tập: Vần IÊN, IÊM → エン', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_3_3 IS NULL;
SET @onyomi_quiz_3_3 = COALESCE(@onyomi_quiz_3_3, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Vần IÊN, IÊM → エン', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_3_3;
SET @onyomi_quiz_3_4 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_3_4 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_3_4, NULL, 'Luyện tập: Vần ANG, ƯƠNG, ÔNG → オウ', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_3_4 IS NULL;
SET @onyomi_quiz_3_4 = COALESCE(@onyomi_quiz_3_4, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Vần ANG, ƯƠNG, ÔNG → オウ', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_3_4;
SET @onyomi_quiz_3_5 = (SELECT id FROM assessment_quiz WHERE lesson_id = @onyomi_lesson_3_5 AND quiz_type IN ('LESSON', 'RULE_PRACTICE') ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT @onyomi_lesson_3_5, NULL, 'Luyện tập: Vần ANH, ÊNH, INH → エイ', 'LESSON', 'PUBLISHED' FROM DUAL WHERE @onyomi_quiz_3_5 IS NULL;
SET @onyomi_quiz_3_5 = COALESCE(@onyomi_quiz_3_5, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Luyện tập: Vần ANH, ÊNH, INH → エイ', quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = @onyomi_quiz_3_5;
SET @onyomi_final_1 = (SELECT MIN(id) FROM assessment_quiz WHERE unit_id = @onyomi_unit_1 AND quiz_type = 'UNIT_FINAL');
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT NULL, @onyomi_unit_1, 'Kiểm tra cuối: Giai đoạn 1: Âm đầu', 'UNIT_FINAL', 'PUBLISHED' FROM DUAL WHERE @onyomi_final_1 IS NULL;
SET @onyomi_final_1 = COALESCE(@onyomi_final_1, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Kiểm tra cuối: Giai đoạn 1: Âm đầu', status = 'PUBLISHED' WHERE id = @onyomi_final_1;
SET @onyomi_final_2 = (SELECT MIN(id) FROM assessment_quiz WHERE unit_id = @onyomi_unit_2 AND quiz_type = 'UNIT_FINAL');
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT NULL, @onyomi_unit_2, 'Kiểm tra cuối: Giai đoạn 2: Âm cuối', 'UNIT_FINAL', 'PUBLISHED' FROM DUAL WHERE @onyomi_final_2 IS NULL;
SET @onyomi_final_2 = COALESCE(@onyomi_final_2, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Kiểm tra cuối: Giai đoạn 2: Âm cuối', status = 'PUBLISHED' WHERE id = @onyomi_final_2;
SET @onyomi_final_3 = (SELECT MIN(id) FROM assessment_quiz WHERE unit_id = @onyomi_unit_3 AND quiz_type = 'UNIT_FINAL');
INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)
SELECT NULL, @onyomi_unit_3, 'Kiểm tra cuối: Giai đoạn 3: Chuyển vần', 'UNIT_FINAL', 'PUBLISHED' FROM DUAL WHERE @onyomi_final_3 IS NULL;
SET @onyomi_final_3 = COALESCE(@onyomi_final_3, LAST_INSERT_ID());
UPDATE assessment_quiz SET title = 'Kiểm tra cuối: Giai đoạn 3: Chuyển vần', status = 'PUBLISHED' WHERE id = @onyomi_final_3;

-- Xoá câu hỏi cũ của các bài này (kể cả bộ 450 câu cũ) cùng các lượt làm quiz trên chúng
-- (quiz luyện tập và bài kiểm tra cuối giai đoạn), rồi xoá hẳn các quiz Ghép từ cũ (RULE_PRACTICE):
-- phần Ghép từ đã bỏ, vì mọi câu hỏi giờ đều là đọc cả từ.
DELETE FROM assessment_attempt WHERE id IN (
    SELECT attempt_id FROM (SELECT link.attempt_id FROM assessment_quiz_attempt link
    JOIN assessment_quiz quiz ON quiz.id = link.quiz_id
    WHERE quiz.id IN (@onyomi_quiz_1_1, @onyomi_quiz_1_2, @onyomi_quiz_1_3, @onyomi_quiz_1_4, @onyomi_quiz_1_5, @onyomi_quiz_1_6, @onyomi_quiz_1_7, @onyomi_quiz_1_8, @onyomi_quiz_1_9, @onyomi_quiz_1_10, @onyomi_quiz_2_1, @onyomi_quiz_2_2, @onyomi_quiz_2_3, @onyomi_quiz_2_4, @onyomi_quiz_3_1, @onyomi_quiz_3_2, @onyomi_quiz_3_3, @onyomi_quiz_3_4, @onyomi_quiz_3_5, @onyomi_final_1, @onyomi_final_2, @onyomi_final_3) OR (quiz.quiz_type = 'RULE_PRACTICE' AND quiz.lesson_id IN (@onyomi_lesson_1_1, @onyomi_lesson_1_2, @onyomi_lesson_1_3, @onyomi_lesson_1_4, @onyomi_lesson_1_5, @onyomi_lesson_1_6, @onyomi_lesson_1_7, @onyomi_lesson_1_8, @onyomi_lesson_1_9, @onyomi_lesson_1_10, @onyomi_lesson_2_1, @onyomi_lesson_2_2, @onyomi_lesson_2_3, @onyomi_lesson_2_4, @onyomi_lesson_3_1, @onyomi_lesson_3_2, @onyomi_lesson_3_3, @onyomi_lesson_3_4, @onyomi_lesson_3_5))) old_attempts);
DELETE quiz FROM assessment_quiz quiz WHERE (quiz.quiz_type = 'RULE_PRACTICE' AND quiz.lesson_id IN (@onyomi_lesson_1_1, @onyomi_lesson_1_2, @onyomi_lesson_1_3, @onyomi_lesson_1_4, @onyomi_lesson_1_5, @onyomi_lesson_1_6, @onyomi_lesson_1_7, @onyomi_lesson_1_8, @onyomi_lesson_1_9, @onyomi_lesson_1_10, @onyomi_lesson_2_1, @onyomi_lesson_2_2, @onyomi_lesson_2_3, @onyomi_lesson_2_4, @onyomi_lesson_3_1, @onyomi_lesson_3_2, @onyomi_lesson_3_3, @onyomi_lesson_3_4, @onyomi_lesson_3_5));
DELETE FROM assessment_quiz_question WHERE quiz_id IN (@onyomi_quiz_1_1, @onyomi_quiz_1_2, @onyomi_quiz_1_3, @onyomi_quiz_1_4, @onyomi_quiz_1_5, @onyomi_quiz_1_6, @onyomi_quiz_1_7, @onyomi_quiz_1_8, @onyomi_quiz_1_9, @onyomi_quiz_1_10, @onyomi_quiz_2_1, @onyomi_quiz_2_2, @onyomi_quiz_2_3, @onyomi_quiz_2_4, @onyomi_quiz_3_1, @onyomi_quiz_3_2, @onyomi_quiz_3_3, @onyomi_quiz_3_4, @onyomi_quiz_3_5, @onyomi_final_1, @onyomi_final_2, @onyomi_final_3);
DELETE a FROM assessment_answer a JOIN assessment_question qn ON qn.id = a.question_id
 WHERE qn.quiz_id IN (@onyomi_quiz_1_1, @onyomi_quiz_1_2, @onyomi_quiz_1_3, @onyomi_quiz_1_4, @onyomi_quiz_1_5, @onyomi_quiz_1_6, @onyomi_quiz_1_7, @onyomi_quiz_1_8, @onyomi_quiz_1_9, @onyomi_quiz_1_10, @onyomi_quiz_2_1, @onyomi_quiz_2_2, @onyomi_quiz_2_3, @onyomi_quiz_2_4, @onyomi_quiz_3_1, @onyomi_quiz_3_2, @onyomi_quiz_3_3, @onyomi_quiz_3_4, @onyomi_quiz_3_5, @onyomi_final_1, @onyomi_final_2, @onyomi_final_3)
    OR qn.conversion_rule_id IN (SELECT id FROM conversion_rule WHERE lesson_id IN (@onyomi_lesson_1_1, @onyomi_lesson_1_2, @onyomi_lesson_1_3, @onyomi_lesson_1_4, @onyomi_lesson_1_5, @onyomi_lesson_1_6, @onyomi_lesson_1_7, @onyomi_lesson_1_8, @onyomi_lesson_1_9, @onyomi_lesson_1_10, @onyomi_lesson_2_1, @onyomi_lesson_2_2, @onyomi_lesson_2_3, @onyomi_lesson_2_4, @onyomi_lesson_3_1, @onyomi_lesson_3_2, @onyomi_lesson_3_3, @onyomi_lesson_3_4, @onyomi_lesson_3_5));
DELETE FROM assessment_question WHERE quiz_id IN (@onyomi_quiz_1_1, @onyomi_quiz_1_2, @onyomi_quiz_1_3, @onyomi_quiz_1_4, @onyomi_quiz_1_5, @onyomi_quiz_1_6, @onyomi_quiz_1_7, @onyomi_quiz_1_8, @onyomi_quiz_1_9, @onyomi_quiz_1_10, @onyomi_quiz_2_1, @onyomi_quiz_2_2, @onyomi_quiz_2_3, @onyomi_quiz_2_4, @onyomi_quiz_3_1, @onyomi_quiz_3_2, @onyomi_quiz_3_3, @onyomi_quiz_3_4, @onyomi_quiz_3_5, @onyomi_final_1, @onyomi_final_2, @onyomi_final_3)
    OR conversion_rule_id IN (SELECT id FROM conversion_rule WHERE lesson_id IN (@onyomi_lesson_1_1, @onyomi_lesson_1_2, @onyomi_lesson_1_3, @onyomi_lesson_1_4, @onyomi_lesson_1_5, @onyomi_lesson_1_6, @onyomi_lesson_1_7, @onyomi_lesson_1_8, @onyomi_lesson_1_9, @onyomi_lesson_1_10, @onyomi_lesson_2_1, @onyomi_lesson_2_2, @onyomi_lesson_2_3, @onyomi_lesson_2_4, @onyomi_lesson_3_1, @onyomi_lesson_3_2, @onyomi_lesson_3_3, @onyomi_lesson_3_4, @onyomi_lesson_3_5));

INSERT INTO conversion_rule (lesson_id, source_sound, target_sound, title, description,
                             example_breakdown, tip, note, display_order) VALUES
    (@onyomi_lesson_1_1, 'C', 'K', 'Âm đầu C → K', 'Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', 'Công (工) → コウ (kou)', 'Đổi C thành K rồi đọc: Công 工 → コウ, Cổ 古 → コ, Các 各 → カク.', 'C và K trong Hán Việt là cùng một âm “cờ”, chỉ khác cách viết: trước i, e, ê, y viết K (Kim, Kế, Kỳ), còn lại viết C (Công, Cổ, Ca). Vì vậy C → K và K → K thực chất là một quy tắc.', 1),
    (@onyomi_lesson_1_1, 'K', 'K', 'Âm đầu K → K', 'Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', 'Kim (金) → キン (kin)', 'K giữ nguyên là K: Kim 金 → キン, Kỳ 期 → キ, Kiến 見 → ケン.', 'Đôi khi K thành G (thêm dấu ゛): Kỹ 技 → ギ, như trong 技術 (ぎじゅつ, kỹ thuật).', 2),
    (@onyomi_lesson_1_1, 'QU', 'K', 'Âm đầu QU → K', 'Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', 'Quốc (国) → コク (koku)', 'Bỏ chữ “u” sau Q rồi đọc như K: Quốc 国 → コク, Quả 果 → カ, Quán 館 → カン.', 'Đôi khi QU thành G: Quân 軍 → グン.', 3),
    (@onyomi_lesson_1_2, 'H', 'K/G', 'Âm đầu H → K/G', 'Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', 'Học (学) → ガク (gaku)', 'H của Hán Việt đọc K hoặc G, không đọc ハ: Hải 海 → カイ (không phải ハイ), Học 学 → ガク.', 'Một số chữ mất hẳn phụ âm đầu: Hòa 和 → ワ.', 1),
    (@onyomi_lesson_1_2, 'NG', 'G', 'Âm đầu NG → G', 'Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', 'Ngũ (五) → ゴ (go)', 'Bỏ âm mũi của NG, chỉ còn G: Ngũ 五 → ゴ, Ngân 銀 → ギン, Ngữ 語 → ゴ.', NULL, 2),
    (@onyomi_lesson_1_3, 'L', 'R', 'Âm đầu L → R', 'Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', 'Lợi (利) → リ (ri)', 'Tiếng Nhật không có L nên L luôn thành R: Lợi 利 → リ, Liệu 料 → リョウ, Lực 力 → リョク.', NULL, 1),
    (@onyomi_lesson_1_3, 'N', 'N', 'Âm đầu N → N', 'Âm đầu N thường giữ hàng ナ (N).', 'Nam (南) → ナン (nan)', 'N giữ nguyên: Nam 南 → ナン, Niên 年 → ネン, Năng 能 → ノウ.', 'Nội 内 thường đọc ナイ; âm ダイ chỉ gặp trong vài từ hiếm như 境内 (けいだい, khuôn viên đền).', 2),
    (@onyomi_lesson_1_3, 'NH', 'N/J', 'Âm đầu NH → N/J', 'Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', 'Nhị (二) → ニ (ni)', 'NH thành N hoặc J, nên thử cả hai: Nhân 人 → ジン/ニン, Nhật 日 → ニチ/ジツ.', 'Đôi khi NH thành Z: Nhiên 然 → ゼン, như trong 自然 (しぜん, tự nhiên).', 3),
    (@onyomi_lesson_1_4, 'S', 'S/SH', 'Âm đầu S → S/SH', 'Âm đầu S thường giữ hàng サ (S hoặc SH).', 'Sinh (生) → セイ (sei)', 'S giữ nguyên S hoặc SH: Sinh 生 → セイ, Sơn 山 → サン, Sở 所 → ショ.', NULL, 1),
    (@onyomi_lesson_1_4, 'T', 'S', 'Âm đầu T → S', 'Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', 'Tiên (先) → セン (sen)', 'T của Hán Việt đọc S, không đọc タ: Tam 三 → サン, Tiên 先 → セン (không phải テン).', NULL, 2),
    (@onyomi_lesson_1_4, 'X', 'SH', 'Âm đầu X → SH', 'Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', 'Xa (車) → シャ (sha)', 'X đọc SH: Xa 車 → シャ, Xuân 春 → シュン, Xuất 出 → シュツ.', 'Đôi khi X thành S: Xích 赤 → セキ.', 3),
    (@onyomi_lesson_1_5, 'TH', 'S/T', 'Âm đầu TH → S/T', 'Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', 'Thất (失) → シツ (shitsu)', 'TH đọc S hoặc T: Thất 失 → シツ, Thế 世 → セイ; nhưng Thiên 天 → テン, Thông 通 → ツウ.', NULL, 1),
    (@onyomi_lesson_1_5, 'TR', 'CH/T', 'Âm đầu TR → CH/T', 'Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', 'Trưởng (長) → チョウ (chou)', 'TR thường đọc CH: Trưởng 長 → チョウ, Trung 中 → チュウ; đôi khi đọc T: Truy 追 → ツイ.', NULL, 2),
    (@onyomi_lesson_1_6, 'KH', 'K', 'Âm đầu KH → K', 'Âm đầu KH thường tương ứng với hàng カ (K).', 'Khai (開) → カイ (kai)', 'KH đọc K: Khai 開 → カイ, Không 空 → クウ, Khách 客 → キャク.', NULL, 1),
    (@onyomi_lesson_1_6, 'GI', 'K/G', 'Âm đầu GI → K/G', 'Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', 'Gia (家) → カ (ka)', 'GI thường đọc K: Gia 家 → カ, Gian 間 → カン, Giáo 教 → キョウ.', NULL, 2),
    (@onyomi_lesson_1_7, 'B', 'H/B', 'Âm đầu B → H/B', 'Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', 'Bắc (北) → ホク (hoku)', 'B thường đọc H: Bản 本 → ホン, Bán 半 → ハン; đôi khi giữ B: Bệnh 病 → ビョウ.', NULL, 1),
    (@onyomi_lesson_1_7, 'PH', 'H', 'Âm đầu PH → H', 'Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', 'Phương (方) → ホウ (hou)', 'PH đọc H (kể cả フ): Phương 方, Pháp 法, Phóng 放 đều → ホウ; Phụ 父 → フ, Phi 飛 → ヒ.', 'Trong từ ghép, đứng sau ン hoặc ッ thì hàng ハ thường đổi sang hàng パ: Văn pháp 文法 → ぶんぽう.', 2),
    (@onyomi_lesson_1_8, 'M', 'M/B', 'Âm đầu M → M/B', 'Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', 'Môn (門) → モン (mon)', 'M đọc M hoặc B: Môn 門 → モン, Minh 明 → メイ; nhưng Mã 馬 → バ, Mẫu 母 → ボ.', NULL, 1),
    (@onyomi_lesson_1_8, 'V', 'B/M/–', 'Âm đầu V → B/M hoặc mất phụ âm', 'Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', 'Văn (文) → ブン (bun)', 'V thường đọc B hoặc M, có khi mất hẳn: Văn 文 → ブン, Vấn 問 → モン, Viên 園 → エン, Vương 王 → オウ.', NULL, 2),
    (@onyomi_lesson_1_9, 'Đ', 'T/D', 'Âm đầu Đ → T/D', 'Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', 'Đại (大) → ダイ (dai)', 'Đ đọc T hoặc D: Đông 東 → トウ, Đại 大 → ダイ, Điện 電 → デン.', NULL, 1),
    (@onyomi_lesson_1_9, 'D', 'Y', 'Âm đầu D → Y', 'Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', 'Dược (薬) → ヤク (yaku)', 'D (không gạch) đọc Y, không đọc ダ hay ザ: Dược 薬 → ヤク, Dụng 用 → ヨウ.', 'Có chữ mất hẳn phụ âm đầu: Dĩ 以 → イ.', 2),
    (@onyomi_lesson_1_10, 'CH', 'S/SH', 'Âm đầu CH → S/SH', 'Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', 'Chính (正) → セイ (sei)', 'CH của Hán Việt đọc S/SH, không đọc チ: Chính 正 → セイ, Chủ 主 → シュ.', 'Đôi khi CH thành J: Chuẩn 準 → ジュン.', 1),
    (@onyomi_lesson_1_10, '(nguyên âm)', 'nguyên âm/Y', 'Nguyên âm đầu → nguyên âm/Y', 'Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', 'An (安) → アン (an)', 'Mở đầu bằng nguyên âm thì âm On cũng mở đầu bằng nguyên âm hoặc Y: An 安 → アン, Ý 意 → イ, Ước 約 → ヤク, Ưu 優 → ユウ.', NULL, 2),
    (@onyomi_lesson_2_1, 'M', 'ン', 'Âm cuối M → ン', 'Âm cuối -M của Hán Việt thành ン (n) trong âm On.', 'Tam (三) → サン (san)', 'Âm cuối -m thành ン, giống hệt -n: Tam 三 → サン, Tâm 心 → シン, Kim 金 → キン.', NULL, 1),
    (@onyomi_lesson_2_1, 'N', 'ン', 'Âm cuối N → ン', 'Âm cuối -N của Hán Việt giữ nguyên thành ン.', 'Văn (文) → ブン (bun)', 'Âm cuối -n thành ン: Văn 文 → ブン, Sơn 山 → サン, Bản 本 → ホン.', NULL, 2),
    (@onyomi_lesson_2_2, 'C', 'ク', 'Âm cuối C → ク', 'Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', 'Ác (悪) → アク (aku)', 'Âm cuối -c thành ク: Quốc 国 → コク, Học 学 → ガク, Lực 力 → リョク.', 'Một số chữ thành キ hoặc ツ: Thức 式 → シキ, Thực 実 → ジツ.', 1),
    (@onyomi_lesson_2_3, 'CH', 'キ/ク', 'Âm cuối CH → キ/ク', 'Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', 'Lịch (歴) → レキ (reki)', '-ich, -êch thành キ: Lịch 歴 → レキ, Tịch 席 → セキ; -ach thành ク: Bạch 白 → ハク, Khách 客 → キャク.', NULL, 1),
    (@onyomi_lesson_2_4, 'T', 'ツ/チ', 'Âm cuối T → ツ/チ', 'Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', 'Thiết (鉄) → テツ (tetsu)', 'Âm cuối -t thành ツ, đôi khi チ: Thiết 鉄 → テツ, Quyết 決 → ケツ; Nhật 日 → ニチ, Bát 八 → ハチ.', NULL, 1),
    (@onyomi_lesson_2_4, 'P', 'ウ', 'Âm cuối P → ウ (trường âm)', 'Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', 'Thập (十) → ジュウ (juu)', 'Âm cuối -p biến thành ウ kéo dài: Thập 十 → ジュウ, Cấp 急 → キュウ, Pháp 法 → ホウ, Hợp 合 → ゴウ.', 'Một số chữ giữ dấu vết -p thành ツ: Tiếp 接 → セツ, Áp 圧 → アツ.', 2),
    (@onyomi_lesson_3_1, 'AN', 'AN', 'Vần AN → アン', 'Vần AN (kể cả OAN) thường giữ nguyên thành アン.', 'An (安) → アン (an)', 'Vần AN giữ nguyên: An 安 → アン, Gian 間 → カン, Hoàn 完 → カン.', NULL, 1),
    (@onyomi_lesson_3_1, 'AM', 'AN', 'Vần AM → アン', 'Vần AM cũng thành アン (âm cuối -M thành ン).', 'Tam (三) → サン (san)', 'Vần AM cũng thành アン: Tam 三 → サン, Cảm 感 → カン, Nam 南 → ナン.', NULL, 2),
    (@onyomi_lesson_3_2, 'ÂN', 'IN', 'Vần ÂN → イン', 'Vần ÂN thường thành イン.', 'Ấn (印) → イン (in)', 'Vần ÂN thành イン: Tân 新 → シン, Cận 近 → キン, Dân 民 → ミン.', NULL, 1),
    (@onyomi_lesson_3_2, 'ÂM', 'IN', 'Vần ÂM → イン', 'Vần ÂM thường thành イン (âm cuối -M thành ン).', 'Tâm (心) → シン (shin)', 'Vần ÂM thành イン: Tâm 心 → シン, Lâm 林 → リン, Cấm 禁 → キン.', 'Âm 音 thường đọc オン, như 音楽 (おんがく, âm nhạc); イン chỉ gặp trong vài từ như 母音 (ぼいん, nguyên âm).', 2),
    (@onyomi_lesson_3_3, 'IÊN', 'EN', 'Vần IÊN → エン', 'Vần IÊN (kể cả UYÊN) thường thành エン.', 'Viên (円) → エン (en)', 'Vần IÊN thành エン: Thiên 天 → テン, Tiên 先 → セン, Nguyên 元 → ゲン.', NULL, 1),
    (@onyomi_lesson_3_3, 'IÊM', 'EN', 'Vần IÊM → エン', 'Vần IÊM thường thành エン (âm cuối -M thành ン).', 'Điếm (店) → テン (ten)', 'Vần IÊM thành エン: Điểm 点 → テン, Kiểm 検 → ケン, Niệm 念 → ネン.', NULL, 2),
    (@onyomi_lesson_3_4, 'ANG', 'OU', 'Vần ANG → オウ', 'Vần ANG (kể cả OANG) thường thành trường âm オウ.', 'Quang (光) → コウ (kou)', 'Vần ANG thành オウ: Quang 光 → コウ, Cảng 港 → コウ, Đảng 党 → トウ.', NULL, 1),
    (@onyomi_lesson_3_4, 'ƯƠNG', 'OU', 'Vần ƯƠNG → オウ', 'Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', 'Trưởng (長) → チョウ (chou)', 'Vần ƯƠNG thành オウ (hay kèm ョ): Trưởng 長 → チョウ, Chương 章 → ショウ, Phương 方 → ホウ.', NULL, 2),
    (@onyomi_lesson_3_4, 'ÔNG', 'OU/UU', 'Vần ÔNG → オウ/ウウ', 'Vần ÔNG thường thành オウ, đôi khi ウウ.', 'Công (工) → コウ (kou)', 'Vần ÔNG thành オウ: Công 工 → コウ, Đông 東 → トウ; nhưng Thông 通 → ツウ, Không 空 → クウ.', NULL, 3),
    (@onyomi_lesson_3_5, 'ANH', 'EI', 'Vần ANH → エイ', 'Vần ANH (kể cả OANH) thường thành エイ.', 'Thanh (青) → セイ (sei)', 'Vần ANH thành エイ: Thanh 青 → セイ, Anh 英 → エイ, Cảnh 景 → ケイ.', 'Ngoại lệ: Hành 行 đọc コウ hoặc ギョウ (ANH thành オウ).', 1),
    (@onyomi_lesson_3_5, 'ÊNH', 'EI', 'Vần ÊNH → エイ', 'Vần ÊNH thường thành エイ. Vần này ít chữ, nên gặp là nhớ luôn.', 'Mệnh (命) → メイ (mei)', 'Vần ÊNH thành エイ: Mệnh 命 → メイ, Lệnh 令 → レイ.', 'Ngoại lệ: Bệnh 病 đọc ビョウ (ÊNH thành ョウ).', 2),
    (@onyomi_lesson_3_5, 'INH', 'EI', 'Vần INH → エイ', 'Vần INH thường thành エイ.', 'Sinh (生) → セイ (sei)', 'Vần INH thành エイ: Sinh 生 → セイ, Định 定 → テイ, Minh 明 → メイ.', NULL, 3)
ON DUPLICATE KEY UPDATE source_sound = VALUES(source_sound), target_sound = VALUES(target_sound),
    title = VALUES(title), description = VALUES(description), example_breakdown = VALUES(example_breakdown),
    tip = VALUES(tip), note = VALUES(note);
SET @onyomi_rule_init_c = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_1 AND display_order = 1);
SET @onyomi_rule_init_k = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_1 AND display_order = 2);
SET @onyomi_rule_init_qu = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_1 AND display_order = 3);
SET @onyomi_rule_init_h = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_2 AND display_order = 1);
SET @onyomi_rule_init_ng = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_2 AND display_order = 2);
SET @onyomi_rule_init_l = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_3 AND display_order = 1);
SET @onyomi_rule_init_n = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_3 AND display_order = 2);
SET @onyomi_rule_init_nh = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_3 AND display_order = 3);
SET @onyomi_rule_init_s = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_4 AND display_order = 1);
SET @onyomi_rule_init_t = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_4 AND display_order = 2);
SET @onyomi_rule_init_x = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_4 AND display_order = 3);
SET @onyomi_rule_init_th = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_5 AND display_order = 1);
SET @onyomi_rule_init_tr = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_5 AND display_order = 2);
SET @onyomi_rule_init_kh = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_6 AND display_order = 1);
SET @onyomi_rule_init_gi = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_6 AND display_order = 2);
SET @onyomi_rule_init_b = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_7 AND display_order = 1);
SET @onyomi_rule_init_ph = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_7 AND display_order = 2);
SET @onyomi_rule_init_m = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_8 AND display_order = 1);
SET @onyomi_rule_init_v = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_8 AND display_order = 2);
SET @onyomi_rule_init_dd = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_9 AND display_order = 1);
SET @onyomi_rule_init_d = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_9 AND display_order = 2);
SET @onyomi_rule_init_ch = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_10 AND display_order = 1);
SET @onyomi_rule_init_vowel = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_10 AND display_order = 2);
SET @onyomi_rule_fin_m = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_2_1 AND display_order = 1);
SET @onyomi_rule_fin_n = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_2_1 AND display_order = 2);
SET @onyomi_rule_fin_c = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_2_2 AND display_order = 1);
SET @onyomi_rule_fin_ch = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_2_3 AND display_order = 1);
SET @onyomi_rule_fin_t = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_2_4 AND display_order = 1);
SET @onyomi_rule_fin_p = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_2_4 AND display_order = 2);
SET @onyomi_rule_rhy_an = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_1 AND display_order = 1);
SET @onyomi_rule_rhy_am = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_1 AND display_order = 2);
SET @onyomi_rule_rhy_aan = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_2 AND display_order = 1);
SET @onyomi_rule_rhy_aam = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_2 AND display_order = 2);
SET @onyomi_rule_rhy_ien = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_3 AND display_order = 1);
SET @onyomi_rule_rhy_iem = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_3 AND display_order = 2);
SET @onyomi_rule_rhy_ang = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_4 AND display_order = 1);
SET @onyomi_rule_rhy_uong = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_4 AND display_order = 2);
SET @onyomi_rule_rhy_ong = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_4 AND display_order = 3);
SET @onyomi_rule_rhy_anh = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_5 AND display_order = 1);
SET @onyomi_rule_rhy_enh = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_5 AND display_order = 2);
SET @onyomi_rule_rhy_inh = (SELECT id FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_5 AND display_order = 3);
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_1 AND display_order > 3;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_2 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_3 AND display_order > 3;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_4 AND display_order > 3;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_5 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_6 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_7 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_8 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_9 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_1_10 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_2_1 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_2_2 AND display_order > 1;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_2_3 AND display_order > 1;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_2_4 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_1 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_2 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_3 AND display_order > 2;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_4 AND display_order > 3;
DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_3_5 AND display_order > 3;

DELETE FROM rule_pattern WHERE rule_id IN (@onyomi_rule_init_c, @onyomi_rule_init_k, @onyomi_rule_init_qu, @onyomi_rule_init_h, @onyomi_rule_init_ng, @onyomi_rule_init_l, @onyomi_rule_init_n, @onyomi_rule_init_nh, @onyomi_rule_init_s, @onyomi_rule_init_t, @onyomi_rule_init_x, @onyomi_rule_init_th, @onyomi_rule_init_tr, @onyomi_rule_init_kh, @onyomi_rule_init_gi, @onyomi_rule_init_b, @onyomi_rule_init_ph, @onyomi_rule_init_m, @onyomi_rule_init_v, @onyomi_rule_init_dd, @onyomi_rule_init_d, @onyomi_rule_init_ch, @onyomi_rule_init_vowel, @onyomi_rule_fin_m, @onyomi_rule_fin_n, @onyomi_rule_fin_c, @onyomi_rule_fin_ch, @onyomi_rule_fin_t, @onyomi_rule_fin_p, @onyomi_rule_rhy_an, @onyomi_rule_rhy_am, @onyomi_rule_rhy_aan, @onyomi_rule_rhy_aam, @onyomi_rule_rhy_ien, @onyomi_rule_rhy_iem, @onyomi_rule_rhy_ang, @onyomi_rule_rhy_uong, @onyomi_rule_rhy_ong, @onyomi_rule_rhy_anh, @onyomi_rule_rhy_enh, @onyomi_rule_rhy_inh);
INSERT INTO rule_pattern (rule_id, step, title, content) VALUES
    (@onyomi_rule_init_c, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu C.'),
    (@onyomi_rule_init_c, 2, 'Chuyển đổi', 'C → K: Công (工) → コウ (kou).'),
    (@onyomi_rule_init_c, 3, 'Ghi nhớ', 'Đổi C thành K rồi đọc: Công 工 → コウ, Cổ 古 → コ, Các 各 → カク.'),
    (@onyomi_rule_init_c, 4, 'Lưu ý', 'C và K trong Hán Việt là cùng một âm “cờ”, chỉ khác cách viết: trước i, e, ê, y viết K (Kim, Kế, Kỳ), còn lại viết C (Công, Cổ, Ca). Vì vậy C → K và K → K thực chất là một quy tắc.'),
    (@onyomi_rule_init_k, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu K.'),
    (@onyomi_rule_init_k, 2, 'Chuyển đổi', 'K → K: Kim (金) → キン (kin).'),
    (@onyomi_rule_init_k, 3, 'Ghi nhớ', 'K giữ nguyên là K: Kim 金 → キン, Kỳ 期 → キ, Kiến 見 → ケン.'),
    (@onyomi_rule_init_k, 4, 'Lưu ý', 'Đôi khi K thành G (thêm dấu ゛): Kỹ 技 → ギ, như trong 技術 (ぎじゅつ, kỹ thuật).'),
    (@onyomi_rule_init_qu, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu QU.'),
    (@onyomi_rule_init_qu, 2, 'Chuyển đổi', 'QU → K: Quốc (国) → コク (koku).'),
    (@onyomi_rule_init_qu, 3, 'Ghi nhớ', 'Bỏ chữ “u” sau Q rồi đọc như K: Quốc 国 → コク, Quả 果 → カ, Quán 館 → カン.'),
    (@onyomi_rule_init_qu, 4, 'Lưu ý', 'Đôi khi QU thành G: Quân 軍 → グン.'),
    (@onyomi_rule_init_h, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu H.'),
    (@onyomi_rule_init_h, 2, 'Chuyển đổi', 'H → K/G: Học (学) → ガク (gaku).'),
    (@onyomi_rule_init_h, 3, 'Ghi nhớ', 'H của Hán Việt đọc K hoặc G, không đọc ハ: Hải 海 → カイ (không phải ハイ), Học 学 → ガク.'),
    (@onyomi_rule_init_h, 4, 'Lưu ý', 'Một số chữ mất hẳn phụ âm đầu: Hòa 和 → ワ.'),
    (@onyomi_rule_init_ng, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu NG.'),
    (@onyomi_rule_init_ng, 2, 'Chuyển đổi', 'NG → G: Ngũ (五) → ゴ (go).'),
    (@onyomi_rule_init_ng, 3, 'Ghi nhớ', 'Bỏ âm mũi của NG, chỉ còn G: Ngũ 五 → ゴ, Ngân 銀 → ギン, Ngữ 語 → ゴ.'),
    (@onyomi_rule_init_l, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu L.'),
    (@onyomi_rule_init_l, 2, 'Chuyển đổi', 'L → R: Lợi (利) → リ (ri).'),
    (@onyomi_rule_init_l, 3, 'Ghi nhớ', 'Tiếng Nhật không có L nên L luôn thành R: Lợi 利 → リ, Liệu 料 → リョウ, Lực 力 → リョク.'),
    (@onyomi_rule_init_n, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu N.'),
    (@onyomi_rule_init_n, 2, 'Chuyển đổi', 'N → N: Nam (南) → ナン (nan).'),
    (@onyomi_rule_init_n, 3, 'Ghi nhớ', 'N giữ nguyên: Nam 南 → ナン, Niên 年 → ネン, Năng 能 → ノウ.'),
    (@onyomi_rule_init_n, 4, 'Lưu ý', 'Nội 内 thường đọc ナイ; âm ダイ chỉ gặp trong vài từ hiếm như 境内 (けいだい, khuôn viên đền).'),
    (@onyomi_rule_init_nh, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu NH.'),
    (@onyomi_rule_init_nh, 2, 'Chuyển đổi', 'NH → N/J: Nhị (二) → ニ (ni).'),
    (@onyomi_rule_init_nh, 3, 'Ghi nhớ', 'NH thành N hoặc J, nên thử cả hai: Nhân 人 → ジン/ニン, Nhật 日 → ニチ/ジツ.'),
    (@onyomi_rule_init_nh, 4, 'Lưu ý', 'Đôi khi NH thành Z: Nhiên 然 → ゼン, như trong 自然 (しぜん, tự nhiên).'),
    (@onyomi_rule_init_s, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu S.'),
    (@onyomi_rule_init_s, 2, 'Chuyển đổi', 'S → S/SH: Sinh (生) → セイ (sei).'),
    (@onyomi_rule_init_s, 3, 'Ghi nhớ', 'S giữ nguyên S hoặc SH: Sinh 生 → セイ, Sơn 山 → サン, Sở 所 → ショ.'),
    (@onyomi_rule_init_t, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu T.'),
    (@onyomi_rule_init_t, 2, 'Chuyển đổi', 'T → S: Tiên (先) → セン (sen).'),
    (@onyomi_rule_init_t, 3, 'Ghi nhớ', 'T của Hán Việt đọc S, không đọc タ: Tam 三 → サン, Tiên 先 → セン (không phải テン).'),
    (@onyomi_rule_init_x, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu X.'),
    (@onyomi_rule_init_x, 2, 'Chuyển đổi', 'X → SH: Xa (車) → シャ (sha).'),
    (@onyomi_rule_init_x, 3, 'Ghi nhớ', 'X đọc SH: Xa 車 → シャ, Xuân 春 → シュン, Xuất 出 → シュツ.'),
    (@onyomi_rule_init_x, 4, 'Lưu ý', 'Đôi khi X thành S: Xích 赤 → セキ.'),
    (@onyomi_rule_init_th, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu TH.'),
    (@onyomi_rule_init_th, 2, 'Chuyển đổi', 'TH → S/T: Thất (失) → シツ (shitsu).'),
    (@onyomi_rule_init_th, 3, 'Ghi nhớ', 'TH đọc S hoặc T: Thất 失 → シツ, Thế 世 → セイ; nhưng Thiên 天 → テン, Thông 通 → ツウ.'),
    (@onyomi_rule_init_tr, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu TR.'),
    (@onyomi_rule_init_tr, 2, 'Chuyển đổi', 'TR → CH/T: Trưởng (長) → チョウ (chou).'),
    (@onyomi_rule_init_tr, 3, 'Ghi nhớ', 'TR thường đọc CH: Trưởng 長 → チョウ, Trung 中 → チュウ; đôi khi đọc T: Truy 追 → ツイ.'),
    (@onyomi_rule_init_kh, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu KH.'),
    (@onyomi_rule_init_kh, 2, 'Chuyển đổi', 'KH → K: Khai (開) → カイ (kai).'),
    (@onyomi_rule_init_kh, 3, 'Ghi nhớ', 'KH đọc K: Khai 開 → カイ, Không 空 → クウ, Khách 客 → キャク.'),
    (@onyomi_rule_init_gi, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu GI.'),
    (@onyomi_rule_init_gi, 2, 'Chuyển đổi', 'GI → K/G: Gia (家) → カ (ka).'),
    (@onyomi_rule_init_gi, 3, 'Ghi nhớ', 'GI thường đọc K: Gia 家 → カ, Gian 間 → カン, Giáo 教 → キョウ.'),
    (@onyomi_rule_init_b, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu B.'),
    (@onyomi_rule_init_b, 2, 'Chuyển đổi', 'B → H/B: Bắc (北) → ホク (hoku).'),
    (@onyomi_rule_init_b, 3, 'Ghi nhớ', 'B thường đọc H: Bản 本 → ホン, Bán 半 → ハン; đôi khi giữ B: Bệnh 病 → ビョウ.'),
    (@onyomi_rule_init_ph, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu PH.'),
    (@onyomi_rule_init_ph, 2, 'Chuyển đổi', 'PH → H: Phương (方) → ホウ (hou).'),
    (@onyomi_rule_init_ph, 3, 'Ghi nhớ', 'PH đọc H (kể cả フ): Phương 方, Pháp 法, Phóng 放 đều → ホウ; Phụ 父 → フ, Phi 飛 → ヒ.'),
    (@onyomi_rule_init_ph, 4, 'Lưu ý', 'Trong từ ghép, đứng sau ン hoặc ッ thì hàng ハ thường đổi sang hàng パ: Văn pháp 文法 → ぶんぽう.'),
    (@onyomi_rule_init_m, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu M.'),
    (@onyomi_rule_init_m, 2, 'Chuyển đổi', 'M → M/B: Môn (門) → モン (mon).'),
    (@onyomi_rule_init_m, 3, 'Ghi nhớ', 'M đọc M hoặc B: Môn 門 → モン, Minh 明 → メイ; nhưng Mã 馬 → バ, Mẫu 母 → ボ.'),
    (@onyomi_rule_init_v, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu V.'),
    (@onyomi_rule_init_v, 2, 'Chuyển đổi', 'V → B/M/–: Văn (文) → ブン (bun).'),
    (@onyomi_rule_init_v, 3, 'Ghi nhớ', 'V thường đọc B hoặc M, có khi mất hẳn: Văn 文 → ブン, Vấn 問 → モン, Viên 園 → エン, Vương 王 → オウ.'),
    (@onyomi_rule_init_dd, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu Đ.'),
    (@onyomi_rule_init_dd, 2, 'Chuyển đổi', 'Đ → T/D: Đại (大) → ダイ (dai).'),
    (@onyomi_rule_init_dd, 3, 'Ghi nhớ', 'Đ đọc T hoặc D: Đông 東 → トウ, Đại 大 → ダイ, Điện 電 → デン.'),
    (@onyomi_rule_init_d, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu D.'),
    (@onyomi_rule_init_d, 2, 'Chuyển đổi', 'D → Y: Dược (薬) → ヤク (yaku).'),
    (@onyomi_rule_init_d, 3, 'Ghi nhớ', 'D (không gạch) đọc Y, không đọc ダ hay ザ: Dược 薬 → ヤク, Dụng 用 → ヨウ.'),
    (@onyomi_rule_init_d, 4, 'Lưu ý', 'Có chữ mất hẳn phụ âm đầu: Dĩ 以 → イ.'),
    (@onyomi_rule_init_ch, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu CH.'),
    (@onyomi_rule_init_ch, 2, 'Chuyển đổi', 'CH → S/SH: Chính (正) → セイ (sei).'),
    (@onyomi_rule_init_ch, 3, 'Ghi nhớ', 'CH của Hán Việt đọc S/SH, không đọc チ: Chính 正 → セイ, Chủ 主 → シュ.'),
    (@onyomi_rule_init_ch, 4, 'Lưu ý', 'Đôi khi CH thành J: Chuẩn 準 → ジュン.'),
    (@onyomi_rule_init_vowel, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm đầu (nguyên âm).'),
    (@onyomi_rule_init_vowel, 2, 'Chuyển đổi', '(nguyên âm) → nguyên âm/Y: An (安) → アン (an).'),
    (@onyomi_rule_init_vowel, 3, 'Ghi nhớ', 'Mở đầu bằng nguyên âm thì âm On cũng mở đầu bằng nguyên âm hoặc Y: An 安 → アン, Ý 意 → イ, Ước 約 → ヤク, Ưu 優 → ユウ.'),
    (@onyomi_rule_fin_m, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm cuối M.'),
    (@onyomi_rule_fin_m, 2, 'Chuyển đổi', 'M → ン: Tam (三) → サン (san).'),
    (@onyomi_rule_fin_m, 3, 'Ghi nhớ', 'Âm cuối -m thành ン, giống hệt -n: Tam 三 → サン, Tâm 心 → シン, Kim 金 → キン.'),
    (@onyomi_rule_fin_n, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm cuối N.'),
    (@onyomi_rule_fin_n, 2, 'Chuyển đổi', 'N → ン: Văn (文) → ブン (bun).'),
    (@onyomi_rule_fin_n, 3, 'Ghi nhớ', 'Âm cuối -n thành ン: Văn 文 → ブン, Sơn 山 → サン, Bản 本 → ホン.'),
    (@onyomi_rule_fin_c, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm cuối C.'),
    (@onyomi_rule_fin_c, 2, 'Chuyển đổi', 'C → ク: Ác (悪) → アク (aku).'),
    (@onyomi_rule_fin_c, 3, 'Ghi nhớ', 'Âm cuối -c thành ク: Quốc 国 → コク, Học 学 → ガク, Lực 力 → リョク.'),
    (@onyomi_rule_fin_c, 4, 'Lưu ý', 'Một số chữ thành キ hoặc ツ: Thức 式 → シキ, Thực 実 → ジツ.'),
    (@onyomi_rule_fin_ch, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm cuối CH.'),
    (@onyomi_rule_fin_ch, 2, 'Chuyển đổi', 'CH → キ/ク: Lịch (歴) → レキ (reki).'),
    (@onyomi_rule_fin_ch, 3, 'Ghi nhớ', '-ich, -êch thành キ: Lịch 歴 → レキ, Tịch 席 → セキ; -ach thành ク: Bạch 白 → ハク, Khách 客 → キャク.'),
    (@onyomi_rule_fin_t, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm cuối T.'),
    (@onyomi_rule_fin_t, 2, 'Chuyển đổi', 'T → ツ/チ: Thiết (鉄) → テツ (tetsu).'),
    (@onyomi_rule_fin_t, 3, 'Ghi nhớ', 'Âm cuối -t thành ツ, đôi khi チ: Thiết 鉄 → テツ, Quyết 決 → ケツ; Nhật 日 → ニチ, Bát 八 → ハチ.'),
    (@onyomi_rule_fin_p, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm âm cuối P.'),
    (@onyomi_rule_fin_p, 2, 'Chuyển đổi', 'P → ウ: Thập (十) → ジュウ (juu).'),
    (@onyomi_rule_fin_p, 3, 'Ghi nhớ', 'Âm cuối -p biến thành ウ kéo dài: Thập 十 → ジュウ, Cấp 急 → キュウ, Pháp 法 → ホウ, Hợp 合 → ゴウ.'),
    (@onyomi_rule_fin_p, 4, 'Lưu ý', 'Một số chữ giữ dấu vết -p thành ツ: Tiếp 接 → セツ, Áp 圧 → アツ.'),
    (@onyomi_rule_rhy_an, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần AN.'),
    (@onyomi_rule_rhy_an, 2, 'Chuyển đổi', 'AN → AN: An (安) → アン (an).'),
    (@onyomi_rule_rhy_an, 3, 'Ghi nhớ', 'Vần AN giữ nguyên: An 安 → アン, Gian 間 → カン, Hoàn 完 → カン.'),
    (@onyomi_rule_rhy_am, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần AM.'),
    (@onyomi_rule_rhy_am, 2, 'Chuyển đổi', 'AM → AN: Tam (三) → サン (san).'),
    (@onyomi_rule_rhy_am, 3, 'Ghi nhớ', 'Vần AM cũng thành アン: Tam 三 → サン, Cảm 感 → カン, Nam 南 → ナン.'),
    (@onyomi_rule_rhy_aan, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần ÂN.'),
    (@onyomi_rule_rhy_aan, 2, 'Chuyển đổi', 'ÂN → IN: Ấn (印) → イン (in).'),
    (@onyomi_rule_rhy_aan, 3, 'Ghi nhớ', 'Vần ÂN thành イン: Tân 新 → シン, Cận 近 → キン, Dân 民 → ミン.'),
    (@onyomi_rule_rhy_aam, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần ÂM.'),
    (@onyomi_rule_rhy_aam, 2, 'Chuyển đổi', 'ÂM → IN: Tâm (心) → シン (shin).'),
    (@onyomi_rule_rhy_aam, 3, 'Ghi nhớ', 'Vần ÂM thành イン: Tâm 心 → シン, Lâm 林 → リン, Cấm 禁 → キン.'),
    (@onyomi_rule_rhy_aam, 4, 'Lưu ý', 'Âm 音 thường đọc オン, như 音楽 (おんがく, âm nhạc); イン chỉ gặp trong vài từ như 母音 (ぼいん, nguyên âm).'),
    (@onyomi_rule_rhy_ien, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần IÊN.'),
    (@onyomi_rule_rhy_ien, 2, 'Chuyển đổi', 'IÊN → EN: Viên (円) → エン (en).'),
    (@onyomi_rule_rhy_ien, 3, 'Ghi nhớ', 'Vần IÊN thành エン: Thiên 天 → テン, Tiên 先 → セン, Nguyên 元 → ゲン.'),
    (@onyomi_rule_rhy_iem, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần IÊM.'),
    (@onyomi_rule_rhy_iem, 2, 'Chuyển đổi', 'IÊM → EN: Điếm (店) → テン (ten).'),
    (@onyomi_rule_rhy_iem, 3, 'Ghi nhớ', 'Vần IÊM thành エン: Điểm 点 → テン, Kiểm 検 → ケン, Niệm 念 → ネン.'),
    (@onyomi_rule_rhy_ang, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần ANG.'),
    (@onyomi_rule_rhy_ang, 2, 'Chuyển đổi', 'ANG → OU: Quang (光) → コウ (kou).'),
    (@onyomi_rule_rhy_ang, 3, 'Ghi nhớ', 'Vần ANG thành オウ: Quang 光 → コウ, Cảng 港 → コウ, Đảng 党 → トウ.'),
    (@onyomi_rule_rhy_uong, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần ƯƠNG.'),
    (@onyomi_rule_rhy_uong, 2, 'Chuyển đổi', 'ƯƠNG → OU: Trưởng (長) → チョウ (chou).'),
    (@onyomi_rule_rhy_uong, 3, 'Ghi nhớ', 'Vần ƯƠNG thành オウ (hay kèm ョ): Trưởng 長 → チョウ, Chương 章 → ショウ, Phương 方 → ホウ.'),
    (@onyomi_rule_rhy_ong, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần ÔNG.'),
    (@onyomi_rule_rhy_ong, 2, 'Chuyển đổi', 'ÔNG → OU/UU: Công (工) → コウ (kou).'),
    (@onyomi_rule_rhy_ong, 3, 'Ghi nhớ', 'Vần ÔNG thành オウ: Công 工 → コウ, Đông 東 → トウ; nhưng Thông 通 → ツウ, Không 空 → クウ.'),
    (@onyomi_rule_rhy_anh, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần ANH.'),
    (@onyomi_rule_rhy_anh, 2, 'Chuyển đổi', 'ANH → EI: Thanh (青) → セイ (sei).'),
    (@onyomi_rule_rhy_anh, 3, 'Ghi nhớ', 'Vần ANH thành エイ: Thanh 青 → セイ, Anh 英 → エイ, Cảnh 景 → ケイ.'),
    (@onyomi_rule_rhy_anh, 4, 'Lưu ý', 'Ngoại lệ: Hành 行 đọc コウ hoặc ギョウ (ANH thành オウ).'),
    (@onyomi_rule_rhy_enh, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần ÊNH.'),
    (@onyomi_rule_rhy_enh, 2, 'Chuyển đổi', 'ÊNH → EI: Mệnh (命) → メイ (mei).'),
    (@onyomi_rule_rhy_enh, 3, 'Ghi nhớ', 'Vần ÊNH thành エイ: Mệnh 命 → メイ, Lệnh 令 → レイ.'),
    (@onyomi_rule_rhy_enh, 4, 'Lưu ý', 'Ngoại lệ: Bệnh 病 đọc ビョウ (ÊNH thành ョウ).'),
    (@onyomi_rule_rhy_inh, 1, 'Nhận diện', 'Tách âm Hán Việt, tìm vần INH.'),
    (@onyomi_rule_rhy_inh, 2, 'Chuyển đổi', 'INH → EI: Sinh (生) → セイ (sei).'),
    (@onyomi_rule_rhy_inh, 3, 'Ghi nhớ', 'Vần INH thành エイ: Sinh 生 → セイ, Định 定 → テイ, Minh 明 → メイ.');

INSERT INTO kanji (`character`, unicode_code, han_viet, jlpt_level) VALUES
    ('万', '4E07', 'Vạn', 'N5'),
    ('三', '4E09', 'Tam', 'N5'),
    ('世', '4E16', 'Thế', 'N3'),
    ('中', '4E2D', 'Trung', 'N5'),
    ('主', '4E3B', 'Chủ', 'N4'),
    ('予', '4E88', 'Dự', 'N4'),
    ('二', '4E8C', 'Nhị', 'N5'),
    ('五', '4E94', 'Ngũ', 'N5'),
    ('交', '4EA4', 'Giao', 'N4'),
    ('京', '4EAC', 'Kinh', 'N5'),
    ('人', '4EBA', 'Nhân', 'N5'),
    ('令', '4EE4', 'Lệnh', 'N2'),
    ('以', '4EE5', 'Dĩ', NULL),
    ('任', '4EFB', 'Nhiệm', 'N3'),
    ('会', '4F1A', 'Hội', 'N5'),
    ('体', '4F53', 'Thể', 'N3'),
    ('作', '4F5C', 'Tác', 'N4'),
    ('使', '4F7F', 'Sử', 'N3'),
    ('億', '5104', 'Ức', 'N3'),
    ('元', '5143', 'Nguyên', 'N5'),
    ('先', '5148', 'Tiên', 'N5'),
    ('光', '5149', 'Quang', 'N3'),
    ('党', '515A', 'Đảng', 'N1'),
    ('入', '5165', 'Nhập', 'N3'),
    ('八', '516B', 'Bát', 'N5'),
    ('公', '516C', 'Công', 'N4'),
    ('六', '516D', 'Lục', 'N5'),
    ('兼', '517C', 'Kiêm', 'N2'),
    ('内', '5185', 'Nội', 'N4'),
    ('円', '5186', 'Viên', 'N5'),
    ('冷', '51B7', 'Lãnh', 'N2'),
    ('処', '51E6', 'Xử', 'N2'),
    ('出', '51FA', 'Xuất', 'N3'),
    ('初', '521D', 'Sơ', 'N4'),
    ('別', '5225', 'Biệt', 'N4'),
    ('利', '5229', 'Lợi', 'N4'),
    ('劇', '5287', 'Kịch', 'N2'),
    ('力', '529B', 'Lực', 'N3'),
    ('加', '52A0', 'Gia', 'N3'),
    ('勇', '52C7', 'Dũng', 'N3'),
    ('動', '52D5', 'Động', 'N4'),
    ('化', '5316', 'Hóa', 'N3'),
    ('北', '5317', 'Bắc', 'N2'),
    ('医', '533B', 'Y', 'N5'),
    ('十', '5341', 'Thập', 'N4'),
    ('半', '534A', 'Bán', 'N5'),
    ('協', '5354', 'Hiệp', 'N3'),
    ('南', '5357', 'Nam', 'N4'),
    ('印', '5370', 'Ấn', 'N2'),
    ('参', '53C2', 'Tham', 'N3'),
    ('反', '53CD', 'Phản', 'N3'),
    ('口', '53E3', 'Khẩu', 'N3'),
    ('古', '53E4', 'Cổ', 'N3'),
    ('各', '5404', 'Các', 'N3'),
    ('合', '5408', 'Hợp', 'N3'),
    ('同', '540C', 'Đồng', 'N3'),
    ('名', '540D', 'Danh', 'N4'),
    ('向', '5411', 'Hướng', 'N3'),
    ('命', '547D', 'Mệnh', 'N2'),
    ('和', '548C', 'Hòa', NULL),
    ('品', '54C1', 'Phẩm', 'N3'),
    ('員', '54E1', 'Viên', 'N3'),
    ('問', '554F', 'Vấn', 'N5'),
    ('営', '55B6', 'Doanh', 'N2'),
    ('国', '56FD', 'Quốc', 'N4'),
    ('圧', '5727', 'Áp', NULL),
    ('場', '5834', 'Trường', 'N3'),
    ('変', '5909', 'Biến', 'N4'),
    ('外', '5916', 'Ngoại', 'N4'),
    ('大', '5927', 'Đại', 'N5'),
    ('天', '5929', 'Thiên', 'N5'),
    ('失', '5931', 'Thất', 'N4'),
    ('学', '5B66', 'Học', 'N5'),
    ('安', '5B89', 'An', 'N4'),
    ('完', '5B8C', 'Hoàn', 'N3'),
    ('定', '5B9A', 'Định', 'N4'),
    ('実', '5B9F', 'Thực', NULL),
    ('客', '5BA2', 'Khách', 'N2'),
    ('家', '5BB6', 'Gia', 'N4'),
    ('射', '5C04', 'Xạ', 'N3'),
    ('山', '5C71', 'Sơn', 'N5'),
    ('工', '5DE5', 'Công', 'N4'),
    ('師', '5E2B', 'Sư', 'N3'),
    ('席', '5E2D', 'Tịch', 'N3'),
    ('常', '5E38', 'Thường', 'N2'),
    ('平', '5E73', 'Bình', 'N3'),
    ('年', '5E74', 'Niên', 'N5'),
    ('店', '5E97', 'Điếm', 'N3'),
    ('度', '5EA6', 'Độ', 'N4'),
    ('康', '5EB7', 'Khang', 'N3'),
    ('式', '5F0F', 'Thức', NULL),
    ('形', '5F62', 'Hình', 'N2'),
    ('後', '5F8C', 'Hậu', 'N5'),
    ('心', '5FC3', 'Tâm', 'N4'),
    ('念', '5FF5', 'Niệm', 'N3'),
    ('急', '6025', 'Cấp', 'N4'),
    ('性', '6027', 'Tính', 'N3'),
    ('悪', '60AA', 'Ác', 'N3'),
    ('意', '610F', 'Ý', 'N4'),
    ('感', '611F', 'Cảm', 'N3'),
    ('成', '6210', 'Thành', 'N3'),
    ('戦', '6226', 'Chiến', 'N3'),
    ('所', '6240', 'Sở', 'N4'),
    ('手', '624B', 'Thủ', 'N3'),
    ('技', '6280', 'Kỹ', 'N3'),
    ('担', '62C5', 'Đảm', 'N2'),
    ('接', '63A5', 'Tiếp', NULL),
    ('放', '653E', 'Phóng', 'N3'),
    ('教', '6559', 'Giáo', 'N5'),
    ('散', '6563', 'Tán', 'N4'),
    ('文', '6587', 'Văn', 'N3'),
    ('料', '6599', 'Liệu', 'N4'),
    ('新', '65B0', 'Tân', 'N5'),
    ('方', '65B9', 'Phương', 'N3'),
    ('旅', '65C5', 'Lữ', 'N4'),
    ('日', '65E5', 'Nhật', 'N5'),
    ('明', '660E', 'Minh', 'N4'),
    ('映', '6620', 'Ánh', 'N4'),
    ('春', '6625', 'Xuân', 'N2'),
    ('景', '666F', 'Cảnh', 'N2'),
    ('暗', '6697', 'Ám', 'N2'),
    ('書', '66F8', 'Thư', 'N4'),
    ('最', '6700', 'Tối', 'N4'),
    ('朝', '671D', 'Triều', 'N3'),
    ('期', '671F', 'Kỳ', 'N3'),
    ('未', '672A', 'Vị', 'N3'),
    ('本', '672C', 'Bản', 'N5'),
    ('東', '6771', 'Đông', 'N5'),
    ('林', '6797', 'Lâm', 'N2'),
    ('果', '679C', 'Quả', 'N3'),
    ('染', '67D3', 'Nhiễm', 'N1'),
    ('校', '6821', 'Hiệu', 'N5'),
    ('格', '683C', 'Cách', 'N3'),
    ('検', '691C', 'Kiểm', 'N3'),
    ('業', '696D', 'Nghiệp', 'N4'),
    ('様', '69D8', 'Dạng', 'N3'),
    ('歌', '6B4C', 'Ca', 'N4'),
    ('止', '6B62', 'Chỉ', 'N3'),
    ('正', '6B63', 'Chính', 'N3'),
    ('歴', '6B74', 'Lịch', 'N3'),
    ('母', '6BCD', 'Mẫu', 'N2'),
    ('毎', '6BCE', 'Mỗi', 'N5'),
    ('民', '6C11', 'Dân', 'N3'),
    ('気', '6C17', 'Khí', 'N5'),
    ('永', '6C38', 'Vĩnh', 'N2'),
    ('決', '6C7A', 'Quyết', 'N3'),
    ('油', '6CB9', 'Du', 'N3'),
    ('法', '6CD5', 'Pháp', 'N4'),
    ('洋', '6D0B', 'Dương', 'N3'),
    ('活', '6D3B', 'Hoạt', 'N4'),
    ('海', '6D77', 'Hải', 'N4'),
    ('深', '6DF1', 'Thâm', 'N2'),
    ('減', '6E1B', 'Giảm', 'N2'),
    ('港', '6E2F', 'Cảng', 'N4'),
    ('湯', '6E6F', 'Thang', 'N1'),
    ('準', '6E96', 'Chuẩn', NULL),
    ('漢', '6F22', 'Hán', 'N4'),
    ('点', '70B9', 'Điểm', 'N3'),
    ('無', '7121', 'Vô', 'N4'),
    ('然', '7136', 'Nhiên', NULL),
    ('熱', '71B1', 'Nhiệt', 'N3'),
    ('父', '7236', 'Phụ', 'N3'),
    ('牛', '725B', 'Ngưu', 'N4'),
    ('物', '7269', 'Vật', 'N4'),
    ('特', '7279', 'Đặc', 'N4'),
    ('王', '738B', 'Vương', 'N3'),
    ('現', '73FE', 'Hiện', 'N3'),
    ('理', '7406', 'Lý', 'N4'),
    ('生', '751F', 'Sinh', 'N5'),
    ('産', '7523', 'Sản', 'N3'),
    ('用', '7528', 'Dụng', 'N3'),
    ('由', '7531', 'Do', 'N4'),
    ('界', '754C', 'Giới', 'N4'),
    ('病', '75C5', 'Bệnh', 'N5'),
    ('発', '767A', 'Phát', 'N4'),
    ('白', '767D', 'Bạch', 'N2'),
    ('百', '767E', 'Bách', 'N5'),
    ('皇', '7687', 'Hoàng', 'N1'),
    ('目', '76EE', 'Mục', 'N3'),
    ('相', '76F8', 'Tương', 'N3'),
    ('真', '771F', 'Chân', 'N5'),
    ('着', '7740', 'Trước', 'N3'),
    ('知', '77E5', 'Tri', 'N3'),
    ('社', '793E', 'Xã', 'N5'),
    ('禁', '7981', 'Cấm', 'N3'),
    ('科', '79D1', 'Khoa', 'N4'),
    ('空', '7A7A', 'Không', 'N4'),
    ('章', '7AE0', 'Chương', 'N3'),
    ('答', '7B54', 'Đáp', 'N2'),
    ('米', '7C73', 'Mễ', 'N2'),
    ('精', '7CBE', 'Tinh', 'N2'),
    ('約', '7D04', 'Ước', 'N4'),
    ('級', '7D1A', 'Cấp', 'N3'),
    ('終', '7D42', 'Chung', 'N2'),
    ('経', '7D4C', 'Kinh', 'N3'),
    ('統', '7D71', 'Thống', 'N3'),
    ('総', '7DCF', 'Tổng', 'N2'),
    ('練', '7DF4', 'Luyện', 'N4'),
    ('美', '7F8E', 'Mỹ', 'N3'),
    ('習', '7FD2', 'Tập', 'N4'),
    ('考', '8003', 'Khảo', 'N3'),
    ('肉', '8089', 'Nhục', 'N4'),
    ('能', '80FD', 'Năng', 'N3'),
    ('色', '8272', 'Sắc', 'N2'),
    ('苦', '82E6', 'Khổ', 'N3'),
    ('英', '82F1', 'Anh', 'N5'),
    ('茶', '8336', 'Trà', 'N4'),
    ('薬', '85AC', 'Dược', 'N3'),
    ('行', '884C', 'Hành', NULL),
    ('西', '897F', 'Tây', 'N3'),
    ('見', '898B', 'Kiến', 'N4'),
    ('規', '898F', 'Quy', 'N3'),
    ('親', '89AA', 'Thân', 'N4'),
    ('解', '89E3', 'Giải', 'N3'),
    ('言', '8A00', 'Ngôn', 'N3'),
    ('記', '8A18', 'Ký', 'N4'),
    ('認', '8A8D', 'Nhận', 'N3'),
    ('語', '8A9E', 'Ngữ', 'N5'),
    ('説', '8AAC', 'Thuyết', 'N3'),
    ('談', '8AC7', 'Đàm', 'N3'),
    ('講', '8B1B', 'Giảng', 'N2'),
    ('警', '8B66', 'Cảnh', 'N3'),
    ('貯', '8CAF', 'Trữ', 'N3'),
    ('費', '8CBB', 'Phí', 'N3'),
    ('質', '8CEA', 'Chất', 'N5'),
    ('赤', '8D64', 'Xích', NULL),
    ('身', '8EAB', 'Thân', 'N3'),
    ('車', '8ECA', 'Xa', 'N5'),
    ('軍', '8ECD', 'Quân', 'N2'),
    ('農', '8FB2', 'Nông', 'N3'),
    ('近', '8FD1', 'Cận', 'N4'),
    ('追', '8FFD', 'Truy', 'N3'),
    ('通', '901A', 'Thông', 'N4'),
    ('週', '9031', 'Chu', 'N5'),
    ('遊', '904A', 'Du', 'N3'),
    ('過', '904E', 'Quá', 'N3'),
    ('道', '9053', 'Đạo', 'N3'),
    ('適', '9069', 'Thích', 'N3'),
    ('金', '91D1', 'Kim', 'N5'),
    ('針', '91DD', 'Châm', 'N2'),
    ('鉄', '9244', 'Thiết', 'N4'),
    ('銀', '9280', 'Ngân', 'N5'),
    ('長', '9577', 'Trưởng', 'N4'),
    ('門', '9580', 'Môn', 'N3'),
    ('開', '958B', 'Khai', 'N3'),
    ('間', '9593', 'Gian', 'N5'),
    ('関', '95A2', 'Quan', 'N3'),
    ('陰', '9670', 'Âm', 'N1'),
    ('険', '967A', 'Hiểm', 'N3'),
    ('際', '969B', 'Tế', 'N3'),
    ('集', '96C6', 'Tập', 'N2'),
    ('難', '96E3', 'Nan', 'N2'),
    ('電', '96FB', 'Điện', 'N5'),
    ('青', '9752', 'Thanh', 'N3'),
    ('韓', '97D3', 'Hàn', 'N4'),
    ('音', '97F3', 'Âm', NULL),
    ('項', '9805', 'Hạng', 'N1'),
    ('飛', '98DB', 'Phi', 'N4'),
    ('館', '9928', 'Quán', 'N5'),
    ('馬', '99AC', 'Mã', 'N2'),
    ('駅', '99C5', 'Dịch', 'N3'),
    ('験', '9A13', 'Nghiệm', 'N3'),
    ('高', '9AD8', 'Cao', 'N5')
ON DUPLICATE KEY UPDATE han_viet = VALUES(han_viet), jlpt_level = COALESCE(jlpt_level, VALUES(jlpt_level));
INSERT IGNORE INTO kanji_reading (kanji_id, reading_type, reading_text, is_common)
SELECT k.id, 'ON', r.reading_text, r.is_common FROM (
    SELECT '万' AS ch, 'バン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '万' AS ch, 'マン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '三' AS ch, 'サン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '世' AS ch, 'セ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '世' AS ch, 'セイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '中' AS ch, 'ジュウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '中' AS ch, 'チュウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '主' AS ch, 'シュ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '主' AS ch, 'ス' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '予' AS ch, 'ヨ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '二' AS ch, 'ジ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '二' AS ch, 'ニ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '五' AS ch, 'ゴ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '交' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '京' AS ch, 'キョウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '京' AS ch, 'キン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '京' AS ch, 'ケイ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '人' AS ch, 'ジン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '人' AS ch, 'ニン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '令' AS ch, 'レイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '以' AS ch, 'イ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '任' AS ch, 'ニン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '会' AS ch, 'エ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '会' AS ch, 'カイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '体' AS ch, 'タイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '体' AS ch, 'テイ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '作' AS ch, 'サ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '作' AS ch, 'サク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '使' AS ch, 'シ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '億' AS ch, 'オク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '元' AS ch, 'ガン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '元' AS ch, 'ゲン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '先' AS ch, 'セン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '光' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '党' AS ch, 'トウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '入' AS ch, 'ジュ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '入' AS ch, 'ニュウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '八' AS ch, 'ハチ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '公' AS ch, 'ク' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '公' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '六' AS ch, 'ロク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '兼' AS ch, 'ケン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '内' AS ch, 'ダイ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '内' AS ch, 'ナイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '円' AS ch, 'エン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '冷' AS ch, 'レイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '処' AS ch, 'ショ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '出' AS ch, 'シュツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '出' AS ch, 'スイ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '初' AS ch, 'ショ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '別' AS ch, 'ベツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '利' AS ch, 'リ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '劇' AS ch, 'ゲキ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '力' AS ch, 'リキ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '力' AS ch, 'リョク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '加' AS ch, 'カ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '勇' AS ch, 'ユウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '動' AS ch, 'ドウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '化' AS ch, 'カ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '化' AS ch, 'ケ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '北' AS ch, 'ホク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '医' AS ch, 'イ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '十' AS ch, 'ジツ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '十' AS ch, 'ジュウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '半' AS ch, 'ハン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '協' AS ch, 'キョウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '南' AS ch, 'ナ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '南' AS ch, 'ナン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '印' AS ch, 'イン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '参' AS ch, 'サン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '反' AS ch, 'タン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '反' AS ch, 'ハン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '反' AS ch, 'ホン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '口' AS ch, 'ク' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '口' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '古' AS ch, 'コ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '各' AS ch, 'カク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '合' AS ch, 'カツ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '合' AS ch, 'ガツ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '合' AS ch, 'ゴウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '同' AS ch, 'ドウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '名' AS ch, 'ミョウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '名' AS ch, 'メイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '向' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '命' AS ch, 'ミョウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '命' AS ch, 'メイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '和' AS ch, 'オ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '和' AS ch, 'ワ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '品' AS ch, 'ヒン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '員' AS ch, 'イン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '問' AS ch, 'モン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '営' AS ch, 'エイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '国' AS ch, 'コク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '圧' AS ch, 'アツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '場' AS ch, 'ジョウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '変' AS ch, 'ヘン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '外' AS ch, 'ガイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '外' AS ch, 'ゲ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '大' AS ch, 'タイ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '大' AS ch, 'ダイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '天' AS ch, 'テン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '失' AS ch, 'シツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '学' AS ch, 'ガク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '安' AS ch, 'アン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '完' AS ch, 'カン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '定' AS ch, 'ジョウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '定' AS ch, 'テイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '実' AS ch, 'ジツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '客' AS ch, 'カク' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '客' AS ch, 'キャク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '家' AS ch, 'カ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '家' AS ch, 'ケ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '射' AS ch, 'シャ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '山' AS ch, 'サン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '山' AS ch, 'セン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '工' AS ch, 'ク' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '工' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '師' AS ch, 'シ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '席' AS ch, 'セキ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '常' AS ch, 'ジョウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '平' AS ch, 'ビョウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '平' AS ch, 'ヘイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '年' AS ch, 'ネン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '店' AS ch, 'テン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '度' AS ch, 'タク' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '度' AS ch, 'ト' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '度' AS ch, 'ド' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '康' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '式' AS ch, 'シキ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '形' AS ch, 'ギョウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '形' AS ch, 'ケイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '後' AS ch, 'コウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '後' AS ch, 'ゴ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '心' AS ch, 'シン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '念' AS ch, 'ネン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '急' AS ch, 'キュウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '性' AS ch, 'ショウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '性' AS ch, 'セイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '悪' AS ch, 'アク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '悪' AS ch, 'オ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '意' AS ch, 'イ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '感' AS ch, 'カン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '成' AS ch, 'ジョウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '成' AS ch, 'セイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '戦' AS ch, 'セン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '所' AS ch, 'ショ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '手' AS ch, 'シュ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '技' AS ch, 'ギ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '担' AS ch, 'タン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '接' AS ch, 'セツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '放' AS ch, 'ホウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '教' AS ch, 'キョウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '散' AS ch, 'サン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '文' AS ch, 'ブン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '文' AS ch, 'モン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '料' AS ch, 'リョウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '新' AS ch, 'シン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '方' AS ch, 'ホウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '旅' AS ch, 'リョ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '日' AS ch, 'ジツ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '日' AS ch, 'ニチ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '明' AS ch, 'ミョウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '明' AS ch, 'ミン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '明' AS ch, 'メイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '映' AS ch, 'エイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '春' AS ch, 'シュン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '景' AS ch, 'ケイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '暗' AS ch, 'アン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '書' AS ch, 'ショ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '最' AS ch, 'サイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '朝' AS ch, 'チョウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '期' AS ch, 'キ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '期' AS ch, 'ゴ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '未' AS ch, 'ミ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '本' AS ch, 'ホン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '東' AS ch, 'トウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '林' AS ch, 'リン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '果' AS ch, 'カ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '染' AS ch, 'セン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '校' AS ch, 'キョウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '校' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '格' AS ch, 'カク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '格' AS ch, 'キャク' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '格' AS ch, 'コウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '格' AS ch, 'ゴウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '検' AS ch, 'ケン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '業' AS ch, 'ギョウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '業' AS ch, 'ゴウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '様' AS ch, 'ヨウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '歌' AS ch, 'カ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '止' AS ch, 'シ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '正' AS ch, 'ショウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '正' AS ch, 'セイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '歴' AS ch, 'レキ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '母' AS ch, 'ボ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '毎' AS ch, 'マイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '民' AS ch, 'ミン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '気' AS ch, 'キ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '気' AS ch, 'ケ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '永' AS ch, 'エイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '決' AS ch, 'ケツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '油' AS ch, 'ユ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '法' AS ch, 'ハツ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '法' AS ch, 'ホウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '法' AS ch, 'ホツ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '洋' AS ch, 'ヨウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '活' AS ch, 'カツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '海' AS ch, 'カイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '深' AS ch, 'シン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '減' AS ch, 'ゲン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '港' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '湯' AS ch, 'トウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '準' AS ch, 'ジュン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '漢' AS ch, 'カン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '点' AS ch, 'テン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '無' AS ch, 'ブ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '無' AS ch, 'ム' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '然' AS ch, 'ゼン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '然' AS ch, 'ネン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '熱' AS ch, 'ネツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '父' AS ch, 'フ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '牛' AS ch, 'ギュウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '物' AS ch, 'ブツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '物' AS ch, 'モツ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '特' AS ch, 'トク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '王' AS ch, 'オウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '現' AS ch, 'ゲン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '理' AS ch, 'リ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '生' AS ch, 'ショウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '生' AS ch, 'セイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '産' AS ch, 'サン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '用' AS ch, 'ヨウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '由' AS ch, 'ユ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '由' AS ch, 'ユイ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '由' AS ch, 'ユウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '界' AS ch, 'カイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '病' AS ch, 'ビョウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '病' AS ch, 'ヘイ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '発' AS ch, 'ハツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '発' AS ch, 'ホツ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '白' AS ch, 'ハク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '白' AS ch, 'ビャク' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '百' AS ch, 'ヒャク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '皇' AS ch, 'オウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '皇' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '目' AS ch, 'ボク' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '目' AS ch, 'モク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '相' AS ch, 'ショウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '相' AS ch, 'ソウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '真' AS ch, 'シン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '着' AS ch, 'ジャク' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '着' AS ch, 'チャク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '知' AS ch, 'チ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '社' AS ch, 'シャ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '社' AS ch, 'ジャ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '禁' AS ch, 'キン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '科' AS ch, 'カ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '空' AS ch, 'クウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '章' AS ch, 'ショウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '答' AS ch, 'トウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '米' AS ch, 'ベイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '米' AS ch, 'マイ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '精' AS ch, 'ショウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '精' AS ch, 'セイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '約' AS ch, 'ヤク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '級' AS ch, 'キュウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '終' AS ch, 'シュウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '経' AS ch, 'キョウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '経' AS ch, 'ケイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '統' AS ch, 'トウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '総' AS ch, 'ソウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '練' AS ch, 'レン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '美' AS ch, 'ビ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '習' AS ch, 'シュウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '考' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '肉' AS ch, 'ニク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '能' AS ch, 'ノウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '色' AS ch, 'シキ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '色' AS ch, 'ショク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '苦' AS ch, 'ク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '英' AS ch, 'エイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '茶' AS ch, 'サ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '茶' AS ch, 'チャ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '薬' AS ch, 'ヤク' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '行' AS ch, 'アン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '行' AS ch, 'ギョウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '行' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '西' AS ch, 'サイ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '西' AS ch, 'セイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '見' AS ch, 'ケン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '見' AS ch, 'ゲン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '規' AS ch, 'キ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '親' AS ch, 'シン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '解' AS ch, 'カイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '解' AS ch, 'ゲ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '言' AS ch, 'ゲン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '言' AS ch, 'ゴン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '記' AS ch, 'キ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '認' AS ch, 'ニン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '語' AS ch, 'ゴ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '説' AS ch, 'セツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '説' AS ch, 'ゼイ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '談' AS ch, 'ダン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '講' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '警' AS ch, 'ケイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '貯' AS ch, 'チョ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '費' AS ch, 'ヒ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '質' AS ch, 'シチ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '質' AS ch, 'シツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '質' AS ch, 'チ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '赤' AS ch, 'シャク' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '赤' AS ch, 'セキ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '身' AS ch, 'シン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '車' AS ch, 'シャ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '軍' AS ch, 'クン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '軍' AS ch, 'グン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '農' AS ch, 'ノウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '近' AS ch, 'キン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '追' AS ch, 'ツイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '通' AS ch, 'ツ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '通' AS ch, 'ツウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '週' AS ch, 'シュウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '遊' AS ch, 'ユ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '遊' AS ch, 'ユウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '過' AS ch, 'カ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '道' AS ch, 'トウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '道' AS ch, 'ドウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '適' AS ch, 'テキ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '金' AS ch, 'キン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '金' AS ch, 'コン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '針' AS ch, 'シン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '鉄' AS ch, 'テツ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '銀' AS ch, 'ギン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '長' AS ch, 'チョウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '門' AS ch, 'モン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '開' AS ch, 'カイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '間' AS ch, 'カン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '間' AS ch, 'ケン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '関' AS ch, 'カン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '陰' AS ch, 'イン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '険' AS ch, 'ケン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '際' AS ch, 'サイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '集' AS ch, 'シュウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '難' AS ch, 'ナン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '電' AS ch, 'デン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '青' AS ch, 'ショウ' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '青' AS ch, 'セイ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '韓' AS ch, 'カン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '音' AS ch, 'イン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '音' AS ch, 'オン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '項' AS ch, 'コウ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '飛' AS ch, 'ヒ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '館' AS ch, 'カン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '馬' AS ch, 'バ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '駅' AS ch, 'エキ' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '験' AS ch, 'ケン' AS reading_text, 1 AS is_common
    UNION ALL
    SELECT '験' AS ch, 'ゲン' AS reading_text, 0 AS is_common
    UNION ALL
    SELECT '高' AS ch, 'コウ' AS reading_text, 1 AS is_common
) r JOIN kanji k ON k.`character` = r.ch COLLATE utf8mb4_unicode_ci;

DELETE FROM rule_example WHERE rule_id IN (@onyomi_rule_init_c, @onyomi_rule_init_k, @onyomi_rule_init_qu, @onyomi_rule_init_h, @onyomi_rule_init_ng, @onyomi_rule_init_l, @onyomi_rule_init_n, @onyomi_rule_init_nh, @onyomi_rule_init_s, @onyomi_rule_init_t, @onyomi_rule_init_x, @onyomi_rule_init_th, @onyomi_rule_init_tr, @onyomi_rule_init_kh, @onyomi_rule_init_gi, @onyomi_rule_init_b, @onyomi_rule_init_ph, @onyomi_rule_init_m, @onyomi_rule_init_v, @onyomi_rule_init_dd, @onyomi_rule_init_d, @onyomi_rule_init_ch, @onyomi_rule_init_vowel, @onyomi_rule_fin_m, @onyomi_rule_fin_n, @onyomi_rule_fin_c, @onyomi_rule_fin_ch, @onyomi_rule_fin_t, @onyomi_rule_fin_p, @onyomi_rule_rhy_an, @onyomi_rule_rhy_am, @onyomi_rule_rhy_aan, @onyomi_rule_rhy_aam, @onyomi_rule_rhy_ien, @onyomi_rule_rhy_iem, @onyomi_rule_rhy_ang, @onyomi_rule_rhy_uong, @onyomi_rule_rhy_ong, @onyomi_rule_rhy_anh, @onyomi_rule_rhy_enh, @onyomi_rule_rhy_inh);
INSERT INTO rule_example (rule_id, kanji_id, example_expression, example_reading, breakdown, display_order)
SELECT e.rule_id, k.id, e.expression, e.reading, e.breakdown, e.display_order FROM (
    SELECT @onyomi_rule_init_c AS rule_id, '工' AS ch, 'Công' AS expression, 'コウ' AS reading, 'Công → コウ (kou) · 工場 こうじょう: nhà máy' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_c AS rule_id, '高' AS ch, 'Cao' AS expression, 'コウ' AS reading, 'Cao → コウ (kou) · 高校 こうこう: trường cấp ba' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_c AS rule_id, '各' AS ch, 'Các' AS expression, 'カク' AS reading, 'Các → カク (kaku) · 各地 かくち: các nơi' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_c AS rule_id, '級' AS ch, 'Cấp' AS expression, 'キュウ' AS reading, 'Cấp → キュウ (kyuu) · 高級 こうきゅう: cao cấp' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_c AS rule_id, '公' AS ch, 'Công' AS expression, 'コウ' AS reading, 'Công → コウ (kou) · 公園 こうえん: công viên' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_c AS rule_id, '古' AS ch, 'Cổ' AS expression, 'コ' AS reading, 'Cổ → コ (ko) · 古代 こだい: thời cổ đại' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_c AS rule_id, '歌' AS ch, 'Ca' AS expression, 'カ' AS reading, 'Ca → カ (ka) · 歌手 かしゅ: ca sĩ' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_c AS rule_id, '感' AS ch, 'Cảm' AS expression, 'カン' AS reading, 'Cảm → カン (kan) · 感動 かんどう: cảm động' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_k AS rule_id, '金' AS ch, 'Kim' AS expression, 'キン' AS reading, 'Kim → キン (kin) · 料金 りょうきん: tiền phí' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_k AS rule_id, '期' AS ch, 'Kỳ' AS expression, 'キ' AS reading, 'Kỳ → キ (ki) · 期間 きかん: thời hạn' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_k AS rule_id, '京' AS ch, 'Kinh' AS expression, 'キョウ' AS reading, 'Kinh → キョウ (kyou) · 東京 とうきょう: Tokyo' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_k AS rule_id, '記' AS ch, 'Ký' AS expression, 'キ' AS reading, 'Ký → キ (ki) · 日記 にっき: nhật ký' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_k AS rule_id, '見' AS ch, 'Kiến' AS expression, 'ケン' AS reading, 'Kiến → ケン (ken) · 意見 いけん: ý kiến' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_k AS rule_id, '経' AS ch, 'Kinh' AS expression, 'ケイ' AS reading, 'Kinh → ケイ (kei) · 経済 けいざい: kinh tế' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_k AS rule_id, '検' AS ch, 'Kiểm' AS expression, 'ケン' AS reading, 'Kiểm → ケン (ken) · 検査 けんさ: kiểm tra' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_k AS rule_id, '技' AS ch, 'Kỹ' AS expression, 'ギ' AS reading, 'Kỹ → ギ (gi) · 技術 ぎじゅつ: kỹ thuật' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_qu AS rule_id, '国' AS ch, 'Quốc' AS expression, 'コク' AS reading, 'Quốc → コク (koku) · 外国 がいこく: nước ngoài' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_qu AS rule_id, '規' AS ch, 'Quy' AS expression, 'キ' AS reading, 'Quy → キ (ki) · 規則 きそく: quy tắc' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_qu AS rule_id, '決' AS ch, 'Quyết' AS expression, 'ケツ' AS reading, 'Quyết → ケツ (ketsu) · 決定 けってい: quyết định' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_qu AS rule_id, '果' AS ch, 'Quả' AS expression, 'カ' AS reading, 'Quả → カ (ka) · 結果 けっか: kết quả' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_qu AS rule_id, '過' AS ch, 'Quá' AS expression, 'カ' AS reading, 'Quá → カ (ka) · 過去 かこ: quá khứ' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_qu AS rule_id, '館' AS ch, 'Quán' AS expression, 'カン' AS reading, 'Quán → カン (kan) · 図書館 としょかん: thư viện' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_qu AS rule_id, '関' AS ch, 'Quan' AS expression, 'カン' AS reading, 'Quan → カン (kan) · 関係 かんけい: quan hệ' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_qu AS rule_id, '軍' AS ch, 'Quân' AS expression, 'グン' AS reading, 'Quân → グン (gun) · 軍隊 ぐんたい: quân đội' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_h AS rule_id, '学' AS ch, 'Học' AS expression, 'ガク' AS reading, 'Học → ガク (gaku) · 大学 だいがく: đại học' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_h AS rule_id, '海' AS ch, 'Hải' AS expression, 'カイ' AS reading, 'Hải → カイ (kai) · 海外 かいがい: nước ngoài' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_h AS rule_id, '韓' AS ch, 'Hàn' AS expression, 'カン' AS reading, 'Hàn → カン (kan) · 韓国 かんこく: Hàn Quốc' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_h AS rule_id, '現' AS ch, 'Hiện' AS expression, 'ゲン' AS reading, 'Hiện → ゲン (gen) · 現在 げんざい: hiện tại' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_h AS rule_id, '校' AS ch, 'Hiệu' AS expression, 'コウ' AS reading, 'Hiệu → コウ (kou) · 学校 がっこう: trường học' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_h AS rule_id, '会' AS ch, 'Hội' AS expression, 'カイ' AS reading, 'Hội → カイ (kai) · 会社 かいしゃ: công ty' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_h AS rule_id, '化' AS ch, 'Hóa' AS expression, 'カ' AS reading, 'Hóa → カ (ka) · 文化 ぶんか: văn hoá' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_h AS rule_id, '後' AS ch, 'Hậu' AS expression, 'ゴ' AS reading, 'Hậu → ゴ (go) · 午後 ごご: buổi chiều' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ng AS rule_id, '五' AS ch, 'Ngũ' AS expression, 'ゴ' AS reading, 'Ngũ → ゴ (go) · 五月 ごがつ: tháng năm' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ng AS rule_id, '銀' AS ch, 'Ngân' AS expression, 'ギン' AS reading, 'Ngân → ギン (gin) · 銀行 ぎんこう: ngân hàng' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ng AS rule_id, '元' AS ch, 'Nguyên' AS expression, 'ゲン' AS reading, 'Nguyên → ゲン (gen) · 元気 げんき: khoẻ mạnh' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ng AS rule_id, '言' AS ch, 'Ngôn' AS expression, 'ゲン' AS reading, 'Ngôn → ゲン (gen) · 言語 げんご: ngôn ngữ' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ng AS rule_id, '業' AS ch, 'Nghiệp' AS expression, 'ギョウ' AS reading, 'Nghiệp → ギョウ (gyou) · 授業 じゅぎょう: giờ học' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ng AS rule_id, '語' AS ch, 'Ngữ' AS expression, 'ゴ' AS reading, 'Ngữ → ゴ (go) · 日本語 にほんご: tiếng Nhật' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ng AS rule_id, '外' AS ch, 'Ngoại' AS expression, 'ガイ' AS reading, 'Ngoại → ガイ (gai) · 外国 がいこく: nước ngoài' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ng AS rule_id, '牛' AS ch, 'Ngưu' AS expression, 'ギュウ' AS reading, 'Ngưu → ギュウ (gyuu) · 牛肉 ぎゅうにく: thịt bò' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_l AS rule_id, '利' AS ch, 'Lợi' AS expression, 'リ' AS reading, 'Lợi → リ (ri) · 便利 べんり: tiện lợi' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_l AS rule_id, '歴' AS ch, 'Lịch' AS expression, 'レキ' AS reading, 'Lịch → レキ (reki) · 歴史 れきし: lịch sử' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_l AS rule_id, '力' AS ch, 'Lực' AS expression, 'リョク' AS reading, 'Lực → リョク (ryoku) · 協力 きょうりょく: hợp tác' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_l AS rule_id, '冷' AS ch, 'Lãnh' AS expression, 'レイ' AS reading, 'Lãnh → レイ (rei) · 冷静 れいせい: bình tĩnh' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_l AS rule_id, '旅' AS ch, 'Lữ' AS expression, 'リョ' AS reading, 'Lữ → リョ (ryo) · 旅行 りょこう: du lịch' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_l AS rule_id, '料' AS ch, 'Liệu' AS expression, 'リョウ' AS reading, 'Liệu → リョウ (ryou) · 料理 りょうり: món ăn' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_l AS rule_id, '理' AS ch, 'Lý' AS expression, 'リ' AS reading, 'Lý → リ (ri) · 理由 りゆう: lý do' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_l AS rule_id, '練' AS ch, 'Luyện' AS expression, 'レン' AS reading, 'Luyện → レン (ren) · 練習 れんしゅう: luyện tập' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_n AS rule_id, '南' AS ch, 'Nam' AS expression, 'ナン' AS reading, 'Nam → ナン (nan) · 南極 なんきょく: Nam Cực' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_n AS rule_id, '内' AS ch, 'Nội' AS expression, 'ナイ' AS reading, 'Nội → ナイ (nai) · 案内 あんない: hướng dẫn' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_n AS rule_id, '年' AS ch, 'Niên' AS expression, 'ネン' AS reading, 'Niên → ネン (nen) · 来年 らいねん: năm sau' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_n AS rule_id, '能' AS ch, 'Năng' AS expression, 'ノウ' AS reading, 'Năng → ノウ (nou) · 能力 のうりょく: năng lực' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_n AS rule_id, '農' AS ch, 'Nông' AS expression, 'ノウ' AS reading, 'Nông → ノウ (nou) · 農業 のうぎょう: nông nghiệp' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_n AS rule_id, '難' AS ch, 'Nan' AS expression, 'ナン' AS reading, 'Nan → ナン (nan) · 困難 こんなん: khó khăn' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_n AS rule_id, '念' AS ch, 'Niệm' AS expression, 'ネン' AS reading, 'Niệm → ネン (nen) · 記念 きねん: kỷ niệm' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_nh AS rule_id, '二' AS ch, 'Nhị' AS expression, 'ニ' AS reading, 'Nhị → ニ (ni) · 二月 にがつ: tháng hai' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_nh AS rule_id, '人' AS ch, 'Nhân' AS expression, 'ジン' AS reading, 'Nhân → ジン (jin) · 日本人 にほんじん: người Nhật' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_nh AS rule_id, '日' AS ch, 'Nhật' AS expression, 'ニチ' AS reading, 'Nhật → ニチ (nichi) · 毎日 まいにち: mỗi ngày' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_nh AS rule_id, '入' AS ch, 'Nhập' AS expression, 'ニュウ' AS reading, 'Nhập → ニュウ (nyuu) · 入学 にゅうがく: nhập học' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_nh AS rule_id, '認' AS ch, 'Nhận' AS expression, 'ニン' AS reading, 'Nhận → ニン (nin) · 確認 かくにん: xác nhận' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_nh AS rule_id, '熱' AS ch, 'Nhiệt' AS expression, 'ネツ' AS reading, 'Nhiệt → ネツ (netsu) · 熱心 ねっしん: nhiệt tình' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_nh AS rule_id, '任' AS ch, 'Nhiệm' AS expression, 'ニン' AS reading, 'Nhiệm → ニン (nin) · 責任 せきにん: trách nhiệm' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_nh AS rule_id, '肉' AS ch, 'Nhục' AS expression, 'ニク' AS reading, 'Nhục → ニク (niku) · 牛肉 ぎゅうにく: thịt bò' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_s AS rule_id, '生' AS ch, 'Sinh' AS expression, 'セイ' AS reading, 'Sinh → セイ (sei) · 学生 がくせい: học sinh' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_s AS rule_id, '色' AS ch, 'Sắc' AS expression, 'ショク' AS reading, 'Sắc → ショク (shoku) · 特色 とくしょく: đặc sắc' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_s AS rule_id, '山' AS ch, 'Sơn' AS expression, 'サン' AS reading, 'Sơn → サン (san) · 富士山 ふじさん: núi Phú Sĩ' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_s AS rule_id, '産' AS ch, 'Sản' AS expression, 'サン' AS reading, 'Sản → サン (san) · 生産 せいさん: sản xuất' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_s AS rule_id, '使' AS ch, 'Sử' AS expression, 'シ' AS reading, 'Sử → シ (shi) · 使用 しよう: sử dụng' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_s AS rule_id, '初' AS ch, 'Sơ' AS expression, 'ショ' AS reading, 'Sơ → ショ (sho) · 最初 さいしょ: ban đầu' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_s AS rule_id, '所' AS ch, 'Sở' AS expression, 'ショ' AS reading, 'Sở → ショ (sho) · 住所 じゅうしょ: địa chỉ' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_s AS rule_id, '師' AS ch, 'Sư' AS expression, 'シ' AS reading, 'Sư → シ (shi) · 教師 きょうし: giáo viên' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_t AS rule_id, '先' AS ch, 'Tiên' AS expression, 'セン' AS reading, 'Tiên → セン (sen) · 先生 せんせい: giáo viên' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_t AS rule_id, '西' AS ch, 'Tây' AS expression, 'セイ' AS reading, 'Tây → セイ (sei) · 西洋 せいよう: phương Tây' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_t AS rule_id, '三' AS ch, 'Tam' AS expression, 'サン' AS reading, 'Tam → サン (san) · 三月 さんがつ: tháng ba' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_t AS rule_id, '最' AS ch, 'Tối' AS expression, 'サイ' AS reading, 'Tối → サイ (sai) · 最後 さいご: cuối cùng' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_t AS rule_id, '作' AS ch, 'Tác' AS expression, 'サク' AS reading, 'Tác → サク (saku) · 作文 さくぶん: bài văn' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_t AS rule_id, '総' AS ch, 'Tổng' AS expression, 'ソウ' AS reading, 'Tổng → ソウ (sou) · 総理 そうり: thủ tướng' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_t AS rule_id, '際' AS ch, 'Tế' AS expression, 'サイ' AS reading, 'Tế → サイ (sai) · 国際 こくさい: quốc tế' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_t AS rule_id, '心' AS ch, 'Tâm' AS expression, 'シン' AS reading, 'Tâm → シン (shin) · 安心 あんしん: yên tâm' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_x AS rule_id, '車' AS ch, 'Xa' AS expression, 'シャ' AS reading, 'Xa → シャ (sha) · 自転車 じてんしゃ: xe đạp' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_x AS rule_id, '射' AS ch, 'Xạ' AS expression, 'シャ' AS reading, 'Xạ → シャ (sha) · 注射 ちゅうしゃ: tiêm' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_x AS rule_id, '社' AS ch, 'Xã' AS expression, 'シャ' AS reading, 'Xã → シャ (sha) · 会社 かいしゃ: công ty' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_x AS rule_id, '春' AS ch, 'Xuân' AS expression, 'シュン' AS reading, 'Xuân → シュン (shun) · 青春 せいしゅん: tuổi xuân' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_x AS rule_id, '出' AS ch, 'Xuất' AS expression, 'シュツ' AS reading, 'Xuất → シュツ (shutsu) · 輸出 ゆしゅつ: xuất khẩu' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_x AS rule_id, '処' AS ch, 'Xử' AS expression, 'ショ' AS reading, 'Xử → ショ (sho) · 処理 しょり: xử lý' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_th AS rule_id, '失' AS ch, 'Thất' AS expression, 'シツ' AS reading, 'Thất → シツ (shitsu) · 失礼 しつれい: thất lễ' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_th AS rule_id, '世' AS ch, 'Thế' AS expression, 'セイ' AS reading, 'Thế → セイ (sei) · 世紀 せいき: thế kỷ' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_th AS rule_id, '手' AS ch, 'Thủ' AS expression, 'シュ' AS reading, 'Thủ → シュ (shu) · 選手 せんしゅ: tuyển thủ' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_th AS rule_id, '書' AS ch, 'Thư' AS expression, 'ショ' AS reading, 'Thư → ショ (sho) · 辞書 じしょ: từ điển' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_th AS rule_id, '天' AS ch, 'Thiên' AS expression, 'テン' AS reading, 'Thiên → テン (ten) · 天気 てんき: thời tiết' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_th AS rule_id, '体' AS ch, 'Thể' AS expression, 'タイ' AS reading, 'Thể → タイ (tai) · 体育 たいいく: thể dục' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_th AS rule_id, '通' AS ch, 'Thông' AS expression, 'ツウ' AS reading, 'Thông → ツウ (tsuu) · 交通 こうつう: giao thông' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_th AS rule_id, '鉄' AS ch, 'Thiết' AS expression, 'テツ' AS reading, 'Thiết → テツ (tetsu) · 地下鉄 ちかてつ: tàu điện ngầm' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_tr AS rule_id, '長' AS ch, 'Trưởng' AS expression, 'チョウ' AS reading, 'Trưởng → チョウ (chou) · 社長 しゃちょう: giám đốc' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_tr AS rule_id, '中' AS ch, 'Trung' AS expression, 'チュウ' AS reading, 'Trung → チュウ (chuu) · 中国 ちゅうごく: Trung Quốc' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_tr AS rule_id, '茶' AS ch, 'Trà' AS expression, 'チャ' AS reading, 'Trà → チャ (cha) · 紅茶 こうちゃ: trà đen' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_tr AS rule_id, '着' AS ch, 'Trước' AS expression, 'チャク' AS reading, 'Trước → チャク (chaku) · 到着 とうちゃく: đến nơi' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_tr AS rule_id, '貯' AS ch, 'Trữ' AS expression, 'チョ' AS reading, 'Trữ → チョ (cho) · 貯金 ちょきん: tiết kiệm tiền' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_tr AS rule_id, '朝' AS ch, 'Triều' AS expression, 'チョウ' AS reading, 'Triều → チョウ (chou) · 朝食 ちょうしょく: bữa sáng' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_tr AS rule_id, '知' AS ch, 'Tri' AS expression, 'チ' AS reading, 'Tri → チ (chi) · 知識 ちしき: tri thức' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_tr AS rule_id, '追' AS ch, 'Truy' AS expression, 'ツイ' AS reading, 'Truy → ツイ (tsui) · 追加 ついか: bổ sung' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_kh AS rule_id, '開' AS ch, 'Khai' AS expression, 'カイ' AS reading, 'Khai → カイ (kai) · 開始 かいし: bắt đầu' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_kh AS rule_id, '科' AS ch, 'Khoa' AS expression, 'カ' AS reading, 'Khoa → カ (ka) · 科学 かがく: khoa học' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_kh AS rule_id, '空' AS ch, 'Không' AS expression, 'クウ' AS reading, 'Không → クウ (kuu) · 空港 くうこう: sân bay' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_kh AS rule_id, '気' AS ch, 'Khí' AS expression, 'キ' AS reading, 'Khí → キ (ki) · 天気 てんき: thời tiết' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_kh AS rule_id, '口' AS ch, 'Khẩu' AS expression, 'コウ' AS reading, 'Khẩu → コウ (kou) · 人口 じんこう: dân số' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_kh AS rule_id, '考' AS ch, 'Khảo' AS expression, 'コウ' AS reading, 'Khảo → コウ (kou) · 参考 さんこう: tham khảo' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_kh AS rule_id, '客' AS ch, 'Khách' AS expression, 'キャク' AS reading, 'Khách → キャク (kyaku) · 観客 かんきゃく: khán giả' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_kh AS rule_id, '苦' AS ch, 'Khổ' AS expression, 'ク' AS reading, 'Khổ → ク (ku) · 苦労 くろう: vất vả' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_gi AS rule_id, '家' AS ch, 'Gia' AS expression, 'カ' AS reading, 'Gia → カ (ka) · 家族 かぞく: gia đình' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_gi AS rule_id, '間' AS ch, 'Gian' AS expression, 'カン' AS reading, 'Gian → カン (kan) · 時間 じかん: thời gian' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_gi AS rule_id, '交' AS ch, 'Giao' AS expression, 'コウ' AS reading, 'Giao → コウ (kou) · 交通 こうつう: giao thông' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_gi AS rule_id, '教' AS ch, 'Giáo' AS expression, 'キョウ' AS reading, 'Giáo → キョウ (kyou) · 教室 きょうしつ: phòng học' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_gi AS rule_id, '解' AS ch, 'Giải' AS expression, 'カイ' AS reading, 'Giải → カイ (kai) · 理解 りかい: hiểu' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_gi AS rule_id, '加' AS ch, 'Gia' AS expression, 'カ' AS reading, 'Gia → カ (ka) · 参加 さんか: tham gia' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_gi AS rule_id, '減' AS ch, 'Giảm' AS expression, 'ゲン' AS reading, 'Giảm → ゲン (gen) · 減少 げんしょう: giảm bớt' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_gi AS rule_id, '界' AS ch, 'Giới' AS expression, 'カイ' AS reading, 'Giới → カイ (kai) · 世界 せかい: thế giới' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_b AS rule_id, '北' AS ch, 'Bắc' AS expression, 'ホク' AS reading, 'Bắc → ホク (hoku) · 東北 とうほく: Đông Bắc' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_b AS rule_id, '本' AS ch, 'Bản' AS expression, 'ホン' AS reading, 'Bản → ホン (hon) · 日本 にほん: Nhật Bản' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_b AS rule_id, '半' AS ch, 'Bán' AS expression, 'ハン' AS reading, 'Bán → ハン (han) · 半分 はんぶん: một nửa' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_b AS rule_id, '百' AS ch, 'Bách' AS expression, 'ヒャク' AS reading, 'Bách → ヒャク (hyaku) · 百円 ひゃくえん: một trăm yên' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_b AS rule_id, '平' AS ch, 'Bình' AS expression, 'ヘイ' AS reading, 'Bình → ヘイ (hei) · 平和 へいわ: hoà bình' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_b AS rule_id, '病' AS ch, 'Bệnh' AS expression, 'ビョウ' AS reading, 'Bệnh → ビョウ (byou) · 病院 びょういん: bệnh viện' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_b AS rule_id, '別' AS ch, 'Biệt' AS expression, 'ベツ' AS reading, 'Biệt → ベツ (betsu) · 特別 とくべつ: đặc biệt' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_b AS rule_id, '変' AS ch, 'Biến' AS expression, 'ヘン' AS reading, 'Biến → ヘン (hen) · 大変 たいへん: vất vả' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ph AS rule_id, '方' AS ch, 'Phương' AS expression, 'ホウ' AS reading, 'Phương → ホウ (hou) · 方法 ほうほう: phương pháp' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ph AS rule_id, '法' AS ch, 'Pháp' AS expression, 'ホウ' AS reading, 'Pháp → ホウ (hou) · 文法 ぶんぽう: ngữ pháp' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ph AS rule_id, '反' AS ch, 'Phản' AS expression, 'ハン' AS reading, 'Phản → ハン (han) · 反対 はんたい: phản đối' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ph AS rule_id, '発' AS ch, 'Phát' AS expression, 'ハツ' AS reading, 'Phát → ハツ (hatsu) · 出発 しゅっぱつ: xuất phát' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ph AS rule_id, '父' AS ch, 'Phụ' AS expression, 'フ' AS reading, 'Phụ → フ (fu) · 祖父 そふ: ông' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ph AS rule_id, '放' AS ch, 'Phóng' AS expression, 'ホウ' AS reading, 'Phóng → ホウ (hou) · 放送 ほうそう: phát sóng' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ph AS rule_id, '飛' AS ch, 'Phi' AS expression, 'ヒ' AS reading, 'Phi → ヒ (hi) · 飛行機 ひこうき: máy bay' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ph AS rule_id, '費' AS ch, 'Phí' AS expression, 'ヒ' AS reading, 'Phí → ヒ (hi) · 費用 ひよう: chi phí' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_m AS rule_id, '門' AS ch, 'Môn' AS expression, 'モン' AS reading, 'Môn → モン (mon) · 専門 せんもん: chuyên môn' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_m AS rule_id, '馬' AS ch, 'Mã' AS expression, 'バ' AS reading, 'Mã → バ (ba) · 馬車 ばしゃ: xe ngựa' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_m AS rule_id, '目' AS ch, 'Mục' AS expression, 'モク' AS reading, 'Mục → モク (moku) · 目的 もくてき: mục đích' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_m AS rule_id, '母' AS ch, 'Mẫu' AS expression, 'ボ' AS reading, 'Mẫu → ボ (bo) · 父母 ふぼ: cha mẹ' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_m AS rule_id, '明' AS ch, 'Minh' AS expression, 'メイ' AS reading, 'Minh → メイ (mei) · 説明 せつめい: giải thích' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_m AS rule_id, '米' AS ch, 'Mễ' AS expression, 'ベイ' AS reading, 'Mễ → ベイ (bei) · 欧米 おうべい: Âu Mỹ' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_m AS rule_id, '美' AS ch, 'Mỹ' AS expression, 'ビ' AS reading, 'Mỹ → ビ (bi) · 美術 びじゅつ: mỹ thuật' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_m AS rule_id, '毎' AS ch, 'Mỗi' AS expression, 'マイ' AS reading, 'Mỗi → マイ (mai) · 毎日 まいにち: mỗi ngày' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_v AS rule_id, '文' AS ch, 'Văn' AS expression, 'ブン' AS reading, 'Văn → ブン (bun) · 文化 ぶんか: văn hoá' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_v AS rule_id, '問' AS ch, 'Vấn' AS expression, 'モン' AS reading, 'Vấn → モン (mon) · 質問 しつもん: câu hỏi' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_v AS rule_id, '物' AS ch, 'Vật' AS expression, 'ブツ' AS reading, 'Vật → ブツ (butsu) · 動物 どうぶつ: động vật' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_v AS rule_id, '万' AS ch, 'Vạn' AS expression, 'マン' AS reading, 'Vạn → マン (man) · 一万 いちまん: mười nghìn' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_v AS rule_id, '未' AS ch, 'Vị' AS expression, 'ミ' AS reading, 'Vị → ミ (mi) · 未来 みらい: tương lai' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_v AS rule_id, '無' AS ch, 'Vô' AS expression, 'ム' AS reading, 'Vô → ム (mu) · 無理 むり: quá sức' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_v AS rule_id, '円' AS ch, 'Viên' AS expression, 'エン' AS reading, 'Viên → エン (en) · 百円 ひゃくえん: một trăm yên' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_v AS rule_id, '王' AS ch, 'Vương' AS expression, 'オウ' AS reading, 'Vương → オウ (ou) · 王様 おうさま: nhà vua' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_dd AS rule_id, '大' AS ch, 'Đại' AS expression, 'ダイ' AS reading, 'Đại → ダイ (dai) · 大学 だいがく: đại học' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_dd AS rule_id, '東' AS ch, 'Đông' AS expression, 'トウ' AS reading, 'Đông → トウ (tou) · 東京 とうきょう: Tokyo' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_dd AS rule_id, '電' AS ch, 'Điện' AS expression, 'デン' AS reading, 'Điện → デン (den) · 電話 でんわ: điện thoại' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_dd AS rule_id, '度' AS ch, 'Độ' AS expression, 'ド' AS reading, 'Độ → ド (do) · 今度 こんど: lần tới' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_dd AS rule_id, '道' AS ch, 'Đạo' AS expression, 'ドウ' AS reading, 'Đạo → ドウ (dou) · 道具 どうぐ: dụng cụ' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_dd AS rule_id, '動' AS ch, 'Động' AS expression, 'ドウ' AS reading, 'Động → ドウ (dou) · 運動 うんどう: vận động' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_dd AS rule_id, '店' AS ch, 'Điếm' AS expression, 'テン' AS reading, 'Điếm → テン (ten) · 書店 しょてん: hiệu sách' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_dd AS rule_id, '特' AS ch, 'Đặc' AS expression, 'トク' AS reading, 'Đặc → トク (toku) · 特別 とくべつ: đặc biệt' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_d AS rule_id, '薬' AS ch, 'Dược' AS expression, 'ヤク' AS reading, 'Dược → ヤク (yaku) · 薬品 やくひん: dược phẩm' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_d AS rule_id, '用' AS ch, 'Dụng' AS expression, 'ヨウ' AS reading, 'Dụng → ヨウ (you) · 用意 ようい: chuẩn bị' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_d AS rule_id, '遊' AS ch, 'Du' AS expression, 'ユウ' AS reading, 'Du → ユウ (yuu) · 遊園地 ゆうえんち: công viên giải trí' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_d AS rule_id, '由' AS ch, 'Do' AS expression, 'ユウ' AS reading, 'Do → ユウ (yuu) · 自由 じゆう: tự do' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_d AS rule_id, '勇' AS ch, 'Dũng' AS expression, 'ユウ' AS reading, 'Dũng → ユウ (yuu) · 勇気 ゆうき: dũng khí' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_d AS rule_id, '様' AS ch, 'Dạng' AS expression, 'ヨウ' AS reading, 'Dạng → ヨウ (you) · 様子 ようす: tình hình' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_d AS rule_id, '予' AS ch, 'Dự' AS expression, 'ヨ' AS reading, 'Dự → ヨ (yo) · 予定 よてい: dự định' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_d AS rule_id, '油' AS ch, 'Du' AS expression, 'ユ' AS reading, 'Du → ユ (yu) · 石油 せきゆ: dầu mỏ' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ch AS rule_id, '正' AS ch, 'Chính' AS expression, 'セイ' AS reading, 'Chính → セイ (sei) · 正確 せいかく: chính xác' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ch AS rule_id, '主' AS ch, 'Chủ' AS expression, 'シュ' AS reading, 'Chủ → シュ (shu) · 主人 しゅじん: chồng; chủ nhà' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ch AS rule_id, '終' AS ch, 'Chung' AS expression, 'シュウ' AS reading, 'Chung → シュウ (shuu) · 終了 しゅうりょう: kết thúc' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ch AS rule_id, '止' AS ch, 'Chỉ' AS expression, 'シ' AS reading, 'Chỉ → シ (shi) · 中止 ちゅうし: huỷ bỏ' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ch AS rule_id, '週' AS ch, 'Chu' AS expression, 'シュウ' AS reading, 'Chu → シュウ (shuu) · 毎週 まいしゅう: hằng tuần' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ch AS rule_id, '真' AS ch, 'Chân' AS expression, 'シン' AS reading, 'Chân → シン (shin) · 写真 しゃしん: ảnh chụp' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ch AS rule_id, '戦' AS ch, 'Chiến' AS expression, 'セン' AS reading, 'Chiến → セン (sen) · 戦争 せんそう: chiến tranh' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_ch AS rule_id, '質' AS ch, 'Chất' AS expression, 'シツ' AS reading, 'Chất → シツ (shitsu) · 質問 しつもん: câu hỏi' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_vowel AS rule_id, '安' AS ch, 'An' AS expression, 'アン' AS reading, 'An → アン (an) · 安全 あんぜん: an toàn' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_vowel AS rule_id, '意' AS ch, 'Ý' AS expression, 'イ' AS reading, 'Ý → イ (i) · 意見 いけん: ý kiến' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_vowel AS rule_id, '億' AS ch, 'Ức' AS expression, 'オク' AS reading, 'Ức → オク (oku) · 一億 いちおく: một trăm triệu' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_vowel AS rule_id, '約' AS ch, 'Ước' AS expression, 'ヤク' AS reading, 'Ước → ヤク (yaku) · 予約 よやく: đặt trước' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_vowel AS rule_id, '英' AS ch, 'Anh' AS expression, 'エイ' AS reading, 'Anh → エイ (ei) · 英語 えいご: tiếng Anh' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_vowel AS rule_id, '印' AS ch, 'Ấn' AS expression, 'イン' AS reading, 'Ấn → イン (in) · 印象 いんしょう: ấn tượng' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_vowel AS rule_id, '悪' AS ch, 'Ác' AS expression, 'アク' AS reading, 'Ác → アク (aku) · 最悪 さいあく: tồi tệ nhất' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_init_vowel AS rule_id, '医' AS ch, 'Y' AS expression, 'イ' AS reading, 'Y → イ (i) · 医者 いしゃ: bác sĩ' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_m AS rule_id, '三' AS ch, 'Tam' AS expression, 'サン' AS reading, 'Tam → サン (san) · 三月 さんがつ: tháng ba' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_m AS rule_id, '心' AS ch, 'Tâm' AS expression, 'シン' AS reading, 'Tâm → シン (shin) · 安心 あんしん: yên tâm' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_m AS rule_id, '金' AS ch, 'Kim' AS expression, 'キン' AS reading, 'Kim → キン (kin) · 料金 りょうきん: tiền phí' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_m AS rule_id, '感' AS ch, 'Cảm' AS expression, 'カン' AS reading, 'Cảm → カン (kan) · 感動 かんどう: cảm động' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_m AS rule_id, '暗' AS ch, 'Ám' AS expression, 'アン' AS reading, 'Ám → アン (an) · 暗記 あんき: học thuộc lòng' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_m AS rule_id, '林' AS ch, 'Lâm' AS expression, 'リン' AS reading, 'Lâm → リン (rin) · 森林 しんりん: rừng' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_m AS rule_id, '談' AS ch, 'Đàm' AS expression, 'ダン' AS reading, 'Đàm → ダン (dan) · 相談 そうだん: trao đổi' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_m AS rule_id, '点' AS ch, 'Điểm' AS expression, 'テン' AS reading, 'Điểm → テン (ten) · 点数 てんすう: điểm số' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_n AS rule_id, '文' AS ch, 'Văn' AS expression, 'ブン' AS reading, 'Văn → ブン (bun) · 文化 ぶんか: văn hoá' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_n AS rule_id, '員' AS ch, 'Viên' AS expression, 'イン' AS reading, 'Viên → イン (in) · 会員 かいいん: hội viên' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_n AS rule_id, '山' AS ch, 'Sơn' AS expression, 'サン' AS reading, 'Sơn → サン (san) · 富士山 ふじさん: núi Phú Sĩ' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_n AS rule_id, '本' AS ch, 'Bản' AS expression, 'ホン' AS reading, 'Bản → ホン (hon) · 日本 にほん: Nhật Bản' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_n AS rule_id, '新' AS ch, 'Tân' AS expression, 'シン' AS reading, 'Tân → シン (shin) · 新聞 しんぶん: báo' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_n AS rule_id, '安' AS ch, 'An' AS expression, 'アン' AS reading, 'An → アン (an) · 安全 あんぜん: an toàn' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_n AS rule_id, '完' AS ch, 'Hoàn' AS expression, 'カン' AS reading, 'Hoàn → カン (kan) · 完全 かんぜん: hoàn toàn' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_n AS rule_id, '問' AS ch, 'Vấn' AS expression, 'モン' AS reading, 'Vấn → モン (mon) · 質問 しつもん: câu hỏi' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_c AS rule_id, '悪' AS ch, 'Ác' AS expression, 'アク' AS reading, 'Ác → アク (aku) · 最悪 さいあく: tồi tệ nhất' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_c AS rule_id, '薬' AS ch, 'Dược' AS expression, 'ヤク' AS reading, 'Dược → ヤク (yaku) · 薬品 やくひん: dược phẩm' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_c AS rule_id, '国' AS ch, 'Quốc' AS expression, 'コク' AS reading, 'Quốc → コク (koku) · 外国 がいこく: nước ngoài' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_c AS rule_id, '学' AS ch, 'Học' AS expression, 'ガク' AS reading, 'Học → ガク (gaku) · 大学 だいがく: đại học' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_c AS rule_id, '力' AS ch, 'Lực' AS expression, 'リョク' AS reading, 'Lực → リョク (ryoku) · 協力 きょうりょく: hợp tác' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_c AS rule_id, '目' AS ch, 'Mục' AS expression, 'モク' AS reading, 'Mục → モク (moku) · 目的 もくてき: mục đích' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_c AS rule_id, '特' AS ch, 'Đặc' AS expression, 'トク' AS reading, 'Đặc → トク (toku) · 特別 とくべつ: đặc biệt' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_c AS rule_id, '六' AS ch, 'Lục' AS expression, 'ロク' AS reading, 'Lục → ロク (roku) · 六月 ろくがつ: tháng sáu' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_ch AS rule_id, '歴' AS ch, 'Lịch' AS expression, 'レキ' AS reading, 'Lịch → レキ (reki) · 歴史 れきし: lịch sử' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_ch AS rule_id, '適' AS ch, 'Thích' AS expression, 'テキ' AS reading, 'Thích → テキ (teki) · 適当 てきとう: thích hợp' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_ch AS rule_id, '席' AS ch, 'Tịch' AS expression, 'セキ' AS reading, 'Tịch → セキ (seki) · 出席 しゅっせき: có mặt' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_ch AS rule_id, '劇' AS ch, 'Kịch' AS expression, 'ゲキ' AS reading, 'Kịch → ゲキ (geki) · 劇場 げきじょう: nhà hát' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_ch AS rule_id, '駅' AS ch, 'Dịch' AS expression, 'エキ' AS reading, 'Dịch → エキ (eki) · 駅員 えきいん: nhân viên nhà ga' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_ch AS rule_id, '白' AS ch, 'Bạch' AS expression, 'ハク' AS reading, 'Bạch → ハク (haku) · 白紙 はくし: giấy trắng' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_ch AS rule_id, '客' AS ch, 'Khách' AS expression, 'キャク' AS reading, 'Khách → キャク (kyaku) · 観客 かんきゃく: khán giả' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_ch AS rule_id, '格' AS ch, 'Cách' AS expression, 'カク' AS reading, 'Cách → カク (kaku) · 合格 ごうかく: thi đỗ' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_t AS rule_id, '鉄' AS ch, 'Thiết' AS expression, 'テツ' AS reading, 'Thiết → テツ (tetsu) · 地下鉄 ちかてつ: tàu điện ngầm' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_t AS rule_id, '熱' AS ch, 'Nhiệt' AS expression, 'ネツ' AS reading, 'Nhiệt → ネツ (netsu) · 熱心 ねっしん: nhiệt tình' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_t AS rule_id, '決' AS ch, 'Quyết' AS expression, 'ケツ' AS reading, 'Quyết → ケツ (ketsu) · 決定 けってい: quyết định' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_t AS rule_id, '発' AS ch, 'Phát' AS expression, 'ハツ' AS reading, 'Phát → ハツ (hatsu) · 出発 しゅっぱつ: xuất phát' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_t AS rule_id, '活' AS ch, 'Hoạt' AS expression, 'カツ' AS reading, 'Hoạt → カツ (katsu) · 生活 せいかつ: cuộc sống' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_t AS rule_id, '説' AS ch, 'Thuyết' AS expression, 'セツ' AS reading, 'Thuyết → セツ (setsu) · 小説 しょうせつ: tiểu thuyết' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_t AS rule_id, '日' AS ch, 'Nhật' AS expression, 'ニチ' AS reading, 'Nhật → ニチ (nichi) · 毎日 まいにち: mỗi ngày' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_t AS rule_id, '八' AS ch, 'Bát' AS expression, 'ハチ' AS reading, 'Bát → ハチ (hachi) · 八月 はちがつ: tháng tám' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_p AS rule_id, '十' AS ch, 'Thập' AS expression, 'ジュウ' AS reading, 'Thập → ジュウ (juu) · 十分 じゅうぶん: đủ' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_p AS rule_id, '急' AS ch, 'Cấp' AS expression, 'キュウ' AS reading, 'Cấp → キュウ (kyuu) · 急行 きゅうこう: tàu nhanh' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_p AS rule_id, '法' AS ch, 'Pháp' AS expression, 'ホウ' AS reading, 'Pháp → ホウ (hou) · 方法 ほうほう: phương pháp' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_p AS rule_id, '合' AS ch, 'Hợp' AS expression, 'ゴウ' AS reading, 'Hợp → ゴウ (gou) · 合格 ごうかく: thi đỗ' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_p AS rule_id, '集' AS ch, 'Tập' AS expression, 'シュウ' AS reading, 'Tập → シュウ (shuu) · 集中 しゅうちゅう: tập trung' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_p AS rule_id, '習' AS ch, 'Tập' AS expression, 'シュウ' AS reading, 'Tập → シュウ (shuu) · 練習 れんしゅう: luyện tập' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_p AS rule_id, '協' AS ch, 'Hiệp' AS expression, 'キョウ' AS reading, 'Hiệp → キョウ (kyou) · 協力 きょうりょく: hợp tác' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_fin_p AS rule_id, '答' AS ch, 'Đáp' AS expression, 'トウ' AS reading, 'Đáp → トウ (tou) · 回答 かいとう: câu trả lời' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_an AS rule_id, '安' AS ch, 'An' AS expression, 'アン' AS reading, 'An → アン (an) · 安全 あんぜん: an toàn' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_an AS rule_id, '間' AS ch, 'Gian' AS expression, 'カン' AS reading, 'Gian → カン (kan) · 時間 じかん: thời gian' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_an AS rule_id, '漢' AS ch, 'Hán' AS expression, 'カン' AS reading, 'Hán → カン (kan) · 漢字 かんじ: chữ Hán' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_an AS rule_id, '反' AS ch, 'Phản' AS expression, 'ハン' AS reading, 'Phản → ハン (han) · 反対 はんたい: phản đối' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_an AS rule_id, '半' AS ch, 'Bán' AS expression, 'ハン' AS reading, 'Bán → ハン (han) · 半分 はんぶん: một nửa' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_an AS rule_id, '館' AS ch, 'Quán' AS expression, 'カン' AS reading, 'Quán → カン (kan) · 図書館 としょかん: thư viện' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_an AS rule_id, '完' AS ch, 'Hoàn' AS expression, 'カン' AS reading, 'Hoàn → カン (kan) · 完全 かんぜん: hoàn toàn' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_an AS rule_id, '散' AS ch, 'Tán' AS expression, 'サン' AS reading, 'Tán → サン (san) · 散歩 さんぽ: đi dạo' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_am AS rule_id, '三' AS ch, 'Tam' AS expression, 'サン' AS reading, 'Tam → サン (san) · 三月 さんがつ: tháng ba' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_am AS rule_id, '感' AS ch, 'Cảm' AS expression, 'カン' AS reading, 'Cảm → カン (kan) · 感動 かんどう: cảm động' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_am AS rule_id, '暗' AS ch, 'Ám' AS expression, 'アン' AS reading, 'Ám → アン (an) · 暗記 あんき: học thuộc lòng' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_am AS rule_id, '談' AS ch, 'Đàm' AS expression, 'ダン' AS reading, 'Đàm → ダン (dan) · 相談 そうだん: trao đổi' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_am AS rule_id, '南' AS ch, 'Nam' AS expression, 'ナン' AS reading, 'Nam → ナン (nan) · 南極 なんきょく: Nam Cực' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_am AS rule_id, '担' AS ch, 'Đảm' AS expression, 'タン' AS reading, 'Đảm → タン (tan) · 担当 たんとう: phụ trách' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_am AS rule_id, '参' AS ch, 'Tham' AS expression, 'サン' AS reading, 'Tham → サン (san) · 参加 さんか: tham gia' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aan AS rule_id, '印' AS ch, 'Ấn' AS expression, 'イン' AS reading, 'Ấn → イン (in) · 印象 いんしょう: ấn tượng' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aan AS rule_id, '近' AS ch, 'Cận' AS expression, 'キン' AS reading, 'Cận → キン (kin) · 近所 きんじょ: hàng xóm' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aan AS rule_id, '新' AS ch, 'Tân' AS expression, 'シン' AS reading, 'Tân → シン (shin) · 新聞 しんぶん: báo' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aan AS rule_id, '身' AS ch, 'Thân' AS expression, 'シン' AS reading, 'Thân → シン (shin) · 自身 じしん: bản thân' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aan AS rule_id, '民' AS ch, 'Dân' AS expression, 'ミン' AS reading, 'Dân → ミン (min) · 国民 こくみん: quốc dân' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aan AS rule_id, '真' AS ch, 'Chân' AS expression, 'シン' AS reading, 'Chân → シン (shin) · 写真 しゃしん: ảnh chụp' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aan AS rule_id, '親' AS ch, 'Thân' AS expression, 'シン' AS reading, 'Thân → シン (shin) · 親切 しんせつ: tử tế' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aan AS rule_id, '人' AS ch, 'Nhân' AS expression, 'ジン' AS reading, 'Nhân → ジン (jin) · 日本人 にほんじん: người Nhật' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aam AS rule_id, '心' AS ch, 'Tâm' AS expression, 'シン' AS reading, 'Tâm → シン (shin) · 安心 あんしん: yên tâm' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aam AS rule_id, '林' AS ch, 'Lâm' AS expression, 'リン' AS reading, 'Lâm → リン (rin) · 森林 しんりん: rừng' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aam AS rule_id, '陰' AS ch, 'Âm' AS expression, 'イン' AS reading, 'Âm → イン (in) · 陰気 いんき: u ám' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aam AS rule_id, '深' AS ch, 'Thâm' AS expression, 'シン' AS reading, 'Thâm → シン (shin) · 深刻 しんこく: nghiêm trọng' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aam AS rule_id, '針' AS ch, 'Châm' AS expression, 'シン' AS reading, 'Châm → シン (shin) · 方針 ほうしん: phương châm' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aam AS rule_id, '禁' AS ch, 'Cấm' AS expression, 'キン' AS reading, 'Cấm → キン (kin) · 禁止 きんし: cấm' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_aam AS rule_id, '品' AS ch, 'Phẩm' AS expression, 'ヒン' AS reading, 'Phẩm → ヒン (hin) · 作品 さくひん: tác phẩm' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ien AS rule_id, '円' AS ch, 'Viên' AS expression, 'エン' AS reading, 'Viên → エン (en) · 百円 ひゃくえん: một trăm yên' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ien AS rule_id, '変' AS ch, 'Biến' AS expression, 'ヘン' AS reading, 'Biến → ヘン (hen) · 大変 たいへん: vất vả' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ien AS rule_id, '天' AS ch, 'Thiên' AS expression, 'テン' AS reading, 'Thiên → テン (ten) · 天気 てんき: thời tiết' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ien AS rule_id, '先' AS ch, 'Tiên' AS expression, 'セン' AS reading, 'Tiên → セン (sen) · 先生 せんせい: giáo viên' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ien AS rule_id, '現' AS ch, 'Hiện' AS expression, 'ゲン' AS reading, 'Hiện → ゲン (gen) · 現在 げんざい: hiện tại' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ien AS rule_id, '電' AS ch, 'Điện' AS expression, 'デン' AS reading, 'Điện → デン (den) · 電話 でんわ: điện thoại' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ien AS rule_id, '練' AS ch, 'Luyện' AS expression, 'レン' AS reading, 'Luyện → レン (ren) · 練習 れんしゅう: luyện tập' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ien AS rule_id, '見' AS ch, 'Kiến' AS expression, 'ケン' AS reading, 'Kiến → ケン (ken) · 意見 いけん: ý kiến' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_iem AS rule_id, '店' AS ch, 'Điếm' AS expression, 'テン' AS reading, 'Điếm → テン (ten) · 書店 しょてん: hiệu sách' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_iem AS rule_id, '兼' AS ch, 'Kiêm' AS expression, 'ケン' AS reading, 'Kiêm → ケン (ken)' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_iem AS rule_id, '点' AS ch, 'Điểm' AS expression, 'テン' AS reading, 'Điểm → テン (ten) · 点数 てんすう: điểm số' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_iem AS rule_id, '検' AS ch, 'Kiểm' AS expression, 'ケン' AS reading, 'Kiểm → ケン (ken) · 検査 けんさ: kiểm tra' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_iem AS rule_id, '険' AS ch, 'Hiểm' AS expression, 'ケン' AS reading, 'Hiểm → ケン (ken) · 危険 きけん: nguy hiểm' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_iem AS rule_id, '験' AS ch, 'Nghiệm' AS expression, 'ケン' AS reading, 'Nghiệm → ケン (ken) · 経験 けいけん: kinh nghiệm' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_iem AS rule_id, '染' AS ch, 'Nhiễm' AS expression, 'セン' AS reading, 'Nhiễm → セン (sen) · 感染 かんせん: lây nhiễm' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_iem AS rule_id, '念' AS ch, 'Niệm' AS expression, 'ネン' AS reading, 'Niệm → ネン (nen) · 記念 きねん: kỷ niệm' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ang AS rule_id, '光' AS ch, 'Quang' AS expression, 'コウ' AS reading, 'Quang → コウ (kou) · 観光 かんこう: tham quan du lịch' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ang AS rule_id, '港' AS ch, 'Cảng' AS expression, 'コウ' AS reading, 'Cảng → コウ (kou) · 空港 くうこう: sân bay' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ang AS rule_id, '康' AS ch, 'Khang' AS expression, 'コウ' AS reading, 'Khang → コウ (kou) · 健康 けんこう: sức khoẻ' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ang AS rule_id, '党' AS ch, 'Đảng' AS expression, 'トウ' AS reading, 'Đảng → トウ (tou) · 政党 せいとう: chính đảng' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ang AS rule_id, '講' AS ch, 'Giảng' AS expression, 'コウ' AS reading, 'Giảng → コウ (kou) · 講義 こうぎ: bài giảng' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ang AS rule_id, '皇' AS ch, 'Hoàng' AS expression, 'コウ' AS reading, 'Hoàng → コウ (kou) · 皇居 こうきょ: hoàng cung' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ang AS rule_id, '項' AS ch, 'Hạng' AS expression, 'コウ' AS reading, 'Hạng → コウ (kou) · 項目 こうもく: hạng mục' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ang AS rule_id, '湯' AS ch, 'Thang' AS expression, 'トウ' AS reading, 'Thang → トウ (tou) · 銭湯 せんとう: nhà tắm công cộng' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_uong AS rule_id, '長' AS ch, 'Trưởng' AS expression, 'チョウ' AS reading, 'Trưởng → チョウ (chou) · 社長 しゃちょう: giám đốc' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_uong AS rule_id, '章' AS ch, 'Chương' AS expression, 'ショウ' AS reading, 'Chương → ショウ (shou) · 文章 ぶんしょう: văn bản' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_uong AS rule_id, '常' AS ch, 'Thường' AS expression, 'ジョウ' AS reading, 'Thường → ジョウ (jou) · 日常 にちじょう: thường ngày' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_uong AS rule_id, '方' AS ch, 'Phương' AS expression, 'ホウ' AS reading, 'Phương → ホウ (hou) · 方法 ほうほう: phương pháp' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_uong AS rule_id, '場' AS ch, 'Trường' AS expression, 'ジョウ' AS reading, 'Trường → ジョウ (jou) · 会場 かいじょう: hội trường' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_uong AS rule_id, '相' AS ch, 'Tương' AS expression, 'ソウ' AS reading, 'Tương → ソウ (sou) · 相談 そうだん: trao đổi' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_uong AS rule_id, '向' AS ch, 'Hướng' AS expression, 'コウ' AS reading, 'Hướng → コウ (kou) · 方向 ほうこう: phương hướng' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_uong AS rule_id, '洋' AS ch, 'Dương' AS expression, 'ヨウ' AS reading, 'Dương → ヨウ (you) · 西洋 せいよう: phương Tây' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ong AS rule_id, '工' AS ch, 'Công' AS expression, 'コウ' AS reading, 'Công → コウ (kou) · 工場 こうじょう: nhà máy' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ong AS rule_id, '総' AS ch, 'Tổng' AS expression, 'ソウ' AS reading, 'Tổng → ソウ (sou) · 総理 そうり: thủ tướng' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ong AS rule_id, '統' AS ch, 'Thống' AS expression, 'トウ' AS reading, 'Thống → トウ (tou) · 伝統 でんとう: truyền thống' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ong AS rule_id, '東' AS ch, 'Đông' AS expression, 'トウ' AS reading, 'Đông → トウ (tou) · 東京 とうきょう: Tokyo' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ong AS rule_id, '公' AS ch, 'Công' AS expression, 'コウ' AS reading, 'Công → コウ (kou) · 公園 こうえん: công viên' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ong AS rule_id, '同' AS ch, 'Đồng' AS expression, 'ドウ' AS reading, 'Đồng → ドウ (dou) · 同時 どうじ: đồng thời' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ong AS rule_id, '通' AS ch, 'Thông' AS expression, 'ツウ' AS reading, 'Thông → ツウ (tsuu) · 交通 こうつう: giao thông' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_ong AS rule_id, '空' AS ch, 'Không' AS expression, 'クウ' AS reading, 'Không → クウ (kuu) · 空港 くうこう: sân bay' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_anh AS rule_id, '青' AS ch, 'Thanh' AS expression, 'セイ' AS reading, 'Thanh → セイ (sei) · 青年 せいねん: thanh niên' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_anh AS rule_id, '英' AS ch, 'Anh' AS expression, 'エイ' AS reading, 'Anh → エイ (ei) · 英語 えいご: tiếng Anh' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_anh AS rule_id, '名' AS ch, 'Danh' AS expression, 'メイ' AS reading, 'Danh → メイ (mei) · 有名 ゆうめい: nổi tiếng' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_anh AS rule_id, '成' AS ch, 'Thành' AS expression, 'セイ' AS reading, 'Thành → セイ (sei) · 成功 せいこう: thành công' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_anh AS rule_id, '景' AS ch, 'Cảnh' AS expression, 'ケイ' AS reading, 'Cảnh → ケイ (kei) · 景気 けいき: tình hình kinh tế' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_anh AS rule_id, '映' AS ch, 'Ánh' AS expression, 'エイ' AS reading, 'Ánh → エイ (ei) · 映画 えいが: phim' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_anh AS rule_id, '警' AS ch, 'Cảnh' AS expression, 'ケイ' AS reading, 'Cảnh → ケイ (kei) · 警察 けいさつ: cảnh sát' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_anh AS rule_id, '営' AS ch, 'Doanh' AS expression, 'エイ' AS reading, 'Doanh → エイ (ei) · 経営 けいえい: kinh doanh' AS breakdown, 8 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_enh AS rule_id, '命' AS ch, 'Mệnh' AS expression, 'メイ' AS reading, 'Mệnh → メイ (mei) · 生命 せいめい: sinh mệnh' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_enh AS rule_id, '令' AS ch, 'Lệnh' AS expression, 'レイ' AS reading, 'Lệnh → レイ (rei) · 命令 めいれい: mệnh lệnh' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_inh AS rule_id, '生' AS ch, 'Sinh' AS expression, 'セイ' AS reading, 'Sinh → セイ (sei) · 学生 がくせい: học sinh' AS breakdown, 1 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_inh AS rule_id, '精' AS ch, 'Tinh' AS expression, 'セイ' AS reading, 'Tinh → セイ (sei) · 精神 せいしん: tinh thần' AS breakdown, 2 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_inh AS rule_id, '定' AS ch, 'Định' AS expression, 'テイ' AS reading, 'Định → テイ (tei) · 予定 よてい: dự định' AS breakdown, 3 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_inh AS rule_id, '平' AS ch, 'Bình' AS expression, 'ヘイ' AS reading, 'Bình → ヘイ (hei) · 平和 へいわ: hoà bình' AS breakdown, 4 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_inh AS rule_id, '明' AS ch, 'Minh' AS expression, 'メイ' AS reading, 'Minh → メイ (mei) · 説明 せつめい: giải thích' AS breakdown, 5 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_inh AS rule_id, '形' AS ch, 'Hình' AS expression, 'ケイ' AS reading, 'Hình → ケイ (kei) · 図形 ずけい: hình vẽ' AS breakdown, 6 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_inh AS rule_id, '性' AS ch, 'Tính' AS expression, 'セイ' AS reading, 'Tính → セイ (sei) · 性格 せいかく: tính cách' AS breakdown, 7 AS display_order
    UNION ALL
    SELECT @onyomi_rule_rhy_inh AS rule_id, '永' AS ch, 'Vĩnh' AS expression, 'エイ' AS reading, 'Vĩnh → エイ (ei) · 永遠 えいえん: vĩnh viễn' AS breakdown, 8 AS display_order
) e JOIN kanji k ON k.`character` = e.ch COLLATE utf8mb4_unicode_ci;

INSERT INTO assessment_question (conversion_rule_id, knowledge_tag_id, skill, jlpt_level, difficulty,
    question_type, question_text, answer_text, option_1, option_2, option_3, option_4, correct_option,
    explanation, context_json, quiz_id, order_index, title) VALUES
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 工場 (Công Trường) đọc là gì?', 'こうじょう', 'ごうじょう', 'ほうじょう', 'そうじょう', 'こうじょう', 4, '工場 = 工 (Công → こう) + 場 (Trường → じょう) → こうじょう: nhà máy. 工 (Công) → こう theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "工", "hanViet": "Công", "reading": "コウ", "romaji": "kou", "focus": "工場"}', @onyomi_quiz_1_1, 1, 'onyomi:INIT_C:工:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 高校 (Cao Hiệu) đọc là gì?', 'こうこう', 'そうこう', 'こうこう', 'ほうこう', 'ごうこう', 2, '高校 = 高 (Cao → こう) + 校 (Hiệu → こう) → こうこう: trường cấp ba. 高 (Cao) → こう theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "高", "hanViet": "Cao", "reading": "コウ", "romaji": "kou", "focus": "高校"}', @onyomi_quiz_1_1, 2, 'onyomi:INIT_C:高:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 各地 (Các Địa) đọc là gì?', 'かくち', 'はくち', 'がくち', 'さくち', 'かくち', 4, '各地 = 各 (Các → かく) + 地 (Địa → ち) → かくち: các nơi. 各 (Các) → かく theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "各", "hanViet": "Các", "reading": "カク", "romaji": "kaku", "focus": "各地"}', @onyomi_quiz_1_1, 3, 'onyomi:INIT_C:各:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 高級 (Cao Cấp) đọc là gì?', 'こうきゅう', 'こうぎゅう', 'こうひゅう', 'こうきゅう', 'こうしゅう', 3, '高級 = 高 (Cao → こう) + 級 (Cấp → きゅう) → こうきゅう: cao cấp. 級 (Cấp) → きゅう theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "級", "hanViet": "Cấp", "reading": "キュウ", "romaji": "kyuu", "focus": "高級"}', @onyomi_quiz_1_1, 4, 'onyomi:INIT_C:級:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 公園 (Công Viên) đọc là gì?', 'こうえん', 'こうえん', 'そうえん', 'ほうえん', 'ごうえん', 1, '公園 = 公 (Công → こう) + 園 (Viên → えん) → こうえん: công viên. 公 (Công) → こう theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "公", "hanViet": "Công", "reading": "コウ", "romaji": "kou", "focus": "公園"}', @onyomi_quiz_1_1, 5, 'onyomi:INIT_C:公:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 古代 (Cổ Đại) đọc là gì?', 'こだい', 'そだい', 'ごだい', 'ほだい', 'こだい', 4, '古代 = 古 (Cổ → こ) + 代 (Đại → だい) → こだい: thời cổ đại. 古 (Cổ) → こ theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "古", "hanViet": "Cổ", "reading": "コ", "romaji": "ko", "focus": "古代"}', @onyomi_quiz_1_1, 6, 'onyomi:INIT_C:古:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 歌手 (Ca Thủ) đọc là gì?', 'かしゅ', 'かしゅ', 'さしゅ', 'はしゅ', 'がしゅ', 1, '歌手 = 歌 (Ca → か) + 手 (Thủ → しゅ) → かしゅ: ca sĩ. 歌 (Ca) → か theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "歌", "hanViet": "Ca", "reading": "カ", "romaji": "ka", "focus": "歌手"}', @onyomi_quiz_1_1, 7, 'onyomi:INIT_C:歌:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 感動 (Cảm Động) đọc là gì?', 'かんどう', 'さんどう', 'はんどう', 'かんどう', 'がんどう', 3, '感動 = 感 (Cảm → かん) + 動 (Động → どう) → かんどう: cảm động. 感 (Cảm) → かん theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "感", "hanViet": "Cảm", "reading": "カン", "romaji": "kan", "focus": "感動"}', @onyomi_quiz_1_1, 8, 'onyomi:INIT_C:感:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 料金 (Liệu Kim) đọc là gì?', 'りょうきん', 'りょうきん', 'りょうしん', 'りょうぎん', 'りょうひん', 1, '料金 = 料 (Liệu → りょう) + 金 (Kim → きん) → りょうきん: tiền phí. 金 (Kim) → きん theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "金", "hanViet": "Kim", "reading": "キン", "romaji": "kin", "focus": "料金"}', @onyomi_quiz_1_1, 9, 'onyomi:INIT_K:金:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 期間 (Kỳ Gian) đọc là gì?', 'きかん', 'ひかん', 'きかん', 'しかん', 'ぎかん', 2, '期間 = 期 (Kỳ → き) + 間 (Gian → かん) → きかん: thời hạn. 期 (Kỳ) → き theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "期", "hanViet": "Kỳ", "reading": "キ", "romaji": "ki", "focus": "期間"}', @onyomi_quiz_1_1, 10, 'onyomi:INIT_K:期:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 東京 (Đông Kinh) đọc là gì?', 'とうきょう', 'とうきょう', 'とうひょう', 'とうぎょう', 'とうしょう', 1, '東京 = 東 (Đông → とう) + 京 (Kinh → きょう) → とうきょう: Tokyo. 京 (Kinh) → きょう theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "京", "hanViet": "Kinh", "reading": "キョウ", "romaji": "kyou", "focus": "東京"}', @onyomi_quiz_1_1, 11, 'onyomi:INIT_K:京:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 日記 (Nhật Ký) đọc là gì?', 'にっき', 'にっし', 'にっち', 'にっぴ', 'にっき', 4, '日記 = 日 (Nhật → にっ) + 記 (Ký → き) → にっき: nhật ký. 記 (Ký) → き theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "記", "hanViet": "Ký", "reading": "キ", "romaji": "ki", "focus": "日記"}', @onyomi_quiz_1_1, 12, 'onyomi:INIT_K:記:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 意見 (Ý Kiến) đọc là gì?', 'いけん', 'いけん', 'いへん', 'いせん', 'いてん', 1, '意見 = 意 (Ý → い) + 見 (Kiến → けん) → いけん: ý kiến. 見 (Kiến) → けん theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "見", "hanViet": "Kiến", "reading": "ケン", "romaji": "ken", "focus": "意見"}', @onyomi_quiz_1_1, 13, 'onyomi:INIT_K:見:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 経済 (Kinh Tế) đọc là gì?', 'けいざい', 'へいざい', 'けいざい', 'せいざい', 'げいざい', 2, '経済 = 経 (Kinh → けい) + 済 (Tế → ざい) → けいざい: kinh tế. 経 (Kinh) → けい theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "経", "hanViet": "Kinh", "reading": "ケイ", "romaji": "kei", "focus": "経済"}', @onyomi_quiz_1_1, 14, 'onyomi:INIT_K:経:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 検査 (Kiểm Tra) đọc là gì?', 'けんさ', 'へんさ', 'けんさ', 'せんさ', 'げんさ', 2, '検査 = 検 (Kiểm → けん) + 査 (Tra → さ) → けんさ: kiểm tra. 検 (Kiểm) → けん theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "検", "hanViet": "Kiểm", "reading": "ケン", "romaji": "ken", "focus": "検査"}', @onyomi_quiz_1_1, 15, 'onyomi:INIT_K:検:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 技術 (Kỹ Thuật) đọc là gì?', 'ぎじゅつ', 'ちじゅつ', 'しじゅつ', 'ぎじゅつ', 'ひじゅつ', 3, '技術 = 技 (Kỹ → ぎ) + 術 (Thuật → じゅつ) → ぎじゅつ: kỹ thuật. 技 (Kỹ) → ぎ theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G). Lưu ý: K thành G (thêm dấu ゛): ギ.', '{"type": "WORD", "rule": "INIT_K", "kanji": "技", "hanViet": "Kỹ", "reading": "ギ", "romaji": "gi", "focus": "技術"}', @onyomi_quiz_1_1, 16, 'onyomi:INIT_K:技:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 外国 (Ngoại Quốc) đọc là gì?', 'がいこく', 'がいほく', 'がいそく', 'がいごく', 'がいこく', 4, '外国 = 外 (Ngoại → がい) + 国 (Quốc → こく) → がいこく: nước ngoài. 国 (Quốc) → こく theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "国", "hanViet": "Quốc", "reading": "コク", "romaji": "koku", "focus": "外国"}', @onyomi_quiz_1_1, 17, 'onyomi:INIT_QU:国:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 規則 (Quy Tắc) đọc là gì?', 'きそく', 'ひそく', 'しそく', 'ぎそく', 'きそく', 4, '規則 = 規 (Quy → き) + 則 (Tắc → そく) → きそく: quy tắc. 規 (Quy) → き theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "規", "hanViet": "Quy", "reading": "キ", "romaji": "ki", "focus": "規則"}', @onyomi_quiz_1_1, 18, 'onyomi:INIT_QU:規:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 決定 (Quyết Định) đọc là gì?', 'けってい', 'けってい', 'せってい', 'げってい', 'へってい', 1, '決定 = 決 (Quyết → けっ) + 定 (Định → てい) → けってい: quyết định. 決 (Quyết) → けつ theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G). Trong từ này 決 đọc thành けっ (vốn là けつ).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "決", "hanViet": "Quyết", "reading": "ケツ", "romaji": "ketsu", "focus": "決定"}', @onyomi_quiz_1_1, 19, 'onyomi:INIT_QU:決:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 結果 (Kết Quả) đọc là gì?', 'けっか', 'けっさ', 'けっぱ', 'けった', 'けっか', 4, '結果 = 結 (Kết → けっ) + 果 (Quả → か) → けっか: kết quả. 果 (Quả) → か theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "果", "hanViet": "Quả", "reading": "カ", "romaji": "ka", "focus": "結果"}', @onyomi_quiz_1_1, 20, 'onyomi:INIT_QU:果:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 過去 (Quá Khứ) đọc là gì?', 'かこ', 'はこ', 'かこ', 'さこ', 'がこ', 2, '過去 = 過 (Quá → か) + 去 (Khứ → こ) → かこ: quá khứ. 過 (Quá) → か theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "過", "hanViet": "Quá", "reading": "カ", "romaji": "ka", "focus": "過去"}', @onyomi_quiz_1_1, 21, 'onyomi:INIT_QU:過:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 図書館 (Đồ Thư Quán) đọc là gì?', 'としょかん', 'としょがん', 'としょさん', 'としょはん', 'としょかん', 4, '図書館 = 図 (Đồ → と) + 書 (Thư → しょ) + 館 (Quán → かん) → としょかん: thư viện. 館 (Quán) → かん theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "館", "hanViet": "Quán", "reading": "カン", "romaji": "kan", "focus": "図書館"}', @onyomi_quiz_1_1, 22, 'onyomi:INIT_QU:館:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 関係 (Quan Hệ) đọc là gì?', 'かんけい', 'はんけい', 'さんけい', 'かんけい', 'がんけい', 3, '関係 = 関 (Quan → かん) + 係 (Hệ → けい) → かんけい: quan hệ. 関 (Quan) → かん theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "関", "hanViet": "Quan", "reading": "カン", "romaji": "kan", "focus": "関係"}', @onyomi_quiz_1_1, 23, 'onyomi:INIT_QU:関:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 軍隊 (Quân Đội) đọc là gì?', 'ぐんたい', 'ふんたい', 'すんたい', 'ぐんたい', 'つんたい', 3, '軍隊 = 軍 (Quân → ぐん) + 隊 (Đội → たい) → ぐんたい: quân đội. 軍 (Quân) → ぐん theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G). Lưu ý: QU thành G (thêm dấu ゛): グ.', '{"type": "WORD", "rule": "INIT_QU", "kanji": "軍", "hanViet": "Quân", "reading": "グン", "romaji": "gun", "focus": "軍隊"}', @onyomi_quiz_1_1, 24, 'onyomi:INIT_QU:軍:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 大学 (Đại Học) đọc là gì?', 'だいがく', 'だいさく', 'だいなく', 'だいはく', 'だいがく', 4, '大学 = 大 (Đại → だい) + 学 (Học → がく) → だいがく: đại học. 学 (Học) → がく theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "学", "hanViet": "Học", "reading": "ガク", "romaji": "gaku", "focus": "大学"}', @onyomi_quiz_1_2, 1, 'onyomi:INIT_H:学:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 海外 (Hải Ngoại) đọc là gì?', 'かいがい', 'かいがい', 'はいがい', 'さいがい', 'ないがい', 1, '海外 = 海 (Hải → かい) + 外 (Ngoại → がい) → かいがい: nước ngoài. 海 (Hải) → かい theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "海", "hanViet": "Hải", "reading": "カイ", "romaji": "kai", "focus": "海外"}', @onyomi_quiz_1_2, 2, 'onyomi:INIT_H:海:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 韓国 (Hàn Quốc) đọc là gì?', 'かんこく', 'かんこく', 'さんこく', 'はんこく', 'なんこく', 1, '韓国 = 韓 (Hàn → かん) + 国 (Quốc → こく) → かんこく: Hàn Quốc. 韓 (Hàn) → かん theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "韓", "hanViet": "Hàn", "reading": "カン", "romaji": "kan", "focus": "韓国"}', @onyomi_quiz_1_2, 3, 'onyomi:INIT_H:韓:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 現在 (Hiện Tại) đọc là gì?', 'げんざい', 'ねんざい', 'げんざい', 'へんざい', 'せんざい', 2, '現在 = 現 (Hiện → げん) + 在 (Tại → ざい) → げんざい: hiện tại. 現 (Hiện) → げん theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "現", "hanViet": "Hiện", "reading": "ゲン", "romaji": "gen", "focus": "現在"}', @onyomi_quiz_1_2, 4, 'onyomi:INIT_H:現:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 学校 (Học Hiệu) đọc là gì?', 'がっこう', 'がっとう', 'がっこう', 'がっそう', 'がっぽう', 2, '学校 = 学 (Học → がっ) + 校 (Hiệu → こう) → がっこう: trường học. 校 (Hiệu) → こう theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "校", "hanViet": "Hiệu", "reading": "コウ", "romaji": "kou", "focus": "学校"}', @onyomi_quiz_1_2, 5, 'onyomi:INIT_H:校:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 会社 (Hội Xã) đọc là gì?', 'かいしゃ', 'さいしゃ', 'かいしゃ', 'ないしゃ', 'はいしゃ', 2, '会社 = 会 (Hội → かい) + 社 (Xã → しゃ) → かいしゃ: công ty. 会 (Hội) → かい theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "会", "hanViet": "Hội", "reading": "カイ", "romaji": "kai", "focus": "会社"}', @onyomi_quiz_1_2, 6, 'onyomi:INIT_H:会:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 文化 (Văn Hóa) đọc là gì?', 'ぶんか', 'ぶんは', 'ぶんな', 'ぶんさ', 'ぶんか', 4, '文化 = 文 (Văn → ぶん) + 化 (Hóa → か) → ぶんか: văn hoá. 化 (Hóa) → か theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "化", "hanViet": "Hóa", "reading": "カ", "romaji": "ka", "focus": "文化"}', @onyomi_quiz_1_2, 7, 'onyomi:INIT_H:化:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 午後 (Ngọ Hậu) đọc là gì?', 'ごご', 'ごの', 'ごほ', 'ごご', 'ごそ', 3, '午後 = 午 (Ngọ → ご) + 後 (Hậu → ご) → ごご: buổi chiều. 後 (Hậu) → ご theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "後", "hanViet": "Hậu", "reading": "ゴ", "romaji": "go", "focus": "午後"}', @onyomi_quiz_1_2, 8, 'onyomi:INIT_H:後:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 平和 (Bình Hòa) đọc là gì?', 'へいわ', 'へいは', 'へいが', 'へいわ', 'へいか', 3, '平和 = 平 (Bình → へい) + 和 (Hòa → わ) → へいわ: hoà bình. 和 (Hòa) là ngoại lệ của quy tắc Âm đầu H → K/G: 和 đọc ワ: phụ âm H mất hẳn thay vì thành K/G.', '{"type": "EXCEPTION", "rule": "INIT_H", "kanji": "和", "hanViet": "Hòa", "reading": "ワ", "romaji": "wa", "note": "和 đọc ワ: phụ âm H mất hẳn thay vì thành K/G.", "word": "平和", "wordReading": "へいわ", "meaning": "hoà bình", "focus": "平和"}', @onyomi_quiz_1_2, 9, 'onyomi:INIT_H:和:EXCEPTION'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 五月 (Ngũ Nguyệt) đọc là gì?', 'ごがつ', 'こがつ', 'ごがつ', 'きょがつ', 'のがつ', 2, '五月 = 五 (Ngũ → ご) + 月 (Nguyệt → がつ) → ごがつ: tháng năm. 五 (Ngũ) → ご theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "五", "hanViet": "Ngũ", "reading": "ゴ", "romaji": "go", "focus": "五月"}', @onyomi_quiz_1_2, 10, 'onyomi:INIT_NG:五:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 銀行 (Ngân Hàng) đọc là gì?', 'ぎんこう', 'みんこう', 'にんこう', 'きんこう', 'ぎんこう', 4, '銀行 = 銀 (Ngân → ぎん) + 行 (Hàng → こう) → ぎんこう: ngân hàng. 銀 (Ngân) → ぎん theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "銀", "hanViet": "Ngân", "reading": "ギン", "romaji": "gin", "focus": "銀行"}', @onyomi_quiz_1_2, 11, 'onyomi:INIT_NG:銀:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 元気 (Nguyên Khí) đọc là gì?', 'げんき', 'げんき', 'けんき', 'めんき', 'ねんき', 1, '元気 = 元 (Nguyên → げん) + 気 (Khí → き) → げんき: khoẻ mạnh. 元 (Nguyên) → げん theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "元", "hanViet": "Nguyên", "reading": "ゲン", "romaji": "gen", "focus": "元気"}', @onyomi_quiz_1_2, 12, 'onyomi:INIT_NG:元:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 言語 (Ngôn Ngữ) đọc là gì?', 'げんご', 'けんご', 'げんご', 'ねんご', 'めんご', 2, '言語 = 言 (Ngôn → げん) + 語 (Ngữ → ご) → げんご: ngôn ngữ. 言 (Ngôn) → げん theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "言", "hanViet": "Ngôn", "reading": "ゲン", "romaji": "gen", "focus": "言語"}', @onyomi_quiz_1_2, 13, 'onyomi:INIT_NG:言:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 授業 (Thụ Nghiệp) đọc là gì?', 'じゅぎょう', 'じゅにょう', 'じゅきょう', 'じゅぎょう', 'じゅみょう', 3, '授業 = 授 (Thụ → じゅ) + 業 (Nghiệp → ぎょう) → じゅぎょう: giờ học. 業 (Nghiệp) → ぎょう theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "業", "hanViet": "Nghiệp", "reading": "ギョウ", "romaji": "gyou", "focus": "授業"}', @onyomi_quiz_1_2, 14, 'onyomi:INIT_NG:業:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 日本語 (Nhật Bản Ngữ) đọc là gì?', 'にほんご', 'にほんこ', 'にほんの', 'にほんきょ', 'にほんご', 4, '日本語 = 日 (Nhật → に) + 本 (Bản → ほん) + 語 (Ngữ → ご) → にほんご: tiếng Nhật. 語 (Ngữ) → ご theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "語", "hanViet": "Ngữ", "reading": "ゴ", "romaji": "go", "focus": "日本語"}', @onyomi_quiz_1_2, 15, 'onyomi:INIT_NG:語:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 外国 (Ngoại Quốc) đọc là gì?', 'がいこく', 'きゃいこく', 'がいこく', 'かいこく', 'ないこく', 2, '外国 = 外 (Ngoại → がい) + 国 (Quốc → こく) → がいこく: nước ngoài. 外 (Ngoại) → がい theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "外", "hanViet": "Ngoại", "reading": "ガイ", "romaji": "gai", "focus": "外国"}', @onyomi_quiz_1_2, 16, 'onyomi:INIT_NG:外:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 牛肉 (Ngưu Nhục) đọc là gì?', 'ぎゅうにく', 'きゅうにく', 'みゅうにく', 'ぎゅうにく', 'にゅうにく', 3, '牛肉 = 牛 (Ngưu → ぎゅう) + 肉 (Nhục → にく) → ぎゅうにく: thịt bò. 牛 (Ngưu) → ぎゅう theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "牛", "hanViet": "Ngưu", "reading": "ギュウ", "romaji": "gyuu", "focus": "牛肉"}', @onyomi_quiz_1_2, 17, 'onyomi:INIT_NG:牛:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 便利 (Tiện Lợi) đọc là gì?', 'べんり', 'べんじ', 'べんき', 'べんに', 'べんり', 4, '便利 = 便 (Tiện → べん) + 利 (Lợi → り) → べんり: tiện lợi. 利 (Lợi) → り theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "利", "hanViet": "Lợi", "reading": "リ", "romaji": "ri", "focus": "便利"}', @onyomi_quiz_1_3, 1, 'onyomi:INIT_L:利:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 歴史 (Lịch Sử) đọc là gì?', 'れきし', 'ぜきし', 'ねきし', 'れきし', 'けきし', 3, '歴史 = 歴 (Lịch → れき) + 史 (Sử → し) → れきし: lịch sử. 歴 (Lịch) → れき theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "歴", "hanViet": "Lịch", "reading": "レキ", "romaji": "reki", "focus": "歴史"}', @onyomi_quiz_1_3, 2, 'onyomi:INIT_L:歴:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 協力 (Hiệp Lực) đọc là gì?', 'きょうりょく', 'きょうりょく', 'きょうきょく', 'きょうにょく', 'きょうじょく', 1, '協力 = 協 (Hiệp → きょう) + 力 (Lực → りょく) → きょうりょく: hợp tác. 力 (Lực) → りょく theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "力", "hanViet": "Lực", "reading": "リョク", "romaji": "ryoku", "focus": "協力"}', @onyomi_quiz_1_3, 3, 'onyomi:INIT_L:力:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 冷静 (Lãnh Tĩnh) đọc là gì?', 'れいせい', 'れいせい', 'けいせい', 'ねいせい', 'ぜいせい', 1, '冷静 = 冷 (Lãnh → れい) + 静 (Tĩnh → せい) → れいせい: bình tĩnh. 冷 (Lãnh) → れい theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "冷", "hanViet": "Lãnh", "reading": "レイ", "romaji": "rei", "focus": "冷静"}', @onyomi_quiz_1_3, 4, 'onyomi:INIT_L:冷:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 旅行 (Lữ Hành) đọc là gì?', 'りょこう', 'きょこう', 'にょこう', 'りょこう', 'じょこう', 3, '旅行 = 旅 (Lữ → りょ) + 行 (Hành → こう) → りょこう: du lịch. 旅 (Lữ) → りょ theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "旅", "hanViet": "Lữ", "reading": "リョ", "romaji": "ryo", "focus": "旅行"}', @onyomi_quiz_1_3, 5, 'onyomi:INIT_L:旅:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 料理 (Liệu Lý) đọc là gì?', 'りょうり', 'きょうり', 'りょうり', 'にょうり', 'じょうり', 2, '料理 = 料 (Liệu → りょう) + 理 (Lý → り) → りょうり: món ăn. 料 (Liệu) → りょう theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "料", "hanViet": "Liệu", "reading": "リョウ", "romaji": "ryou", "focus": "料理"}', @onyomi_quiz_1_3, 6, 'onyomi:INIT_L:料:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 理由 (Lý Do) đọc là gì?', 'りゆう', 'じゆう', 'にゆう', 'りゆう', 'きゆう', 3, '理由 = 理 (Lý → り) + 由 (Do → ゆう) → りゆう: lý do. 理 (Lý) → り theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "理", "hanViet": "Lý", "reading": "リ", "romaji": "ri", "focus": "理由"}', @onyomi_quiz_1_3, 7, 'onyomi:INIT_L:理:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 練習 (Luyện Tập) đọc là gì?', 'れんしゅう', 'ねんしゅう', 'れんしゅう', 'ぜんしゅう', 'けんしゅう', 2, '練習 = 練 (Luyện → れん) + 習 (Tập → しゅう) → れんしゅう: luyện tập. 練 (Luyện) → れん theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "練", "hanViet": "Luyện", "reading": "レン", "romaji": "ren", "focus": "練習"}', @onyomi_quiz_1_3, 8, 'onyomi:INIT_L:練:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 南極 (Nam Cực) đọc là gì?', 'なんきょく', 'なんきょく', 'りゃんきょく', 'じゃんきょく', 'らんきょく', 1, '南極 = 南 (Nam → なん) + 極 (Cực → きょく) → なんきょく: Nam Cực. 南 (Nam) → なん theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "南", "hanViet": "Nam", "reading": "ナン", "romaji": "nan", "focus": "南極"}', @onyomi_quiz_1_3, 9, 'onyomi:INIT_N:南:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 案内 (Án Nội) đọc là gì?', 'あんない', 'あんらい', 'あんりゃい', 'あんない', 'あんじゃい', 3, '案内 = 案 (Án → あん) + 内 (Nội → ない) → あんない: hướng dẫn. 内 (Nội) → ない theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "内", "hanViet": "Nội", "reading": "ナイ", "romaji": "nai", "focus": "案内"}', @onyomi_quiz_1_3, 10, 'onyomi:INIT_N:内:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 来年 (Lai Niên) đọc là gì?', 'らいねん', 'らいれん', 'らいぜん', 'らいけん', 'らいねん', 4, '来年 = 来 (Lai → らい) + 年 (Niên → ねん) → らいねん: năm sau. 年 (Niên) → ねん theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "年", "hanViet": "Niên", "reading": "ネン", "romaji": "nen", "focus": "来年"}', @onyomi_quiz_1_3, 11, 'onyomi:INIT_N:年:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 能力 (Năng Lực) đọc là gì?', 'のうりょく', 'りょうりょく', 'ろうりょく', 'じょうりょく', 'のうりょく', 4, '能力 = 能 (Năng → のう) + 力 (Lực → りょく) → のうりょく: năng lực. 能 (Năng) → のう theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "能", "hanViet": "Năng", "reading": "ノウ", "romaji": "nou", "focus": "能力"}', @onyomi_quiz_1_3, 12, 'onyomi:INIT_N:能:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 農業 (Nông Nghiệp) đọc là gì?', 'のうぎょう', 'のうぎょう', 'りょうぎょう', 'ろうぎょう', 'じょうぎょう', 1, '農業 = 農 (Nông → のう) + 業 (Nghiệp → ぎょう) → のうぎょう: nông nghiệp. 農 (Nông) → のう theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "農", "hanViet": "Nông", "reading": "ノウ", "romaji": "nou", "focus": "農業"}', @onyomi_quiz_1_3, 13, 'onyomi:INIT_N:農:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 困難 (Khốn Nan) đọc là gì?', 'こんなん', 'こんじゃん', 'こんらん', 'こんなん', 'こんりゃん', 3, '困難 = 困 (Khốn → こん) + 難 (Nan → なん) → こんなん: khó khăn. 難 (Nan) → なん theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "難", "hanViet": "Nan", "reading": "ナン", "romaji": "nan", "focus": "困難"}', @onyomi_quiz_1_3, 14, 'onyomi:INIT_N:難:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 記念 (Ký Niệm) đọc là gì?', 'きねん', 'きけん', 'きぜん', 'きねん', 'きれん', 3, '記念 = 記 (Ký → き) + 念 (Niệm → ねん) → きねん: kỷ niệm. 念 (Niệm) → ねん theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "念", "hanViet": "Niệm", "reading": "ネン", "romaji": "nen", "focus": "記念"}', @onyomi_quiz_1_3, 15, 'onyomi:INIT_N:念:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 二月 (Nhị Nguyệt) đọc là gì?', 'にがつ', 'りがつ', 'きがつ', 'にがつ', 'ぎがつ', 3, '二月 = 二 (Nhị → に) + 月 (Nguyệt → がつ) → にがつ: tháng hai. 二 (Nhị) → に theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "二", "hanViet": "Nhị", "reading": "ニ", "romaji": "ni", "focus": "二月"}', @onyomi_quiz_1_3, 16, 'onyomi:INIT_NH:二:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 日本人 (Nhật Bản Nhân) đọc là gì?', 'にほんじん', 'にほんりん', 'にほんぎん', 'にほんきん', 'にほんじん', 4, '日本人 = 日 (Nhật → に) + 本 (Bản → ほん) + 人 (Nhân → じん) → にほんじん: người Nhật. 人 (Nhân) → じん theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "人", "hanViet": "Nhân", "reading": "ジン", "romaji": "jin", "focus": "日本人"}', @onyomi_quiz_1_3, 17, 'onyomi:INIT_NH:人:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 毎日 (Mỗi Nhật) đọc là gì?', 'まいにち', 'まいぎち', 'まいにち', 'まいりち', 'まいきち', 2, '毎日 = 毎 (Mỗi → まい) + 日 (Nhật → にち) → まいにち: mỗi ngày. 日 (Nhật) → にち theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "日", "hanViet": "Nhật", "reading": "ニチ", "romaji": "nichi", "focus": "毎日"}', @onyomi_quiz_1_3, 18, 'onyomi:INIT_NH:日:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 入学 (Nhập Học) đọc là gì?', 'にゅうがく', 'ぎゅうがく', 'りゅうがく', 'きゅうがく', 'にゅうがく', 4, '入学 = 入 (Nhập → にゅう) + 学 (Học → がく) → にゅうがく: nhập học. 入 (Nhập) → にゅう theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "入", "hanViet": "Nhập", "reading": "ニュウ", "romaji": "nyuu", "focus": "入学"}', @onyomi_quiz_1_3, 19, 'onyomi:INIT_NH:入:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 確認 (Xác Nhận) đọc là gì?', 'かくにん', 'かくきん', 'かくりん', 'かくぎん', 'かくにん', 4, '確認 = 確 (Xác → かく) + 認 (Nhận → にん) → かくにん: xác nhận. 認 (Nhận) → にん theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "認", "hanViet": "Nhận", "reading": "ニン", "romaji": "nin", "focus": "確認"}', @onyomi_quiz_1_3, 20, 'onyomi:INIT_NH:認:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 熱心 (Nhiệt Tâm) đọc là gì?', 'ねっしん', 'れっしん', 'ねっしん', 'けっしん', 'げっしん', 2, '熱心 = 熱 (Nhiệt → ねっ) + 心 (Tâm → しん) → ねっしん: nhiệt tình. 熱 (Nhiệt) → ねつ theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J). Trong từ này 熱 đọc thành ねっ (vốn là ねつ).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "熱", "hanViet": "Nhiệt", "reading": "ネツ", "romaji": "netsu", "focus": "熱心"}', @onyomi_quiz_1_3, 21, 'onyomi:INIT_NH:熱:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 責任 (Trách Nhiệm) đọc là gì?', 'せきにん', 'せきりん', 'せきぎん', 'せききん', 'せきにん', 4, '責任 = 責 (Trách → せき) + 任 (Nhiệm → にん) → せきにん: trách nhiệm. 任 (Nhiệm) → にん theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "任", "hanViet": "Nhiệm", "reading": "ニン", "romaji": "nin", "focus": "責任"}', @onyomi_quiz_1_3, 22, 'onyomi:INIT_NH:任:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 牛肉 (Ngưu Nhục) đọc là gì?', 'ぎゅうにく', 'ぎゅうりく', 'ぎゅうにく', 'ぎゅうきく', 'ぎゅうぎく', 2, '牛肉 = 牛 (Ngưu → ぎゅう) + 肉 (Nhục → にく) → ぎゅうにく: thịt bò. 肉 (Nhục) → にく theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "肉", "hanViet": "Nhục", "reading": "ニク", "romaji": "niku", "focus": "牛肉"}', @onyomi_quiz_1_3, 23, 'onyomi:INIT_NH:肉:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 自然 (Tự Nhiên) đọc là gì?', 'しぜん', 'しぜん', 'しじん', 'しれん', 'しせん', 1, '自然 = 自 (Tự → し) + 然 (Nhiên → ぜん) → しぜん: tự nhiên. 然 (Nhiên) là ngoại lệ của quy tắc Âm đầu NH → N/J: 然 thường đọc ゼン (自然); ネン chỉ gặp trong vài từ như 天然.', '{"type": "EXCEPTION", "rule": "INIT_NH", "kanji": "然", "hanViet": "Nhiên", "reading": "ゼン", "romaji": "zen", "note": "然 thường đọc ゼン (自然); ネン chỉ gặp trong vài từ như 天然.", "word": "自然", "wordReading": "しぜん", "meaning": "tự nhiên", "focus": "自然"}', @onyomi_quiz_1_3, 24, 'onyomi:INIT_NH:然:EXCEPTION'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 学生 (Học Sinh) đọc là gì?', 'がくせい', 'がくぜい', 'がくせい', 'がくねい', 'がくれい', 2, '学生 = 学 (Học → がく) + 生 (Sinh → せい) → がくせい: học sinh. 生 (Sinh) → せい theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "生", "hanViet": "Sinh", "reading": "セイ", "romaji": "sei", "focus": "学生"}', @onyomi_quiz_1_4, 1, 'onyomi:INIT_S:生:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 特色 (Đặc Sắc) đọc là gì?', 'とくしょく', 'とくにょく', 'とくじょく', 'とくりょく', 'とくしょく', 4, '特色 = 特 (Đặc → とく) + 色 (Sắc → しょく) → とくしょく: đặc sắc. 色 (Sắc) → しょく theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "色", "hanViet": "Sắc", "reading": "ショク", "romaji": "shoku", "focus": "特色"}', @onyomi_quiz_1_4, 2, 'onyomi:INIT_S:色:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 富士山 (Phú Sĩ Sơn) đọc là gì?', 'ふじさん', 'ふじりゃん', 'ふじなん', 'ふじさん', 'ふじらん', 3, '富士山 = 富 (Phú → ふ) + 士 (Sĩ → じ) + 山 (Sơn → さん) → ふじさん: núi Phú Sĩ. 山 (Sơn) → さん theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "山", "hanViet": "Sơn", "reading": "サン", "romaji": "san", "focus": "富士山"}', @onyomi_quiz_1_4, 3, 'onyomi:INIT_S:山:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 生産 (Sinh Sản) đọc là gì?', 'せいさん', 'せいりゃん', 'せいらん', 'せいなん', 'せいさん', 4, '生産 = 生 (Sinh → せい) + 産 (Sản → さん) → せいさん: sản xuất. 産 (Sản) → さん theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "産", "hanViet": "Sản", "reading": "サン", "romaji": "san", "focus": "生産"}', @onyomi_quiz_1_4, 4, 'onyomi:INIT_S:産:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 使用 (Sử Dụng) đọc là gì?', 'しよう', 'によう', 'じよう', 'しよう', 'りよう', 3, '使用 = 使 (Sử → し) + 用 (Dụng → よう) → しよう: sử dụng. 使 (Sử) → し theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "使", "hanViet": "Sử", "reading": "シ", "romaji": "shi", "focus": "使用"}', @onyomi_quiz_1_4, 5, 'onyomi:INIT_S:使:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 最初 (Tối Sơ) đọc là gì?', 'さいしょ', 'さいじょ', 'さいりょ', 'さいしょ', 'さいにょ', 3, '最初 = 最 (Tối → さい) + 初 (Sơ → しょ) → さいしょ: ban đầu. 初 (Sơ) → しょ theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "初", "hanViet": "Sơ", "reading": "ショ", "romaji": "sho", "focus": "最初"}', @onyomi_quiz_1_4, 6, 'onyomi:INIT_S:初:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 住所 (Trú Sở) đọc là gì?', 'じゅうしょ', 'じゅうじょ', 'じゅうにょ', 'じゅうりょ', 'じゅうしょ', 4, '住所 = 住 (Trú → じゅう) + 所 (Sở → しょ) → じゅうしょ: địa chỉ. 所 (Sở) → しょ theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "所", "hanViet": "Sở", "reading": "ショ", "romaji": "sho", "focus": "住所"}', @onyomi_quiz_1_4, 7, 'onyomi:INIT_S:所:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 教師 (Giáo Sư) đọc là gì?', 'きょうし', 'きょうじ', 'きょうし', 'きょうに', 'きょうり', 2, '教師 = 教 (Giáo → きょう) + 師 (Sư → し) → きょうし: giáo viên. 師 (Sư) → し theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "師", "hanViet": "Sư", "reading": "シ", "romaji": "shi", "focus": "教師"}', @onyomi_quiz_1_4, 8, 'onyomi:INIT_S:師:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 先生 (Tiên Sinh) đọc là gì?', 'せんせい', 'ねんせい', 'ぜんせい', 'せんせい', 'れんせい', 3, '先生 = 先 (Tiên → せん) + 生 (Sinh → せい) → せんせい: giáo viên. 先 (Tiên) → せん theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "先", "hanViet": "Tiên", "reading": "セン", "romaji": "sen", "focus": "先生"}', @onyomi_quiz_1_4, 9, 'onyomi:INIT_T:先:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 西洋 (Tây Dương) đọc là gì?', 'せいよう', 'れいよう', 'ねいよう', 'ぜいよう', 'せいよう', 4, '西洋 = 西 (Tây → せい) + 洋 (Dương → よう) → せいよう: phương Tây. 西 (Tây) → せい theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "西", "hanViet": "Tây", "reading": "セイ", "romaji": "sei", "focus": "西洋"}', @onyomi_quiz_1_4, 10, 'onyomi:INIT_T:西:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 三月 (Tam Nguyệt) đọc là gì?', 'さんがつ', 'さんがつ', 'りゃんがつ', 'らんがつ', 'なんがつ', 1, '三月 = 三 (Tam → さん) + 月 (Nguyệt → がつ) → さんがつ: tháng ba. 三 (Tam) → さん theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "三", "hanViet": "Tam", "reading": "サン", "romaji": "san", "focus": "三月"}', @onyomi_quiz_1_4, 11, 'onyomi:INIT_T:三:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 最後 (Tối Hậu) đọc là gì?', 'さいご', 'ないご', 'りゃいご', 'らいご', 'さいご', 4, '最後 = 最 (Tối → さい) + 後 (Hậu → ご) → さいご: cuối cùng. 最 (Tối) → さい theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "最", "hanViet": "Tối", "reading": "サイ", "romaji": "sai", "focus": "最後"}', @onyomi_quiz_1_4, 12, 'onyomi:INIT_T:最:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 作文 (Tác Văn) đọc là gì?', 'さくぶん', 'らくぶん', 'りゃくぶん', 'なくぶん', 'さくぶん', 4, '作文 = 作 (Tác → さく) + 文 (Văn → ぶん) → さくぶん: bài văn. 作 (Tác) → さく theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "作", "hanViet": "Tác", "reading": "サク", "romaji": "saku", "focus": "作文"}', @onyomi_quiz_1_4, 13, 'onyomi:INIT_T:作:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 総理 (Tổng Lý) đọc là gì?', 'そうり', 'のうり', 'ろうり', 'りょうり', 'そうり', 4, '総理 = 総 (Tổng → そう) + 理 (Lý → り) → そうり: thủ tướng. 総 (Tổng) → そう theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "総", "hanViet": "Tổng", "reading": "ソウ", "romaji": "sou", "focus": "総理"}', @onyomi_quiz_1_4, 14, 'onyomi:INIT_T:総:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 国際 (Quốc Tế) đọc là gì?', 'こくさい', 'こくりゃい', 'こくらい', 'こくさい', 'こくない', 3, '国際 = 国 (Quốc → こく) + 際 (Tế → さい) → こくさい: quốc tế. 際 (Tế) → さい theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "際", "hanViet": "Tế", "reading": "サイ", "romaji": "sai", "focus": "国際"}', @onyomi_quiz_1_4, 15, 'onyomi:INIT_T:際:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 安心 (An Tâm) đọc là gì?', 'あんしん', 'あんにん', 'あんしん', 'あんりん', 'あんじん', 2, '安心 = 安 (An → あん) + 心 (Tâm → しん) → あんしん: yên tâm. 心 (Tâm) → しん theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "心", "hanViet": "Tâm", "reading": "シン", "romaji": "shin", "focus": "安心"}', @onyomi_quiz_1_4, 16, 'onyomi:INIT_T:心:WORD'),
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 自転車 (Tự Chuyển Xa) đọc là gì?', 'じてんしゃ', 'じてんしゃ', 'じてんにゃ', 'じてんじゃ', 'じてんりゃ', 1, '自転車 = 自 (Tự → じ) + 転 (Chuyển → てん) + 車 (Xa → しゃ) → じてんしゃ: xe đạp. 車 (Xa) → しゃ theo quy tắc Âm đầu X → SH: Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', '{"type": "WORD", "rule": "INIT_X", "kanji": "車", "hanViet": "Xa", "reading": "シャ", "romaji": "sha", "focus": "自転車"}', @onyomi_quiz_1_4, 17, 'onyomi:INIT_X:車:WORD'),
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 注射 (Chú Xạ) đọc là gì?', 'ちゅうしゃ', 'ちゅうしゃ', 'ちゅうじゃ', 'ちゅうりゃ', 'ちゅうにゃ', 1, '注射 = 注 (Chú → ちゅう) + 射 (Xạ → しゃ) → ちゅうしゃ: tiêm. 射 (Xạ) → しゃ theo quy tắc Âm đầu X → SH: Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', '{"type": "WORD", "rule": "INIT_X", "kanji": "射", "hanViet": "Xạ", "reading": "シャ", "romaji": "sha", "focus": "注射"}', @onyomi_quiz_1_4, 18, 'onyomi:INIT_X:射:WORD'),
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 会社 (Hội Xã) đọc là gì?', 'かいしゃ', 'かいにゃ', 'かいきゃ', 'かいしゃ', 'かいりゃ', 3, '会社 = 会 (Hội → かい) + 社 (Xã → しゃ) → かいしゃ: công ty. 社 (Xã) → しゃ theo quy tắc Âm đầu X → SH: Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', '{"type": "WORD", "rule": "INIT_X", "kanji": "社", "hanViet": "Xã", "reading": "シャ", "romaji": "sha", "focus": "会社"}', @onyomi_quiz_1_4, 19, 'onyomi:INIT_X:社:WORD'),
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 青春 (Thanh Xuân) đọc là gì?', 'せいしゅん', 'せいしゅん', 'せいじゅん', 'せいにゅん', 'せいりゅん', 1, '青春 = 青 (Thanh → せい) + 春 (Xuân → しゅん) → せいしゅん: tuổi xuân. 春 (Xuân) → しゅん theo quy tắc Âm đầu X → SH: Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', '{"type": "WORD", "rule": "INIT_X", "kanji": "春", "hanViet": "Xuân", "reading": "シュン", "romaji": "shun", "focus": "青春"}', @onyomi_quiz_1_4, 20, 'onyomi:INIT_X:春:WORD'),
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 輸出 (Du Xuất) đọc là gì?', 'ゆしゅつ', 'ゆりゅつ', 'ゆにゅつ', 'ゆしゅつ', 'ゆじゅつ', 3, '輸出 = 輸 (Du → ゆ) + 出 (Xuất → しゅつ) → ゆしゅつ: xuất khẩu. 出 (Xuất) → しゅつ theo quy tắc Âm đầu X → SH: Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', '{"type": "WORD", "rule": "INIT_X", "kanji": "出", "hanViet": "Xuất", "reading": "シュツ", "romaji": "shutsu", "focus": "輸出"}', @onyomi_quiz_1_4, 21, 'onyomi:INIT_X:出:WORD'),
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 処理 (Xử Lý) đọc là gì?', 'しょり', 'しょり', 'じょり', 'りょり', 'にょり', 1, '処理 = 処 (Xử → しょ) + 理 (Lý → り) → しょり: xử lý. 処 (Xử) → しょ theo quy tắc Âm đầu X → SH: Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', '{"type": "WORD", "rule": "INIT_X", "kanji": "処", "hanViet": "Xử", "reading": "ショ", "romaji": "sho", "focus": "処理"}', @onyomi_quiz_1_4, 22, 'onyomi:INIT_X:処:WORD'),
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 赤道 (Xích Đạo) đọc là gì?', 'せきどう', 'しきどう', 'ちきどう', 'ひきどう', 'せきどう', 4, '赤道 = 赤 (Xích → せき) + 道 (Đạo → どう) → せきどう: xích đạo. 赤 (Xích) là ngoại lệ của quy tắc Âm đầu X → SH: 赤 thường đọc セキ: X thành S chứ không thành SH.', '{"type": "EXCEPTION", "rule": "INIT_X", "kanji": "赤", "hanViet": "Xích", "reading": "セキ", "romaji": "seki", "note": "赤 thường đọc セキ: X thành S chứ không thành SH.", "word": "赤道", "wordReading": "せきどう", "meaning": "xích đạo", "focus": "赤道"}', @onyomi_quiz_1_4, 23, 'onyomi:INIT_X:赤:EXCEPTION'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 失礼 (Thất Lễ) đọc là gì?', 'しつれい', 'につれい', 'りつれい', 'じつれい', 'しつれい', 4, '失礼 = 失 (Thất → しつ) + 礼 (Lễ → れい) → しつれい: thất lễ. 失 (Thất) → しつ theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "失", "hanViet": "Thất", "reading": "シツ", "romaji": "shitsu", "focus": "失礼"}', @onyomi_quiz_1_5, 1, 'onyomi:INIT_TH:失:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 世紀 (Thế Kỷ) đọc là gì?', 'せいき', 'れいき', 'ねいき', 'ぜいき', 'せいき', 4, '世紀 = 世 (Thế → せい) + 紀 (Kỷ → き) → せいき: thế kỷ. 世 (Thế) → せい theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "世", "hanViet": "Thế", "reading": "セイ", "romaji": "sei", "focus": "世紀"}', @onyomi_quiz_1_5, 2, 'onyomi:INIT_TH:世:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 選手 (Tuyển Thủ) đọc là gì?', 'せんしゅ', 'せんりゅ', 'せんしゅ', 'せんじゅ', 'せんにゅ', 2, '選手 = 選 (Tuyển → せん) + 手 (Thủ → しゅ) → せんしゅ: tuyển thủ. 手 (Thủ) → しゅ theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "手", "hanViet": "Thủ", "reading": "シュ", "romaji": "shu", "focus": "選手"}', @onyomi_quiz_1_5, 3, 'onyomi:INIT_TH:手:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 辞書 (Từ Thư) đọc là gì?', 'じしょ', 'じりょ', 'じじょ', 'じにょ', 'じしょ', 4, '辞書 = 辞 (Từ → じ) + 書 (Thư → しょ) → じしょ: từ điển. 書 (Thư) → しょ theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "書", "hanViet": "Thư", "reading": "ショ", "romaji": "sho", "focus": "辞書"}', @onyomi_quiz_1_5, 4, 'onyomi:INIT_TH:書:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 天気 (Thiên Khí) đọc là gì?', 'てんき', 'ねんき', 'れんき', 'ぜんき', 'てんき', 4, '天気 = 天 (Thiên → てん) + 気 (Khí → き) → てんき: thời tiết. 天 (Thiên) → てん theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "天", "hanViet": "Thiên", "reading": "テン", "romaji": "ten", "focus": "天気"}', @onyomi_quiz_1_5, 5, 'onyomi:INIT_TH:天:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 体育 (Thể Dục) đọc là gì?', 'たいいく', 'らいいく', 'りゃいいく', 'たいいく', 'ないいく', 3, '体育 = 体 (Thể → たい) + 育 (Dục → いく) → たいいく: thể dục. 体 (Thể) → たい theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "体", "hanViet": "Thể", "reading": "タイ", "romaji": "tai", "focus": "体育"}', @onyomi_quiz_1_5, 6, 'onyomi:INIT_TH:体:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 交通 (Giao Thông) đọc là gì?', 'こうつう', 'こうぬう', 'こうりゅう', 'こうつう', 'こうるう', 3, '交通 = 交 (Giao → こう) + 通 (Thông → つう) → こうつう: giao thông. 通 (Thông) → つう theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "通", "hanViet": "Thông", "reading": "ツウ", "romaji": "tsuu", "focus": "交通"}', @onyomi_quiz_1_5, 7, 'onyomi:INIT_TH:通:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 地下鉄 (Địa Hạ Thiết) đọc là gì?', 'ちかてつ', 'ちかねつ', 'ちかれつ', 'ちかてつ', 'ちかぜつ', 3, '地下鉄 = 地 (Địa → ち) + 下 (Hạ → か) + 鉄 (Thiết → てつ) → ちかてつ: tàu điện ngầm. 鉄 (Thiết) → てつ theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "鉄", "hanViet": "Thiết", "reading": "テツ", "romaji": "tetsu", "focus": "地下鉄"}', @onyomi_quiz_1_5, 8, 'onyomi:INIT_TH:鉄:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 社長 (Xã Trưởng) đọc là gì?', 'しゃちょう', 'しゃしょう', 'しゃちょう', 'しゃりょう', 'しゃにょう', 2, '社長 = 社 (Xã → しゃ) + 長 (Trưởng → ちょう) → しゃちょう: giám đốc. 長 (Trưởng) → ちょう theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "長", "hanViet": "Trưởng", "reading": "チョウ", "romaji": "chou", "focus": "社長"}', @onyomi_quiz_1_5, 9, 'onyomi:INIT_TR:長:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 中国 (Trung Quốc) đọc là gì?', 'ちゅうごく', 'ちゅうごく', 'りゅうごく', 'にゅうごく', 'しゅうごく', 1, '中国 = 中 (Trung → ちゅう) + 国 (Quốc → ごく) → ちゅうごく: Trung Quốc. 中 (Trung) → ちゅう theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "中", "hanViet": "Trung", "reading": "チュウ", "romaji": "chuu", "focus": "中国"}', @onyomi_quiz_1_5, 10, 'onyomi:INIT_TR:中:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 紅茶 (Hồng Trà) đọc là gì?', 'こうちゃ', 'こうにゃ', 'こうしゃ', 'こうりゃ', 'こうちゃ', 4, '紅茶 = 紅 (Hồng → こう) + 茶 (Trà → ちゃ) → こうちゃ: trà đen. 茶 (Trà) → ちゃ theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "茶", "hanViet": "Trà", "reading": "チャ", "romaji": "cha", "focus": "紅茶"}', @onyomi_quiz_1_5, 11, 'onyomi:INIT_TR:茶:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 到着 (Đáo Trước) đọc là gì?', 'とうちゃく', 'とうちゃく', 'とうしゃく', 'とうりゃく', 'とうにゃく', 1, '到着 = 到 (Đáo → とう) + 着 (Trước → ちゃく) → とうちゃく: đến nơi. 着 (Trước) → ちゃく theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "着", "hanViet": "Trước", "reading": "チャク", "romaji": "chaku", "focus": "到着"}', @onyomi_quiz_1_5, 12, 'onyomi:INIT_TR:着:WORD');
INSERT INTO assessment_question (conversion_rule_id, knowledge_tag_id, skill, jlpt_level, difficulty,
    question_type, question_text, answer_text, option_1, option_2, option_3, option_4, correct_option,
    explanation, context_json, quiz_id, order_index, title) VALUES
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 貯金 (Trữ Kim) đọc là gì?', 'ちょきん', 'りょきん', 'ちょきん', 'にょきん', 'しょきん', 2, '貯金 = 貯 (Trữ → ちょ) + 金 (Kim → きん) → ちょきん: tiết kiệm tiền. 貯 (Trữ) → ちょ theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "貯", "hanViet": "Trữ", "reading": "チョ", "romaji": "cho", "focus": "貯金"}', @onyomi_quiz_1_5, 13, 'onyomi:INIT_TR:貯:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 朝食 (Triều Thực) đọc là gì?', 'ちょうしょく', 'しょうしょく', 'ちょうしょく', 'りょうしょく', 'にょうしょく', 2, '朝食 = 朝 (Triều → ちょう) + 食 (Thực → しょく) → ちょうしょく: bữa sáng. 朝 (Triều) → ちょう theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "朝", "hanViet": "Triều", "reading": "チョウ", "romaji": "chou", "focus": "朝食"}', @onyomi_quiz_1_5, 14, 'onyomi:INIT_TR:朝:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 知識 (Tri Thức) đọc là gì?', 'ちしき', 'ししき', 'ちしき', 'にしき', 'りしき', 2, '知識 = 知 (Tri → ち) + 識 (Thức → しき) → ちしき: tri thức. 知 (Tri) → ち theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "知", "hanViet": "Tri", "reading": "チ", "romaji": "chi", "focus": "知識"}', @onyomi_quiz_1_5, 15, 'onyomi:INIT_TR:知:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 追加 (Truy Gia) đọc là gì?', 'ついか', 'ついか', 'しゅいか', 'すいか', 'るいか', 1, '追加 = 追 (Truy → つい) + 加 (Gia → か) → ついか: bổ sung. 追 (Truy) → つい theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "追", "hanViet": "Truy", "reading": "ツイ", "romaji": "tsui", "focus": "追加"}', @onyomi_quiz_1_5, 16, 'onyomi:INIT_TR:追:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 開始 (Khai Thủy) đọc là gì?', 'かいし', 'かいし', 'がいし', 'さいし', 'ぎゃいし', 1, '開始 = 開 (Khai → かい) + 始 (Thủy → し) → かいし: bắt đầu. 開 (Khai) → かい theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "開", "hanViet": "Khai", "reading": "カイ", "romaji": "kai", "focus": "開始"}', @onyomi_quiz_1_6, 1, 'onyomi:INIT_KH:開:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 科学 (Khoa Học) đọc là gì?', 'かがく', 'かがく', 'ががく', 'ぎゃがく', 'さがく', 1, '科学 = 科 (Khoa → か) + 学 (Học → がく) → かがく: khoa học. 科 (Khoa) → か theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "科", "hanViet": "Khoa", "reading": "カ", "romaji": "ka", "focus": "科学"}', @onyomi_quiz_1_6, 2, 'onyomi:INIT_KH:科:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 空港 (Không Cảng) đọc là gì?', 'くうこう', 'すうこう', 'くうこう', 'ぐうこう', 'ぎゅうこう', 2, '空港 = 空 (Không → くう) + 港 (Cảng → こう) → くうこう: sân bay. 空 (Không) → くう theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "空", "hanViet": "Không", "reading": "クウ", "romaji": "kuu", "focus": "空港"}', @onyomi_quiz_1_6, 3, 'onyomi:INIT_KH:空:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 天気 (Thiên Khí) đọc là gì?', 'てんき', 'てんき', 'てんち', 'てんし', 'てんぎ', 1, '天気 = 天 (Thiên → てん) + 気 (Khí → き) → てんき: thời tiết. 気 (Khí) → き theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "気", "hanViet": "Khí", "reading": "キ", "romaji": "ki", "focus": "天気"}', @onyomi_quiz_1_6, 4, 'onyomi:INIT_KH:気:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 人口 (Nhân Khẩu) đọc là gì?', 'じんこう', 'じんこう', 'じんごう', 'じんそう', 'じんぎょう', 1, '人口 = 人 (Nhân → じん) + 口 (Khẩu → こう) → じんこう: dân số. 口 (Khẩu) → こう theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "口", "hanViet": "Khẩu", "reading": "コウ", "romaji": "kou", "focus": "人口"}', @onyomi_quiz_1_6, 5, 'onyomi:INIT_KH:口:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 参考 (Tham Khảo) đọc là gì?', 'さんこう', 'さんぎょう', 'さんそう', 'さんごう', 'さんこう', 4, '参考 = 参 (Tham → さん) + 考 (Khảo → こう) → さんこう: tham khảo. 考 (Khảo) → こう theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "考", "hanViet": "Khảo", "reading": "コウ", "romaji": "kou", "focus": "参考"}', @onyomi_quiz_1_6, 6, 'onyomi:INIT_KH:考:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 観客 (Quan Khách) đọc là gì?', 'かんきゃく', 'かんぎゃく', 'かんきゃく', 'かんちゃく', 'かんしゃく', 2, '観客 = 観 (Quan → かん) + 客 (Khách → きゃく) → かんきゃく: khán giả. 客 (Khách) → きゃく theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "客", "hanViet": "Khách", "reading": "キャク", "romaji": "kyaku", "focus": "観客"}', @onyomi_quiz_1_6, 7, 'onyomi:INIT_KH:客:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 苦労 (Khổ Lao) đọc là gì?', 'くろう', 'ぎゅろう', 'くろう', 'すろう', 'ぐろう', 2, '苦労 = 苦 (Khổ → く) + 労 (Lao → ろう) → くろう: vất vả. 苦 (Khổ) → く theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "苦", "hanViet": "Khổ", "reading": "ク", "romaji": "ku", "focus": "苦労"}', @onyomi_quiz_1_6, 8, 'onyomi:INIT_KH:苦:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 家族 (Gia Tộc) đọc là gì?', 'かぞく', 'たぞく', 'かぞく', 'しゃぞく', 'さぞく', 2, '家族 = 家 (Gia → か) + 族 (Tộc → ぞく) → かぞく: gia đình. 家 (Gia) → か theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "家", "hanViet": "Gia", "reading": "カ", "romaji": "ka", "focus": "家族"}', @onyomi_quiz_1_6, 9, 'onyomi:INIT_GI:家:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 時間 (Thời Gian) đọc là gì?', 'じかん', 'じかん', 'じさん', 'じたん', 'じしゃん', 1, '時間 = 時 (Thời → じ) + 間 (Gian → かん) → じかん: thời gian. 間 (Gian) → かん theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "間", "hanViet": "Gian", "reading": "カン", "romaji": "kan", "focus": "時間"}', @onyomi_quiz_1_6, 10, 'onyomi:INIT_GI:間:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 交通 (Giao Thông) đọc là gì?', 'こうつう', 'そうつう', 'とうつう', 'しょうつう', 'こうつう', 4, '交通 = 交 (Giao → こう) + 通 (Thông → つう) → こうつう: giao thông. 交 (Giao) → こう theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "交", "hanViet": "Giao", "reading": "コウ", "romaji": "kou", "focus": "交通"}', @onyomi_quiz_1_6, 11, 'onyomi:INIT_GI:交:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 教室 (Giáo Thất) đọc là gì?', 'きょうしつ', 'しょうしつ', 'とうしつ', 'ちょうしつ', 'きょうしつ', 4, '教室 = 教 (Giáo → きょう) + 室 (Thất → しつ) → きょうしつ: phòng học. 教 (Giáo) → きょう theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "教", "hanViet": "Giáo", "reading": "キョウ", "romaji": "kyou", "focus": "教室"}', @onyomi_quiz_1_6, 12, 'onyomi:INIT_GI:教:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 理解 (Lý Giải) đọc là gì?', 'りかい', 'りしゃい', 'りたい', 'りさい', 'りかい', 4, '理解 = 理 (Lý → り) + 解 (Giải → かい) → りかい: hiểu. 解 (Giải) → かい theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "解", "hanViet": "Giải", "reading": "カイ", "romaji": "kai", "focus": "理解"}', @onyomi_quiz_1_6, 13, 'onyomi:INIT_GI:解:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 参加 (Tham Gia) đọc là gì?', 'さんか', 'さんさ', 'さんしゃ', 'さんた', 'さんか', 4, '参加 = 参 (Tham → さん) + 加 (Gia → か) → さんか: tham gia. 加 (Gia) → か theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "加", "hanViet": "Gia", "reading": "カ", "romaji": "ka", "focus": "参加"}', @onyomi_quiz_1_6, 14, 'onyomi:INIT_GI:加:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 減少 (Giảm Thiếu) đọc là gì?', 'げんしょう', 'れんしょう', 'てんしょう', 'げんしょう', 'せんしょう', 3, '減少 = 減 (Giảm → げん) + 少 (Thiếu → しょう) → げんしょう: giảm bớt. 減 (Giảm) → げん theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "減", "hanViet": "Giảm", "reading": "ゲン", "romaji": "gen", "focus": "減少"}', @onyomi_quiz_1_6, 15, 'onyomi:INIT_GI:減:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 世界 (Thế Giới) đọc là gì?', 'せかい', 'せかい', 'せしゃい', 'せさい', 'せたい', 1, '世界 = 世 (Thế → せ) + 界 (Giới → かい) → せかい: thế giới. 界 (Giới) → かい theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "界", "hanViet": "Giới", "reading": "カイ", "romaji": "kai", "focus": "世界"}', @onyomi_quiz_1_6, 16, 'onyomi:INIT_GI:界:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 東北 (Đông Bắc) đọc là gì?', 'とうほく', 'とうほく', 'とうごく', 'とうきょく', 'とうこく', 1, '東北 = 東 (Đông → とう) + 北 (Bắc → ほく) → とうほく: Đông Bắc. 北 (Bắc) → ほく theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "北", "hanViet": "Bắc", "reading": "ホク", "romaji": "hoku", "focus": "東北"}', @onyomi_quiz_1_7, 1, 'onyomi:INIT_B:北:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 日本 (Nhật Bản) đọc là gì?', 'にほん', 'にごん', 'にきょん', 'にほん', 'にこん', 3, '日本 = 日 (Nhật → に) + 本 (Bản → ほん) → にほん: Nhật Bản. 本 (Bản) → ほん theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "本", "hanViet": "Bản", "reading": "ホン", "romaji": "hon", "focus": "日本"}', @onyomi_quiz_1_7, 2, 'onyomi:INIT_B:本:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 半分 (Bán Phân) đọc là gì?', 'はんぶん', 'きゃんぶん', 'がんぶん', 'はんぶん', 'かんぶん', 3, '半分 = 半 (Bán → はん) + 分 (Phân → ぶん) → はんぶん: một nửa. 半 (Bán) → はん theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "半", "hanViet": "Bán", "reading": "ハン", "romaji": "han", "focus": "半分"}', @onyomi_quiz_1_7, 3, 'onyomi:INIT_B:半:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 百円 (Bách Viên) đọc là gì?', 'ひゃくえん', 'しゃくえん', 'ぎゃくえん', 'ひゃくえん', 'きゃくえん', 3, '百円 = 百 (Bách → ひゃく) + 円 (Viên → えん) → ひゃくえん: một trăm yên. 百 (Bách) → ひゃく theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "百", "hanViet": "Bách", "reading": "ヒャク", "romaji": "hyaku", "focus": "百円"}', @onyomi_quiz_1_7, 4, 'onyomi:INIT_B:百:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 平和 (Bình Hòa) đọc là gì?', 'へいわ', 'へいわ', 'せいわ', 'けいわ', 'げいわ', 1, '平和 = 平 (Bình → へい) + 和 (Hòa → わ) → へいわ: hoà bình. 平 (Bình) → へい theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "平", "hanViet": "Bình", "reading": "ヘイ", "romaji": "hei", "focus": "平和"}', @onyomi_quiz_1_7, 5, 'onyomi:INIT_B:平:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 病院 (Bệnh Viện) đọc là gì?', 'びょういん', 'びょういん', 'きょういん', 'ぎょういん', 'しょういん', 1, '病院 = 病 (Bệnh → びょう) + 院 (Viện → いん) → びょういん: bệnh viện. 病 (Bệnh) → びょう theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "病", "hanViet": "Bệnh", "reading": "ビョウ", "romaji": "byou", "focus": "病院"}', @onyomi_quiz_1_7, 6, 'onyomi:INIT_B:病:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 特別 (Đặc Biệt) đọc là gì?', 'とくべつ', 'とくせつ', 'とくげつ', 'とくけつ', 'とくべつ', 4, '特別 = 特 (Đặc → とく) + 別 (Biệt → べつ) → とくべつ: đặc biệt. 別 (Biệt) → べつ theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "別", "hanViet": "Biệt", "reading": "ベツ", "romaji": "betsu", "focus": "特別"}', @onyomi_quiz_1_7, 7, 'onyomi:INIT_B:別:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 大変 (Đại Biến) đọc là gì?', 'たいへん', 'たいせん', 'たいけん', 'たいげん', 'たいへん', 4, '大変 = 大 (Đại → たい) + 変 (Biến → へん) → たいへん: vất vả. 変 (Biến) → へん theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "変", "hanViet": "Biến", "reading": "ヘン", "romaji": "hen", "focus": "大変"}', @onyomi_quiz_1_7, 8, 'onyomi:INIT_B:変:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 方法 (Phương Pháp) đọc là gì?', 'ほうほう', 'ほうほう', 'ぼうほう', 'びょうほう', 'こうほう', 1, '方法 = 方 (Phương → ほう) + 法 (Pháp → ほう) → ほうほう: phương pháp. 方 (Phương) → ほう theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "方", "hanViet": "Phương", "reading": "ホウ", "romaji": "hou", "focus": "方法"}', @onyomi_quiz_1_7, 9, 'onyomi:INIT_PH:方:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 反対 (Phản Đối) đọc là gì?', 'はんたい', 'はんたい', 'ばんたい', 'かんたい', 'びゃんたい', 1, '反対 = 反 (Phản → はん) + 対 (Đối → たい) → はんたい: phản đối. 反 (Phản) → はん theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "反", "hanViet": "Phản", "reading": "ハン", "romaji": "han", "focus": "反対"}', @onyomi_quiz_1_7, 10, 'onyomi:INIT_PH:反:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 祖父 (Tổ Phụ) đọc là gì?', 'そふ', 'そく', 'そふ', 'そぶ', 'そびゅ', 2, '祖父 = 祖 (Tổ → そ) + 父 (Phụ → ふ) → そふ: ông. 父 (Phụ) → ふ theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "父", "hanViet": "Phụ", "reading": "フ", "romaji": "fu", "focus": "祖父"}', @onyomi_quiz_1_7, 11, 'onyomi:INIT_PH:父:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 放送 (Phóng Tống) đọc là gì?', 'ほうそう', 'ぼうそう', 'こうそう', 'びょうそう', 'ほうそう', 4, '放送 = 放 (Phóng → ほう) + 送 (Tống → そう) → ほうそう: phát sóng. 放 (Phóng) → ほう theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "放", "hanViet": "Phóng", "reading": "ホウ", "romaji": "hou", "focus": "放送"}', @onyomi_quiz_1_7, 12, 'onyomi:INIT_PH:放:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 飛行機 (Phi Hành Cơ) đọc là gì?', 'ひこうき', 'びこうき', 'ぎこうき', 'ひこうき', 'きこうき', 3, '飛行機 = 飛 (Phi → ひ) + 行 (Hành → こう) + 機 (Cơ → き) → ひこうき: máy bay. 飛 (Phi) → ひ theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "飛", "hanViet": "Phi", "reading": "ヒ", "romaji": "hi", "focus": "飛行機"}', @onyomi_quiz_1_7, 13, 'onyomi:INIT_PH:飛:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 費用 (Phí Dụng) đọc là gì?', 'ひよう', 'ひよう', 'きよう', 'ぎよう', 'びよう', 1, '費用 = 費 (Phí → ひ) + 用 (Dụng → よう) → ひよう: chi phí. 費 (Phí) → ひ theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "費", "hanViet": "Phí", "reading": "ヒ", "romaji": "hi", "focus": "費用"}', @onyomi_quiz_1_7, 14, 'onyomi:INIT_PH:費:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 専門 (Chuyên Môn) đọc là gì?', 'せんもん', 'せんひょん', 'せんもん', 'せんおん', 'せんほん', 2, '専門 = 専 (Chuyên → せん) + 門 (Môn → もん) → せんもん: chuyên môn. 門 (Môn) → もん theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "門", "hanViet": "Môn", "reading": "モン", "romaji": "mon", "focus": "専門"}', @onyomi_quiz_1_8, 1, 'onyomi:INIT_M:門:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 馬車 (Mã Xa) đọc là gì?', 'ばしゃ', 'ひゃしゃ', 'はしゃ', 'ばしゃ', 'あしゃ', 3, '馬車 = 馬 (Mã → ば) + 車 (Xa → しゃ) → ばしゃ: xe ngựa. 馬 (Mã) → ば theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "馬", "hanViet": "Mã", "reading": "バ", "romaji": "ba", "focus": "馬車"}', @onyomi_quiz_1_8, 2, 'onyomi:INIT_M:馬:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 目的 (Mục Đích) đọc là gì?', 'もくてき', 'もくてき', 'おくてき', 'ほくてき', 'ひょくてき', 1, '目的 = 目 (Mục → もく) + 的 (Đích → てき) → もくてき: mục đích. 目 (Mục) → もく theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "目", "hanViet": "Mục", "reading": "モク", "romaji": "moku", "focus": "目的"}', @onyomi_quiz_1_8, 3, 'onyomi:INIT_M:目:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 父母 (Phụ Mẫu) đọc là gì?', 'ふぼ', 'ふお', 'ふぼ', 'ふほ', 'ふひょ', 2, '父母 = 父 (Phụ → ふ) + 母 (Mẫu → ぼ) → ふぼ: cha mẹ. 母 (Mẫu) → ぼ theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "母", "hanViet": "Mẫu", "reading": "ボ", "romaji": "bo", "focus": "父母"}', @onyomi_quiz_1_8, 4, 'onyomi:INIT_M:母:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 説明 (Thuyết Minh) đọc là gì?', 'せつめい', 'せつめい', 'せつえい', 'せつへい', 'せつけい', 1, '説明 = 説 (Thuyết → せつ) + 明 (Minh → めい) → せつめい: giải thích. 明 (Minh) → めい theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "明", "hanViet": "Minh", "reading": "メイ", "romaji": "mei", "focus": "説明"}', @onyomi_quiz_1_8, 5, 'onyomi:INIT_M:明:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 欧米 (Âu Mễ) đọc là gì?', 'おうべい', 'おうべい', 'おうへい', 'おうけい', 'おうえい', 1, '欧米 = 欧 (Âu → おう) + 米 (Mễ → べい) → おうべい: Âu Mỹ. 米 (Mễ) → べい theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "米", "hanViet": "Mễ", "reading": "ベイ", "romaji": "bei", "focus": "欧米"}', @onyomi_quiz_1_8, 6, 'onyomi:INIT_M:米:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 美術 (Mỹ Thuật) đọc là gì?', 'びじゅつ', 'きじゅつ', 'びじゅつ', 'ひじゅつ', 'いじゅつ', 2, '美術 = 美 (Mỹ → び) + 術 (Thuật → じゅつ) → びじゅつ: mỹ thuật. 美 (Mỹ) → び theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "美", "hanViet": "Mỹ", "reading": "ビ", "romaji": "bi", "focus": "美術"}', @onyomi_quiz_1_8, 7, 'onyomi:INIT_M:美:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 毎日 (Mỗi Nhật) đọc là gì?', 'まいにち', 'まいにち', 'ひゃいにち', 'はいにち', 'あいにち', 1, '毎日 = 毎 (Mỗi → まい) + 日 (Nhật → にち) → まいにち: mỗi ngày. 毎 (Mỗi) → まい theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "毎", "hanViet": "Mỗi", "reading": "マイ", "romaji": "mai", "focus": "毎日"}', @onyomi_quiz_1_8, 8, 'onyomi:INIT_M:毎:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 文化 (Văn Hóa) đọc là gì?', 'ぶんか', 'ふんか', 'ぶんか', 'ひゅんか', 'くんか', 2, '文化 = 文 (Văn → ぶん) + 化 (Hóa → か) → ぶんか: văn hoá. 文 (Văn) → ぶん theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "文", "hanViet": "Văn", "reading": "ブン", "romaji": "bun", "focus": "文化"}', @onyomi_quiz_1_8, 9, 'onyomi:INIT_V:文:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 質問 (Chất Vấn) đọc là gì?', 'しつもん', 'しつほん', 'しつこん', 'しつもん', 'しつひょん', 3, '質問 = 質 (Chất → しつ) + 問 (Vấn → もん) → しつもん: câu hỏi. 問 (Vấn) → もん theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "問", "hanViet": "Vấn", "reading": "モン", "romaji": "mon", "focus": "質問"}', @onyomi_quiz_1_8, 10, 'onyomi:INIT_V:問:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 動物 (Động Vật) đọc là gì?', 'どうぶつ', 'どうひゅつ', 'どうふつ', 'どうぶつ', 'どうくつ', 3, '動物 = 動 (Động → どう) + 物 (Vật → ぶつ) → どうぶつ: động vật. 物 (Vật) → ぶつ theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "物", "hanViet": "Vật", "reading": "ブツ", "romaji": "butsu", "focus": "動物"}', @onyomi_quiz_1_8, 11, 'onyomi:INIT_V:物:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 一万 (Nhất Vạn) đọc là gì?', 'いちまん', 'いちはん', 'いちひゃん', 'いちかん', 'いちまん', 4, '一万 = 一 (Nhất → いち) + 万 (Vạn → まん) → いちまん: mười nghìn. 万 (Vạn) → まん theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "万", "hanViet": "Vạn", "reading": "マン", "romaji": "man", "focus": "一万"}', @onyomi_quiz_1_8, 12, 'onyomi:INIT_V:万:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 未来 (Vị Lai) đọc là gì?', 'みらい', 'ぎらい', 'きらい', 'みらい', 'ひらい', 3, '未来 = 未 (Vị → み) + 来 (Lai → らい) → みらい: tương lai. 未 (Vị) → み theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "未", "hanViet": "Vị", "reading": "ミ", "romaji": "mi", "focus": "未来"}', @onyomi_quiz_1_8, 13, 'onyomi:INIT_V:未:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 無理 (Vô Lý) đọc là gì?', 'むり', 'ひゅり', 'くり', 'むり', 'ふり', 3, '無理 = 無 (Vô → む) + 理 (Lý → り) → むり: quá sức. 無 (Vô) → む theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "無", "hanViet": "Vô", "reading": "ム", "romaji": "mu", "focus": "無理"}', @onyomi_quiz_1_8, 14, 'onyomi:INIT_V:無:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 百円 (Bách Viên) đọc là gì?', 'ひゃくえん', 'ひゃくえん', 'ひゃくへん', 'ひゃくけん', 'ひゃくげん', 1, '百円 = 百 (Bách → ひゃく) + 円 (Viên → えん) → ひゃくえん: một trăm yên. 円 (Viên) → えん theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "円", "hanViet": "Viên", "reading": "エン", "romaji": "en", "focus": "百円"}', @onyomi_quiz_1_8, 15, 'onyomi:INIT_V:円:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 王様 (Vương Dạng) đọc là gì?', 'おうさま', 'ひょうさま', 'ほうさま', 'こうさま', 'おうさま', 4, '王様 = 王 (Vương → おう) + 様 (Dạng → さま) → おうさま: nhà vua. 王 (Vương) → おう theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "王", "hanViet": "Vương", "reading": "オウ", "romaji": "ou", "focus": "王様"}', @onyomi_quiz_1_8, 16, 'onyomi:INIT_V:王:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 大学 (Đại Học) đọc là gì?', 'だいがく', 'だいがく', 'やいがく', 'みゃいがく', 'まいがく', 1, '大学 = 大 (Đại → だい) + 学 (Học → がく) → だいがく: đại học. 大 (Đại) → だい theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "大", "hanViet": "Đại", "reading": "ダイ", "romaji": "dai", "focus": "大学"}', @onyomi_quiz_1_9, 1, 'onyomi:INIT_DD:大:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 東京 (Đông Kinh) đọc là gì?', 'とうきょう', 'みょうきょう', 'もうきょう', 'とうきょう', 'ようきょう', 3, '東京 = 東 (Đông → とう) + 京 (Kinh → きょう) → とうきょう: Tokyo. 東 (Đông) → とう theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "東", "hanViet": "Đông", "reading": "トウ", "romaji": "tou", "focus": "東京"}', @onyomi_quiz_1_9, 2, 'onyomi:INIT_DD:東:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 電話 (Điện Thoại) đọc là gì?', 'でんわ', 'えんわ', 'べんわ', 'でんわ', 'めんわ', 3, '電話 = 電 (Điện → でん) + 話 (Thoại → わ) → でんわ: điện thoại. 電 (Điện) → でん theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "電", "hanViet": "Điện", "reading": "デン", "romaji": "den", "focus": "電話"}', @onyomi_quiz_1_9, 3, 'onyomi:INIT_DD:電:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 今度 (Kim Độ) đọc là gì?', 'こんど', 'こんみょ', 'こんも', 'こんよ', 'こんど', 4, '今度 = 今 (Kim → こん) + 度 (Độ → ど) → こんど: lần tới. 度 (Độ) → ど theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "度", "hanViet": "Độ", "reading": "ド", "romaji": "do", "focus": "今度"}', @onyomi_quiz_1_9, 4, 'onyomi:INIT_DD:度:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 道具 (Đạo Cụ) đọc là gì?', 'どうぐ', 'みょうぐ', 'もうぐ', 'ようぐ', 'どうぐ', 4, '道具 = 道 (Đạo → どう) + 具 (Cụ → ぐ) → どうぐ: dụng cụ. 道 (Đạo) → どう theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "道", "hanViet": "Đạo", "reading": "ドウ", "romaji": "dou", "focus": "道具"}', @onyomi_quiz_1_9, 5, 'onyomi:INIT_DD:道:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 運動 (Vận Động) đọc là gì?', 'うんどう', 'うんみょう', 'うんもう', 'うんどう', 'うんよう', 3, '運動 = 運 (Vận → うん) + 動 (Động → どう) → うんどう: vận động. 動 (Động) → どう theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "動", "hanViet": "Động", "reading": "ドウ", "romaji": "dou", "focus": "運動"}', @onyomi_quiz_1_9, 6, 'onyomi:INIT_DD:動:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 書店 (Thư Điếm) đọc là gì?', 'しょてん', 'しょめん', 'しょえん', 'しょべん', 'しょてん', 4, '書店 = 書 (Thư → しょ) + 店 (Điếm → てん) → しょてん: hiệu sách. 店 (Điếm) → てん theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "店", "hanViet": "Điếm", "reading": "テン", "romaji": "ten", "focus": "書店"}', @onyomi_quiz_1_9, 7, 'onyomi:INIT_DD:店:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 特別 (Đặc Biệt) đọc là gì?', 'とくべつ', 'みょくべつ', 'もくべつ', 'よくべつ', 'とくべつ', 4, '特別 = 特 (Đặc → とく) + 別 (Biệt → べつ) → とくべつ: đặc biệt. 特 (Đặc) → とく theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "特", "hanViet": "Đặc", "reading": "トク", "romaji": "toku", "focus": "特別"}', @onyomi_quiz_1_9, 8, 'onyomi:INIT_DD:特:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 薬品 (Dược Phẩm) đọc là gì?', 'やくひん', 'まくひん', 'だくひん', 'やくひん', 'たくひん', 3, '薬品 = 薬 (Dược → やく) + 品 (Phẩm → ひん) → やくひん: dược phẩm. 薬 (Dược) → やく theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "薬", "hanViet": "Dược", "reading": "ヤク", "romaji": "yaku", "focus": "薬品"}', @onyomi_quiz_1_9, 9, 'onyomi:INIT_D:薬:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 用意 (Dụng Ý) đọc là gì?', 'ようい', 'ようい', 'とうい', 'もうい', 'どうい', 1, '用意 = 用 (Dụng → よう) + 意 (Ý → い) → ようい: chuẩn bị. 用 (Dụng) → よう theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "用", "hanViet": "Dụng", "reading": "ヨウ", "romaji": "you", "focus": "用意"}', @onyomi_quiz_1_9, 10, 'onyomi:INIT_D:用:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 遊園地 (Du Viên Địa) đọc là gì?', 'ゆうえんち', 'つうえんち', 'みゅうえんち', 'ゆうえんち', 'むうえんち', 3, '遊園地 = 遊 (Du → ゆう) + 園 (Viên → えん) + 地 (Địa → ち) → ゆうえんち: công viên giải trí. 遊 (Du) → ゆう theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "遊", "hanViet": "Du", "reading": "ユウ", "romaji": "yuu", "focus": "遊園地"}', @onyomi_quiz_1_9, 11, 'onyomi:INIT_D:遊:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 自由 (Tự Do) đọc là gì?', 'じゆう', 'じゆう', 'じむう', 'じみゅう', 'じつう', 1, '自由 = 自 (Tự → じ) + 由 (Do → ゆう) → じゆう: tự do. 由 (Do) → ゆう theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "由", "hanViet": "Do", "reading": "ユウ", "romaji": "yuu", "focus": "自由"}', @onyomi_quiz_1_9, 12, 'onyomi:INIT_D:由:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 勇気 (Dũng Khí) đọc là gì?', 'ゆうき', 'むうき', 'つうき', 'ゆうき', 'みゅうき', 3, '勇気 = 勇 (Dũng → ゆう) + 気 (Khí → き) → ゆうき: dũng khí. 勇 (Dũng) → ゆう theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "勇", "hanViet": "Dũng", "reading": "ユウ", "romaji": "yuu", "focus": "勇気"}', @onyomi_quiz_1_9, 13, 'onyomi:INIT_D:勇:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 様子 (Dạng Tử) đọc là gì?', 'ようす', 'どうす', 'ようす', 'とうす', 'もうす', 2, '様子 = 様 (Dạng → よう) + 子 (Tử → す) → ようす: tình hình. 様 (Dạng) → よう theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "様", "hanViet": "Dạng", "reading": "ヨウ", "romaji": "you", "focus": "様子"}', @onyomi_quiz_1_9, 14, 'onyomi:INIT_D:様:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 予定 (Dự Định) đọc là gì?', 'よてい', 'よてい', 'とてい', 'どてい', 'もてい', 1, '予定 = 予 (Dự → よ) + 定 (Định → てい) → よてい: dự định. 予 (Dự) → よ theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "予", "hanViet": "Dự", "reading": "ヨ", "romaji": "yo", "focus": "予定"}', @onyomi_quiz_1_9, 15, 'onyomi:INIT_D:予:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 石油 (Thạch Du) đọc là gì?', 'せきゆ', 'せきゆ', 'せきつ', 'せきむ', 'せきみゅ', 1, '石油 = 石 (Thạch → せき) + 油 (Du → ゆ) → せきゆ: dầu mỏ. 油 (Du) → ゆ theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "油", "hanViet": "Du", "reading": "ユ", "romaji": "yu", "focus": "石油"}', @onyomi_quiz_1_9, 16, 'onyomi:INIT_D:油:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 以上 (Dĩ Thượng) đọc là gì?', 'いじょう', 'ゆじょう', 'いじょう', 'やじょう', 'よじょう', 2, '以上 = 以 (Dĩ → い) + 上 (Thượng → じょう) → いじょう: trở lên. 以 (Dĩ) là ngoại lệ của quy tắc Âm đầu D → Y: 以 đọc イ: phụ âm D mất hẳn thay vì thành Y.', '{"type": "EXCEPTION", "rule": "INIT_D", "kanji": "以", "hanViet": "Dĩ", "reading": "イ", "romaji": "i", "note": "以 đọc イ: phụ âm D mất hẳn thay vì thành Y.", "word": "以上", "wordReading": "いじょう", "meaning": "trở lên", "focus": "以上"}', @onyomi_quiz_1_9, 17, 'onyomi:INIT_D:以:EXCEPTION'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 正確 (Chính Xác) đọc là gì?', 'せいかく', 'でいかく', 'せいかく', 'ていかく', 'えいかく', 2, '正確 = 正 (Chính → せい) + 確 (Xác → かく) → せいかく: chính xác. 正 (Chính) → せい theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "正", "hanViet": "Chính", "reading": "セイ", "romaji": "sei", "focus": "正確"}', @onyomi_quiz_1_10, 1, 'onyomi:INIT_CH:正:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 主人 (Chủ Nhân) đọc là gì?', 'しゅじん', 'うじん', 'ちゅじん', 'ゆじん', 'しゅじん', 4, '主人 = 主 (Chủ → しゅ) + 人 (Nhân → じん) → しゅじん: chồng; chủ nhà. 主 (Chủ) → しゅ theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "主", "hanViet": "Chủ", "reading": "シュ", "romaji": "shu", "focus": "主人"}', @onyomi_quiz_1_10, 2, 'onyomi:INIT_CH:主:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 終了 (Chung Liễu) đọc là gì?', 'しゅうりょう', 'ううりょう', 'ちゅうりょう', 'しゅうりょう', 'ゆうりょう', 3, '終了 = 終 (Chung → しゅう) + 了 (Liễu → りょう) → しゅうりょう: kết thúc. 終 (Chung) → しゅう theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "終", "hanViet": "Chung", "reading": "シュウ", "romaji": "shuu", "focus": "終了"}', @onyomi_quiz_1_10, 3, 'onyomi:INIT_CH:終:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 中止 (Trung Chỉ) đọc là gì?', 'ちゅうし', 'ちゅうい', 'ちゅうち', 'ちゅうし', 'ちゅうみ', 3, '中止 = 中 (Trung → ちゅう) + 止 (Chỉ → し) → ちゅうし: huỷ bỏ. 止 (Chỉ) → し theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "止", "hanViet": "Chỉ", "reading": "シ", "romaji": "shi", "focus": "中止"}', @onyomi_quiz_1_10, 4, 'onyomi:INIT_CH:止:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 毎週 (Mỗi Chu) đọc là gì?', 'まいしゅう', 'まいうう', 'まいしゅう', 'まいゆう', 'まいちゅう', 2, '毎週 = 毎 (Mỗi → まい) + 週 (Chu → しゅう) → まいしゅう: hằng tuần. 週 (Chu) → しゅう theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "週", "hanViet": "Chu", "reading": "シュウ", "romaji": "shuu", "focus": "毎週"}', @onyomi_quiz_1_10, 5, 'onyomi:INIT_CH:週:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 写真 (Tả Chân) đọc là gì?', 'しゃしん', 'しゃしん', 'しゃいん', 'しゃちん', 'しゃみん', 1, '写真 = 写 (Tả → しゃ) + 真 (Chân → しん) → しゃしん: ảnh chụp. 真 (Chân) → しん theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "真", "hanViet": "Chân", "reading": "シン", "romaji": "shin", "focus": "写真"}', @onyomi_quiz_1_10, 6, 'onyomi:INIT_CH:真:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 戦争 (Chiến Tranh) đọc là gì?', 'せんそう', 'でんそう', 'せんそう', 'てんそう', 'えんそう', 2, '戦争 = 戦 (Chiến → せん) + 争 (Tranh → そう) → せんそう: chiến tranh. 戦 (Chiến) → せん theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "戦", "hanViet": "Chiến", "reading": "セン", "romaji": "sen", "focus": "戦争"}', @onyomi_quiz_1_10, 7, 'onyomi:INIT_CH:戦:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 質問 (Chất Vấn) đọc là gì?', 'しつもん', 'しつもん', 'いつもん', 'みつもん', 'ちつもん', 1, '質問 = 質 (Chất → しつ) + 問 (Vấn → もん) → しつもん: câu hỏi. 質 (Chất) → しつ theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "質", "hanViet": "Chất", "reading": "シツ", "romaji": "shitsu", "focus": "質問"}', @onyomi_quiz_1_10, 8, 'onyomi:INIT_CH:質:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 準備 (Chuẩn Bị) đọc là gì?', 'じゅんび', 'ちゅんび', 'しゅんび', 'じゅんび', 'すんび', 3, '準備 = 準 (Chuẩn → じゅん) + 備 (Bị → び) → じゅんび: chuẩn bị. 準 (Chuẩn) là ngoại lệ của quy tắc Âm đầu CH → S/SH: 準 đọc ジュン: CH thành J thay vì S/SH.', '{"type": "EXCEPTION", "rule": "INIT_CH", "kanji": "準", "hanViet": "Chuẩn", "reading": "ジュン", "romaji": "jun", "note": "準 đọc ジュン: CH thành J thay vì S/SH.", "word": "準備", "wordReading": "じゅんび", "meaning": "chuẩn bị", "focus": "準備"}', @onyomi_quiz_1_10, 9, 'onyomi:INIT_CH:準:EXCEPTION'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 安全 (An Toàn) đọc là gì?', 'あんぜん', 'たんぜん', 'さんぜん', 'しゃんぜん', 'あんぜん', 4, '安全 = 安 (An → あん) + 全 (Toàn → ぜん) → あんぜん: an toàn. 安 (An) → あん theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "安", "hanViet": "An", "reading": "アン", "romaji": "an", "focus": "安全"}', @onyomi_quiz_1_10, 10, 'onyomi:INIT_VOWEL:安:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 意見 (Ý Kiến) đọc là gì?', 'いけん', 'みけん', 'しけん', 'いけん', 'ちけん', 3, '意見 = 意 (Ý → い) + 見 (Kiến → けん) → いけん: ý kiến. 意 (Ý) → い theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "意", "hanViet": "Ý", "reading": "イ", "romaji": "i", "focus": "意見"}', @onyomi_quiz_1_10, 11, 'onyomi:INIT_VOWEL:意:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 一億 (Nhất Ức) đọc là gì?', 'いちおく', 'いちおく', 'いちとく', 'いちしょく', 'いちそく', 1, '一億 = 一 (Nhất → いち) + 億 (Ức → おく) → いちおく: một trăm triệu. 億 (Ức) → おく theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "億", "hanViet": "Ức", "reading": "オク", "romaji": "oku", "focus": "一億"}', @onyomi_quiz_1_10, 12, 'onyomi:INIT_VOWEL:億:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 予約 (Dự Ước) đọc là gì?', 'よやく', 'よたく', 'よさく', 'よしゃく', 'よやく', 4, '予約 = 予 (Dự → よ) + 約 (Ước → やく) → よやく: đặt trước. 約 (Ước) → やく theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "約", "hanViet": "Ước", "reading": "ヤク", "romaji": "yaku", "focus": "予約"}', @onyomi_quiz_1_10, 13, 'onyomi:INIT_VOWEL:約:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 英語 (Anh Ngữ) đọc là gì?', 'えいご', 'せいご', 'でいご', 'えいご', 'ていご', 3, '英語 = 英 (Anh → えい) + 語 (Ngữ → ご) → えいご: tiếng Anh. 英 (Anh) → えい theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "英", "hanViet": "Anh", "reading": "エイ", "romaji": "ei", "focus": "英語"}', @onyomi_quiz_1_10, 14, 'onyomi:INIT_VOWEL:英:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 印象 (Ấn Tượng) đọc là gì?', 'いんしょう', 'みんしょう', 'ちんしょう', 'しんしょう', 'いんしょう', 4, '印象 = 印 (Ấn → いん) + 象 (Tượng → しょう) → いんしょう: ấn tượng. 印 (Ấn) → いん theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "印", "hanViet": "Ấn", "reading": "イン", "romaji": "in", "focus": "印象"}', @onyomi_quiz_1_10, 15, 'onyomi:INIT_VOWEL:印:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 最悪 (Tối Ác) đọc là gì?', 'さいあく', 'さいあく', 'さいさく', 'さいたく', 'さいしゃく', 1, '最悪 = 最 (Tối → さい) + 悪 (Ác → あく) → さいあく: tồi tệ nhất. 悪 (Ác) → あく theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "悪", "hanViet": "Ác", "reading": "アク", "romaji": "aku", "focus": "最悪"}', @onyomi_quiz_1_10, 16, 'onyomi:INIT_VOWEL:悪:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 医者 (Y Giả) đọc là gì?', 'いしゃ', 'ししゃ', 'いしゃ', 'ちしゃ', 'みしゃ', 2, '医者 = 医 (Y → い) + 者 (Giả → しゃ) → いしゃ: bác sĩ. 医 (Y) → い theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "医", "hanViet": "Y", "reading": "イ", "romaji": "i", "focus": "医者"}', @onyomi_quiz_1_10, 17, 'onyomi:INIT_VOWEL:医:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 三月 (Tam Nguyệt) đọc là gì?', 'さんがつ', 'さつがつ', 'さくがつ', 'さんがつ', 'さいがつ', 3, '三月 = 三 (Tam → さん) + 月 (Nguyệt → がつ) → さんがつ: tháng ba. 三 (Tam) → さん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "三", "hanViet": "Tam", "reading": "サン", "romaji": "san", "focus": "三月"}', @onyomi_quiz_2_1, 1, 'onyomi:FIN_M:三:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 安心 (An Tâm) đọc là gì?', 'あんしん', 'あんしい', 'あんしん', 'あんしく', 'あんしつ', 2, '安心 = 安 (An → あん) + 心 (Tâm → しん) → あんしん: yên tâm. 心 (Tâm) → しん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "心", "hanViet": "Tâm", "reading": "シン", "romaji": "shin", "focus": "安心"}', @onyomi_quiz_2_1, 2, 'onyomi:FIN_M:心:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 料金 (Liệu Kim) đọc là gì?', 'りょうきん', 'りょうきい', 'りょうきん', 'りょうきく', 'りょうきつ', 2, '料金 = 料 (Liệu → りょう) + 金 (Kim → きん) → りょうきん: tiền phí. 金 (Kim) → きん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "金", "hanViet": "Kim", "reading": "キン", "romaji": "kin", "focus": "料金"}', @onyomi_quiz_2_1, 3, 'onyomi:FIN_M:金:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 感動 (Cảm Động) đọc là gì?', 'かんどう', 'かいどう', 'かつどう', 'かくどう', 'かんどう', 4, '感動 = 感 (Cảm → かん) + 動 (Động → どう) → かんどう: cảm động. 感 (Cảm) → かん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "感", "hanViet": "Cảm", "reading": "カン", "romaji": "kan", "focus": "感動"}', @onyomi_quiz_2_1, 4, 'onyomi:FIN_M:感:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 暗記 (Ám Ký) đọc là gì?', 'あんき', 'あつき', 'あくき', 'あいき', 'あんき', 4, '暗記 = 暗 (Ám → あん) + 記 (Ký → き) → あんき: học thuộc lòng. 暗 (Ám) → あん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "暗", "hanViet": "Ám", "reading": "アン", "romaji": "an", "focus": "暗記"}', @onyomi_quiz_2_1, 5, 'onyomi:FIN_M:暗:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 森林 (Sâm Lâm) đọc là gì?', 'しんりん', 'しんりん', 'しんりい', 'しんりく', 'しんりつ', 1, '森林 = 森 (Sâm → しん) + 林 (Lâm → りん) → しんりん: rừng. 林 (Lâm) → りん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "林", "hanViet": "Lâm", "reading": "リン", "romaji": "rin", "focus": "森林"}', @onyomi_quiz_2_1, 6, 'onyomi:FIN_M:林:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 相談 (Tương Đàm) đọc là gì?', 'そうだん', 'そうだつ', 'そうだん', 'そうだい', 'そうだく', 2, '相談 = 相 (Tương → そう) + 談 (Đàm → だん) → そうだん: trao đổi. 談 (Đàm) → だん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "談", "hanViet": "Đàm", "reading": "ダン", "romaji": "dan", "focus": "相談"}', @onyomi_quiz_2_1, 7, 'onyomi:FIN_M:談:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 点数 (Điểm Số) đọc là gì?', 'てんすう', 'てんすう', 'てつすう', 'てくすう', 'ていすう', 1, '点数 = 点 (Điểm → てん) + 数 (Số → すう) → てんすう: điểm số. 点 (Điểm) → てん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "点", "hanViet": "Điểm", "reading": "テン", "romaji": "ten", "focus": "点数"}', @onyomi_quiz_2_1, 8, 'onyomi:FIN_M:点:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 文化 (Văn Hóa) đọc là gì?', 'ぶんか', 'ぶんか', 'ぶつか', 'ぶいか', 'ぶくか', 1, '文化 = 文 (Văn → ぶん) + 化 (Hóa → か) → ぶんか: văn hoá. 文 (Văn) → ぶん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "文", "hanViet": "Văn", "reading": "ブン", "romaji": "bun", "focus": "文化"}', @onyomi_quiz_2_1, 9, 'onyomi:FIN_N:文:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 会員 (Hội Viên) đọc là gì?', 'かいいん', 'かいいく', 'かいいい', 'かいいん', 'かいいつ', 3, '会員 = 会 (Hội → かい) + 員 (Viên → いん) → かいいん: hội viên. 員 (Viên) → いん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "員", "hanViet": "Viên", "reading": "イン", "romaji": "in", "focus": "会員"}', @onyomi_quiz_2_1, 10, 'onyomi:FIN_N:員:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 富士山 (Phú Sĩ Sơn) đọc là gì?', 'ふじさん', 'ふじさん', 'ふじさい', 'ふじさつ', 'ふじさく', 1, '富士山 = 富 (Phú → ふ) + 士 (Sĩ → じ) + 山 (Sơn → さん) → ふじさん: núi Phú Sĩ. 山 (Sơn) → さん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "山", "hanViet": "Sơn", "reading": "サン", "romaji": "san", "focus": "富士山"}', @onyomi_quiz_2_1, 11, 'onyomi:FIN_N:山:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 日本 (Nhật Bản) đọc là gì?', 'にほん', 'にほい', 'にほん', 'にほつ', 'にほく', 2, '日本 = 日 (Nhật → に) + 本 (Bản → ほん) → にほん: Nhật Bản. 本 (Bản) → ほん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "本", "hanViet": "Bản", "reading": "ホン", "romaji": "hon", "focus": "日本"}', @onyomi_quiz_2_1, 12, 'onyomi:FIN_N:本:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 新聞 (Tân Văn) đọc là gì?', 'しんぶん', 'しくぶん', 'しつぶん', 'しいぶん', 'しんぶん', 4, '新聞 = 新 (Tân → しん) + 聞 (Văn → ぶん) → しんぶん: báo. 新 (Tân) → しん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "新", "hanViet": "Tân", "reading": "シン", "romaji": "shin", "focus": "新聞"}', @onyomi_quiz_2_1, 13, 'onyomi:FIN_N:新:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 安全 (An Toàn) đọc là gì?', 'あんぜん', 'あいぜん', 'あつぜん', 'あんぜん', 'あくぜん', 3, '安全 = 安 (An → あん) + 全 (Toàn → ぜん) → あんぜん: an toàn. 安 (An) → あん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "安", "hanViet": "An", "reading": "アン", "romaji": "an", "focus": "安全"}', @onyomi_quiz_2_1, 14, 'onyomi:FIN_N:安:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 完全 (Hoàn Toàn) đọc là gì?', 'かんぜん', 'かくぜん', 'かつぜん', 'かんぜん', 'かいぜん', 3, '完全 = 完 (Hoàn → かん) + 全 (Toàn → ぜん) → かんぜん: hoàn toàn. 完 (Hoàn) → かん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "完", "hanViet": "Hoàn", "reading": "カン", "romaji": "kan", "focus": "完全"}', @onyomi_quiz_2_1, 15, 'onyomi:FIN_N:完:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 質問 (Chất Vấn) đọc là gì?', 'しつもん', 'しつもつ', 'しつもく', 'しつもい', 'しつもん', 4, '質問 = 質 (Chất → しつ) + 問 (Vấn → もん) → しつもん: câu hỏi. 問 (Vấn) → もん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "問", "hanViet": "Vấn", "reading": "モン", "romaji": "mon", "focus": "質問"}', @onyomi_quiz_2_1, 16, 'onyomi:FIN_N:問:WORD');
INSERT INTO assessment_question (conversion_rule_id, knowledge_tag_id, skill, jlpt_level, difficulty,
    question_type, question_text, answer_text, option_1, option_2, option_3, option_4, correct_option,
    explanation, context_json, quiz_id, order_index, title) VALUES
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 最悪 (Tối Ác) đọc là gì?', 'さいあく', 'さいあき', 'さいあん', 'さいあつ', 'さいあく', 4, '最悪 = 最 (Tối → さい) + 悪 (Ác → あく) → さいあく: tồi tệ nhất. 悪 (Ác) → あく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "悪", "hanViet": "Ác", "reading": "アク", "romaji": "aku", "focus": "最悪"}', @onyomi_quiz_2_2, 1, 'onyomi:FIN_C:悪:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 薬品 (Dược Phẩm) đọc là gì?', 'やくひん', 'やんひん', 'やつひん', 'やくひん', 'やきひん', 3, '薬品 = 薬 (Dược → やく) + 品 (Phẩm → ひん) → やくひん: dược phẩm. 薬 (Dược) → やく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "薬", "hanViet": "Dược", "reading": "ヤク", "romaji": "yaku", "focus": "薬品"}', @onyomi_quiz_2_2, 2, 'onyomi:FIN_C:薬:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 外国 (Ngoại Quốc) đọc là gì?', 'がいこく', 'がいこつ', 'がいこん', 'がいこき', 'がいこく', 4, '外国 = 外 (Ngoại → がい) + 国 (Quốc → こく) → がいこく: nước ngoài. 国 (Quốc) → こく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "国", "hanViet": "Quốc", "reading": "コク", "romaji": "koku", "focus": "外国"}', @onyomi_quiz_2_2, 3, 'onyomi:FIN_C:国:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 大学 (Đại Học) đọc là gì?', 'だいがく', 'だいがん', 'だいがき', 'だいがつ', 'だいがく', 4, '大学 = 大 (Đại → だい) + 学 (Học → がく) → だいがく: đại học. 学 (Học) → がく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "学", "hanViet": "Học", "reading": "ガク", "romaji": "gaku", "focus": "大学"}', @onyomi_quiz_2_2, 4, 'onyomi:FIN_C:学:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 協力 (Hiệp Lực) đọc là gì?', 'きょうりょく', 'きょうりょつ', 'きょうりょん', 'きょうりょき', 'きょうりょく', 4, '協力 = 協 (Hiệp → きょう) + 力 (Lực → りょく) → きょうりょく: hợp tác. 力 (Lực) → りょく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "力", "hanViet": "Lực", "reading": "リョク", "romaji": "ryoku", "focus": "協力"}', @onyomi_quiz_2_2, 5, 'onyomi:FIN_C:力:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 目的 (Mục Đích) đọc là gì?', 'もくてき', 'もつてき', 'もんてき', 'もきてき', 'もくてき', 4, '目的 = 目 (Mục → もく) + 的 (Đích → てき) → もくてき: mục đích. 目 (Mục) → もく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "目", "hanViet": "Mục", "reading": "モク", "romaji": "moku", "focus": "目的"}', @onyomi_quiz_2_2, 6, 'onyomi:FIN_C:目:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 特別 (Đặc Biệt) đọc là gì?', 'とくべつ', 'ときべつ', 'とんべつ', 'とくべつ', 'とつべつ', 3, '特別 = 特 (Đặc → とく) + 別 (Biệt → べつ) → とくべつ: đặc biệt. 特 (Đặc) → とく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "特", "hanViet": "Đặc", "reading": "トク", "romaji": "toku", "focus": "特別"}', @onyomi_quiz_2_2, 7, 'onyomi:FIN_C:特:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 六月 (Lục Nguyệt) đọc là gì?', 'ろくがつ', 'ろくがつ', 'ろんがつ', 'ろきがつ', 'ろつがつ', 1, '六月 = 六 (Lục → ろく) + 月 (Nguyệt → がつ) → ろくがつ: tháng sáu. 六 (Lục) → ろく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "六", "hanViet": "Lục", "reading": "ロク", "romaji": "roku", "focus": "六月"}', @onyomi_quiz_2_2, 8, 'onyomi:FIN_C:六:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 正式 (Chính Thức) đọc là gì?', 'せいしき', 'せいしく', 'せいしき', 'せいしつ', 'せいしょく', 2, '正式 = 正 (Chính → せい) + 式 (Thức → しき) → せいしき: chính thức. 式 (Thức) là ngoại lệ của quy tắc Âm cuối C → ク: 式 đọc シキ: -C thành キ thay vì ク.', '{"type": "EXCEPTION", "rule": "FIN_C", "kanji": "式", "hanViet": "Thức", "reading": "シキ", "romaji": "shiki", "note": "式 đọc シキ: -C thành キ thay vì ク.", "word": "正式", "wordReading": "せいしき", "meaning": "chính thức", "focus": "正式"}', @onyomi_quiz_2_2, 9, 'onyomi:FIN_C:式:EXCEPTION'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 事実 (Sự Thực) đọc là gì?', 'じじつ', 'じじん', 'じじつ', 'じしょく', 'じじく', 2, '事実 = 事 (Sự → じ) + 実 (Thực → じつ) → じじつ: sự thật. 実 (Thực) là ngoại lệ của quy tắc Âm cuối C → ク: 実 đọc ジツ: -C thành ツ thay vì ク.', '{"type": "EXCEPTION", "rule": "FIN_C", "kanji": "実", "hanViet": "Thực", "reading": "ジツ", "romaji": "jitsu", "note": "実 đọc ジツ: -C thành ツ thay vì ク.", "word": "事実", "wordReading": "じじつ", "meaning": "sự thật", "focus": "事実"}', @onyomi_quiz_2_2, 10, 'onyomi:FIN_C:実:EXCEPTION'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 歴史 (Lịch Sử) đọc là gì?', 'れきし', 'れんし', 'れちし', 'れきし', 'れつし', 3, '歴史 = 歴 (Lịch → れき) + 史 (Sử → し) → れきし: lịch sử. 歴 (Lịch) → れき theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "歴", "hanViet": "Lịch", "reading": "レキ", "romaji": "reki", "focus": "歴史"}', @onyomi_quiz_2_3, 1, 'onyomi:FIN_CH:歴:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 適当 (Thích Đương) đọc là gì?', 'てきとう', 'てつとう', 'てちとう', 'てんとう', 'てきとう', 4, '適当 = 適 (Thích → てき) + 当 (Đương → とう) → てきとう: thích hợp. 適 (Thích) → てき theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "適", "hanViet": "Thích", "reading": "テキ", "romaji": "teki", "focus": "適当"}', @onyomi_quiz_2_3, 2, 'onyomi:FIN_CH:適:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 出席 (Xuất Tịch) đọc là gì?', 'しゅっせき', 'しゅっせち', 'しゅっせつ', 'しゅっせん', 'しゅっせき', 4, '出席 = 出 (Xuất → しゅっ) + 席 (Tịch → せき) → しゅっせき: có mặt. 席 (Tịch) → せき theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "席", "hanViet": "Tịch", "reading": "セキ", "romaji": "seki", "focus": "出席"}', @onyomi_quiz_2_3, 3, 'onyomi:FIN_CH:席:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 劇場 (Kịch Trường) đọc là gì?', 'げきじょう', 'げきじょう', 'げつじょう', 'げちじょう', 'げんじょう', 1, '劇場 = 劇 (Kịch → げき) + 場 (Trường → じょう) → げきじょう: nhà hát. 劇 (Kịch) → げき theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "劇", "hanViet": "Kịch", "reading": "ゲキ", "romaji": "geki", "focus": "劇場"}', @onyomi_quiz_2_3, 4, 'onyomi:FIN_CH:劇:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 駅員 (Dịch Viên) đọc là gì?', 'えきいん', 'えんいん', 'えついん', 'えちいん', 'えきいん', 4, '駅員 = 駅 (Dịch → えき) + 員 (Viên → いん) → えきいん: nhân viên nhà ga. 駅 (Dịch) → えき theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "駅", "hanViet": "Dịch", "reading": "エキ", "romaji": "eki", "focus": "駅員"}', @onyomi_quiz_2_3, 5, 'onyomi:FIN_CH:駅:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 白紙 (Bạch Chỉ) đọc là gì?', 'はくし', 'はつし', 'はんし', 'はちし', 'はくし', 4, '白紙 = 白 (Bạch → はく) + 紙 (Chỉ → し) → はくし: giấy trắng. 白 (Bạch) → はく theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "白", "hanViet": "Bạch", "reading": "ハク", "romaji": "haku", "focus": "白紙"}', @onyomi_quiz_2_3, 6, 'onyomi:FIN_CH:白:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 観客 (Quan Khách) đọc là gì?', 'かんきゃく', 'かんきゃん', 'かんきゃつ', 'かんきゃく', 'かんきゃち', 3, '観客 = 観 (Quan → かん) + 客 (Khách → きゃく) → かんきゃく: khán giả. 客 (Khách) → きゃく theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "客", "hanViet": "Khách", "reading": "キャク", "romaji": "kyaku", "focus": "観客"}', @onyomi_quiz_2_3, 7, 'onyomi:FIN_CH:客:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 合格 (Hợp Cách) đọc là gì?', 'ごうかく', 'ごうかく', 'ごうかん', 'ごうかつ', 'ごうかち', 1, '合格 = 合 (Hợp → ごう) + 格 (Cách → かく) → ごうかく: thi đỗ. 格 (Cách) → かく theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "格", "hanViet": "Cách", "reading": "カク", "romaji": "kaku", "focus": "合格"}', @onyomi_quiz_2_3, 8, 'onyomi:FIN_CH:格:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 地下鉄 (Địa Hạ Thiết) đọc là gì?', 'ちかてつ', 'ちかてう', 'ちかてく', 'ちかてき', 'ちかてつ', 4, '地下鉄 = 地 (Địa → ち) + 下 (Hạ → か) + 鉄 (Thiết → てつ) → ちかてつ: tàu điện ngầm. 鉄 (Thiết) → てつ theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "鉄", "hanViet": "Thiết", "reading": "テツ", "romaji": "tetsu", "focus": "地下鉄"}', @onyomi_quiz_2_4, 1, 'onyomi:FIN_T:鉄:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 生活 (Sinh Hoạt) đọc là gì?', 'せいかつ', 'せいかつ', 'せいかう', 'せいかく', 'せいかき', 1, '生活 = 生 (Sinh → せい) + 活 (Hoạt → かつ) → せいかつ: cuộc sống. 活 (Hoạt) → かつ theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "活", "hanViet": "Hoạt", "reading": "カツ", "romaji": "katsu", "focus": "生活"}', @onyomi_quiz_2_4, 2, 'onyomi:FIN_T:活:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 小説 (Tiểu Thuyết) đọc là gì?', 'しょうせつ', 'しょうせく', 'しょうせう', 'しょうせき', 'しょうせつ', 4, '小説 = 小 (Tiểu → しょう) + 説 (Thuyết → せつ) → しょうせつ: tiểu thuyết. 説 (Thuyết) → せつ theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "説", "hanViet": "Thuyết", "reading": "セツ", "romaji": "setsu", "focus": "小説"}', @onyomi_quiz_2_4, 3, 'onyomi:FIN_T:説:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 毎日 (Mỗi Nhật) đọc là gì?', 'まいにち', 'まいにち', 'まいにき', 'まいにう', 'まいにく', 1, '毎日 = 毎 (Mỗi → まい) + 日 (Nhật → にち) → まいにち: mỗi ngày. 日 (Nhật) → にち theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "日", "hanViet": "Nhật", "reading": "ニチ", "romaji": "nichi", "focus": "毎日"}', @onyomi_quiz_2_4, 4, 'onyomi:FIN_T:日:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 八月 (Bát Nguyệt) đọc là gì?', 'はちがつ', 'はきがつ', 'はちがつ', 'はくがつ', 'はうがつ', 2, '八月 = 八 (Bát → はち) + 月 (Nguyệt → がつ) → はちがつ: tháng tám. 八 (Bát) → はち theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "八", "hanViet": "Bát", "reading": "ハチ", "romaji": "hachi", "focus": "八月"}', @onyomi_quiz_2_4, 5, 'onyomi:FIN_T:八:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 十分 (Thập Phân) đọc là gì?', 'じゅうぶん', 'じゅつぶん', 'じゅきぶん', 'じゅちぶん', 'じゅうぶん', 4, '十分 = 十 (Thập → じゅう) + 分 (Phân → ぶん) → じゅうぶん: đủ. 十 (Thập) → じゅう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "十", "hanViet": "Thập", "reading": "ジュウ", "romaji": "juu", "focus": "十分"}', @onyomi_quiz_2_4, 6, 'onyomi:FIN_P:十:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 急行 (Cấp Hành) đọc là gì?', 'きゅうこう', 'きゅうこう', 'きゅきこう', 'きゅちこう', 'きゅつこう', 1, '急行 = 急 (Cấp → きゅう) + 行 (Hành → こう) → きゅうこう: tàu nhanh. 急 (Cấp) → きゅう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "急", "hanViet": "Cấp", "reading": "キュウ", "romaji": "kyuu", "focus": "急行"}', @onyomi_quiz_2_4, 7, 'onyomi:FIN_P:急:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 方法 (Phương Pháp) đọc là gì?', 'ほうほう', 'ほうほく', 'ほうほう', 'ほうほち', 'ほうほき', 2, '方法 = 方 (Phương → ほう) + 法 (Pháp → ほう) → ほうほう: phương pháp. 法 (Pháp) → ほう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "法", "hanViet": "Pháp", "reading": "ホウ", "romaji": "hou", "focus": "方法"}', @onyomi_quiz_2_4, 8, 'onyomi:FIN_P:法:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 合格 (Hợp Cách) đọc là gì?', 'ごうかく', 'ごちかく', 'ごうかく', 'ごきかく', 'ごつかく', 2, '合格 = 合 (Hợp → ごう) + 格 (Cách → かく) → ごうかく: thi đỗ. 合 (Hợp) → ごう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "合", "hanViet": "Hợp", "reading": "ゴウ", "romaji": "gou", "focus": "合格"}', @onyomi_quiz_2_4, 9, 'onyomi:FIN_P:合:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 集中 (Tập Trung) đọc là gì?', 'しゅうちゅう', 'しゅつちゅう', 'しゅちちゅう', 'しゅきちゅう', 'しゅうちゅう', 4, '集中 = 集 (Tập → しゅう) + 中 (Trung → ちゅう) → しゅうちゅう: tập trung. 集 (Tập) → しゅう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "集", "hanViet": "Tập", "reading": "シュウ", "romaji": "shuu", "focus": "集中"}', @onyomi_quiz_2_4, 10, 'onyomi:FIN_P:集:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 練習 (Luyện Tập) đọc là gì?', 'れんしゅう', 'れんしゅき', 'れんしゅつ', 'れんしゅち', 'れんしゅう', 4, '練習 = 練 (Luyện → れん) + 習 (Tập → しゅう) → れんしゅう: luyện tập. 習 (Tập) → しゅう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "習", "hanViet": "Tập", "reading": "シュウ", "romaji": "shuu", "focus": "練習"}', @onyomi_quiz_2_4, 11, 'onyomi:FIN_P:習:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 協力 (Hiệp Lực) đọc là gì?', 'きょうりょく', 'きょちりょく', 'きょうりょく', 'きょつりょく', 'きょきりょく', 2, '協力 = 協 (Hiệp → きょう) + 力 (Lực → りょく) → きょうりょく: hợp tác. 協 (Hiệp) → きょう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "協", "hanViet": "Hiệp", "reading": "キョウ", "romaji": "kyou", "focus": "協力"}', @onyomi_quiz_2_4, 12, 'onyomi:FIN_P:協:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 回答 (Hồi Đáp) đọc là gì?', 'かいとう', 'かいとつ', 'かいとち', 'かいとう', 'かいとき', 3, '回答 = 回 (Hồi → かい) + 答 (Đáp → とう) → かいとう: câu trả lời. 答 (Đáp) → とう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "答", "hanViet": "Đáp", "reading": "トウ", "romaji": "tou", "focus": "回答"}', @onyomi_quiz_2_4, 13, 'onyomi:FIN_P:答:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 直接 (Trực Tiếp) đọc là gì?', 'ちょくせつ', 'ちょくせつ', 'ちょくしょう', 'ちょくせん', 'ちょくそう', 1, '直接 = 直 (Trực → ちょく) + 接 (Tiếp → せつ) → ちょくせつ: trực tiếp. 接 (Tiếp) là ngoại lệ của quy tắc Âm cuối P → ウ (trường âm): 接 đọc セツ: -P thành ツ thay vì trường âm.', '{"type": "EXCEPTION", "rule": "FIN_P", "kanji": "接", "hanViet": "Tiếp", "reading": "セツ", "romaji": "setsu", "note": "接 đọc セツ: -P thành ツ thay vì trường âm.", "word": "直接", "wordReading": "ちょくせつ", "meaning": "trực tiếp", "focus": "直接"}', @onyomi_quiz_2_4, 14, 'onyomi:FIN_P:接:EXCEPTION'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 圧力 (Áp Lực) đọc là gì?', 'あつりょく', 'あんりょく', 'おうりょく', 'あうりょく', 'あつりょく', 4, '圧力 = 圧 (Áp → あつ) + 力 (Lực → りょく) → あつりょく: áp lực. 圧 (Áp) là ngoại lệ của quy tắc Âm cuối P → ウ (trường âm): 圧 đọc アツ: -P thành ツ thay vì trường âm.', '{"type": "EXCEPTION", "rule": "FIN_P", "kanji": "圧", "hanViet": "Áp", "reading": "アツ", "romaji": "atsu", "note": "圧 đọc アツ: -P thành ツ thay vì trường âm.", "word": "圧力", "wordReading": "あつりょく", "meaning": "áp lực", "focus": "圧力"}', @onyomi_quiz_2_4, 15, 'onyomi:FIN_P:圧:EXCEPTION'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 安全 (An Toàn) đọc là gì?', 'あんぜん', 'えんぜん', 'いんぜん', 'あんぜん', 'おうぜん', 3, '安全 = 安 (An → あん) + 全 (Toàn → ぜん) → あんぜん: an toàn. 安 (An) → あん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "安", "hanViet": "An", "reading": "アン", "romaji": "an", "focus": "安全"}', @onyomi_quiz_3_1, 1, 'onyomi:RHY_AN:安:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 時間 (Thời Gian) đọc là gì?', 'じかん', 'じくん', 'じかん', 'じきん', 'じこう', 2, '時間 = 時 (Thời → じ) + 間 (Gian → かん) → じかん: thời gian. 間 (Gian) → かん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "間", "hanViet": "Gian", "reading": "カン", "romaji": "kan", "focus": "時間"}', @onyomi_quiz_3_1, 2, 'onyomi:RHY_AN:間:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 漢字 (Hán Tự) đọc là gì?', 'かんじ', 'きんじ', 'けんじ', 'かんじ', 'こうじ', 3, '漢字 = 漢 (Hán → かん) + 字 (Tự → じ) → かんじ: chữ Hán. 漢 (Hán) → かん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "漢", "hanViet": "Hán", "reading": "カン", "romaji": "kan", "focus": "漢字"}', @onyomi_quiz_3_1, 3, 'onyomi:RHY_AN:漢:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 反対 (Phản Đối) đọc là gì?', 'はんたい', 'ひんたい', 'はんたい', 'へんたい', 'ほうたい', 2, '反対 = 反 (Phản → はん) + 対 (Đối → たい) → はんたい: phản đối. 反 (Phản) → はん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "反", "hanViet": "Phản", "reading": "ハン", "romaji": "han", "focus": "反対"}', @onyomi_quiz_3_1, 4, 'onyomi:RHY_AN:反:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 半分 (Bán Phân) đọc là gì?', 'はんぶん', 'ひんぶん', 'はんぶん', 'へんぶん', 'ほうぶん', 2, '半分 = 半 (Bán → はん) + 分 (Phân → ぶん) → はんぶん: một nửa. 半 (Bán) → はん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "半", "hanViet": "Bán", "reading": "ハン", "romaji": "han", "focus": "半分"}', @onyomi_quiz_3_1, 5, 'onyomi:RHY_AN:半:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 図書館 (Đồ Thư Quán) đọc là gì?', 'としょかん', 'としょきん', 'としょけん', 'としょこう', 'としょかん', 4, '図書館 = 図 (Đồ → と) + 書 (Thư → しょ) + 館 (Quán → かん) → としょかん: thư viện. 館 (Quán) → かん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "館", "hanViet": "Quán", "reading": "カン", "romaji": "kan", "focus": "図書館"}', @onyomi_quiz_3_1, 6, 'onyomi:RHY_AN:館:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 完全 (Hoàn Toàn) đọc là gì?', 'かんぜん', 'かんぜん', 'きんぜん', 'こうぜん', 'けんぜん', 1, '完全 = 完 (Hoàn → かん) + 全 (Toàn → ぜん) → かんぜん: hoàn toàn. 完 (Hoàn) → かん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "完", "hanViet": "Hoàn", "reading": "カン", "romaji": "kan", "focus": "完全"}', @onyomi_quiz_3_1, 7, 'onyomi:RHY_AN:完:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 散歩 (Tán Bộ) đọc là gì?', 'さんぽ', 'さんぽ', 'そうぽ', 'せんぽ', 'しんぽ', 1, '散歩 = 散 (Tán → さん) + 歩 (Bộ → ぽ) → さんぽ: đi dạo. 散 (Tán) → さん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "散", "hanViet": "Tán", "reading": "サン", "romaji": "san", "focus": "散歩"}', @onyomi_quiz_3_1, 8, 'onyomi:RHY_AN:散:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 三月 (Tam Nguyệt) đọc là gì?', 'さんがつ', 'しんがつ', 'せんがつ', 'さんがつ', 'そうがつ', 3, '三月 = 三 (Tam → さん) + 月 (Nguyệt → がつ) → さんがつ: tháng ba. 三 (Tam) → さん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "三", "hanViet": "Tam", "reading": "サン", "romaji": "san", "focus": "三月"}', @onyomi_quiz_3_1, 9, 'onyomi:RHY_AM:三:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 感動 (Cảm Động) đọc là gì?', 'かんどう', 'きんどう', 'こうどう', 'かんどう', 'けんどう', 3, '感動 = 感 (Cảm → かん) + 動 (Động → どう) → かんどう: cảm động. 感 (Cảm) → かん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "感", "hanViet": "Cảm", "reading": "カン", "romaji": "kan", "focus": "感動"}', @onyomi_quiz_3_1, 10, 'onyomi:RHY_AM:感:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 暗記 (Ám Ký) đọc là gì?', 'あんき', 'えんき', 'あんき', 'おうき', 'いんき', 2, '暗記 = 暗 (Ám → あん) + 記 (Ký → き) → あんき: học thuộc lòng. 暗 (Ám) → あん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "暗", "hanViet": "Ám", "reading": "アン", "romaji": "an", "focus": "暗記"}', @onyomi_quiz_3_1, 11, 'onyomi:RHY_AM:暗:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 相談 (Tương Đàm) đọc là gì?', 'そうだん', 'そうどう', 'そうでい', 'そうでん', 'そうだん', 4, '相談 = 相 (Tương → そう) + 談 (Đàm → だん) → そうだん: trao đổi. 談 (Đàm) → だん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "談", "hanViet": "Đàm", "reading": "ダン", "romaji": "dan", "focus": "相談"}', @onyomi_quiz_3_1, 12, 'onyomi:RHY_AM:談:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 南極 (Nam Cực) đọc là gì?', 'なんきょく', 'ねんきょく', 'なんきょく', 'のうきょく', 'にんきょく', 2, '南極 = 南 (Nam → なん) + 極 (Cực → きょく) → なんきょく: Nam Cực. 南 (Nam) → なん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "南", "hanViet": "Nam", "reading": "ナン", "romaji": "nan", "focus": "南極"}', @onyomi_quiz_3_1, 13, 'onyomi:RHY_AM:南:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 担当 (Đảm Đương) đọc là gì?', 'たんとう', 'ちんとう', 'てんとう', 'とうとう', 'たんとう', 4, '担当 = 担 (Đảm → たん) + 当 (Đương → とう) → たんとう: phụ trách. 担 (Đảm) → たん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "担", "hanViet": "Đảm", "reading": "タン", "romaji": "tan", "focus": "担当"}', @onyomi_quiz_3_1, 14, 'onyomi:RHY_AM:担:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 参加 (Tham Gia) đọc là gì?', 'さんか', 'せんか', 'そうか', 'しんか', 'さんか', 4, '参加 = 参 (Tham → さん) + 加 (Gia → か) → さんか: tham gia. 参 (Tham) → さん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "参", "hanViet": "Tham", "reading": "サン", "romaji": "san", "focus": "参加"}', @onyomi_quiz_3_1, 15, 'onyomi:RHY_AM:参:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 印象 (Ấn Tượng) đọc là gì?', 'いんしょう', 'いんしょう', 'えんしょう', 'あんしょう', 'えいしょう', 1, '印象 = 印 (Ấn → いん) + 象 (Tượng → しょう) → いんしょう: ấn tượng. 印 (Ấn) → いん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "印", "hanViet": "Ấn", "reading": "イン", "romaji": "in", "focus": "印象"}', @onyomi_quiz_3_2, 1, 'onyomi:RHY_AAN:印:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 近所 (Cận Sở) đọc là gì?', 'きんじょ', 'きんじょ', 'けんじょ', 'けいじょ', 'かんじょ', 1, '近所 = 近 (Cận → きん) + 所 (Sở → じょ) → きんじょ: hàng xóm. 近 (Cận) → きん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "近", "hanViet": "Cận", "reading": "キン", "romaji": "kin", "focus": "近所"}', @onyomi_quiz_3_2, 2, 'onyomi:RHY_AAN:近:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 新聞 (Tân Văn) đọc là gì?', 'しんぶん', 'せんぶん', 'しんぶん', 'さんぶん', 'せいぶん', 2, '新聞 = 新 (Tân → しん) + 聞 (Văn → ぶん) → しんぶん: báo. 新 (Tân) → しん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "新", "hanViet": "Tân", "reading": "シン", "romaji": "shin", "focus": "新聞"}', @onyomi_quiz_3_2, 3, 'onyomi:RHY_AAN:新:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 自身 (Tự Thân) đọc là gì?', 'じしん', 'じさん', 'じせん', 'じしん', 'じせい', 3, '自身 = 自 (Tự → じ) + 身 (Thân → しん) → じしん: bản thân. 身 (Thân) → しん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "身", "hanViet": "Thân", "reading": "シン", "romaji": "shin", "focus": "自身"}', @onyomi_quiz_3_2, 4, 'onyomi:RHY_AAN:身:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 国民 (Quốc Dân) đọc là gì?', 'こくみん', 'こくみん', 'こくめん', 'こくまん', 'こくめい', 1, '国民 = 国 (Quốc → こく) + 民 (Dân → みん) → こくみん: quốc dân. 民 (Dân) → みん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "民", "hanViet": "Dân", "reading": "ミン", "romaji": "min", "focus": "国民"}', @onyomi_quiz_3_2, 5, 'onyomi:RHY_AAN:民:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 写真 (Tả Chân) đọc là gì?', 'しゃしん', 'しゃせん', 'しゃしん', 'しゃせい', 'しゃさん', 2, '写真 = 写 (Tả → しゃ) + 真 (Chân → しん) → しゃしん: ảnh chụp. 真 (Chân) → しん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "真", "hanViet": "Chân", "reading": "シン", "romaji": "shin", "focus": "写真"}', @onyomi_quiz_3_2, 6, 'onyomi:RHY_AAN:真:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 親切 (Thân Thiết) đọc là gì?', 'しんせつ', 'しんせつ', 'せんせつ', 'さんせつ', 'せいせつ', 1, '親切 = 親 (Thân → しん) + 切 (Thiết → せつ) → しんせつ: tử tế. 親 (Thân) → しん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "親", "hanViet": "Thân", "reading": "シン", "romaji": "shin", "focus": "親切"}', @onyomi_quiz_3_2, 7, 'onyomi:RHY_AAN:親:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 日本人 (Nhật Bản Nhân) đọc là gì?', 'にほんじん', 'にほんぜい', 'にほんじん', 'にほんぜん', 'にほんざん', 2, '日本人 = 日 (Nhật → に) + 本 (Bản → ほん) + 人 (Nhân → じん) → にほんじん: người Nhật. 人 (Nhân) → じん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "人", "hanViet": "Nhân", "reading": "ジン", "romaji": "jin", "focus": "日本人"}', @onyomi_quiz_3_2, 8, 'onyomi:RHY_AAN:人:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 安心 (An Tâm) đọc là gì?', 'あんしん', 'あんさん', 'あんせん', 'あんしん', 'あんせい', 3, '安心 = 安 (An → あん) + 心 (Tâm → しん) → あんしん: yên tâm. 心 (Tâm) → しん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "心", "hanViet": "Tâm", "reading": "シン", "romaji": "shin", "focus": "安心"}', @onyomi_quiz_3_2, 9, 'onyomi:RHY_AAM:心:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 森林 (Sâm Lâm) đọc là gì?', 'しんりん', 'しんらん', 'しんれい', 'しんれん', 'しんりん', 4, '森林 = 森 (Sâm → しん) + 林 (Lâm → りん) → しんりん: rừng. 林 (Lâm) → りん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "林", "hanViet": "Lâm", "reading": "リン", "romaji": "rin", "focus": "森林"}', @onyomi_quiz_3_2, 10, 'onyomi:RHY_AAM:林:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 陰気 (Âm Khí) đọc là gì?', 'いんき', 'えいき', 'あんき', 'えんき', 'いんき', 4, '陰気 = 陰 (Âm → いん) + 気 (Khí → き) → いんき: u ám. 陰 (Âm) → いん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "陰", "hanViet": "Âm", "reading": "イン", "romaji": "in", "focus": "陰気"}', @onyomi_quiz_3_2, 11, 'onyomi:RHY_AAM:陰:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 深刻 (Thâm Khắc) đọc là gì?', 'しんこく', 'せんこく', 'しんこく', 'せいこく', 'さんこく', 2, '深刻 = 深 (Thâm → しん) + 刻 (Khắc → こく) → しんこく: nghiêm trọng. 深 (Thâm) → しん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "深", "hanViet": "Thâm", "reading": "シン", "romaji": "shin", "focus": "深刻"}', @onyomi_quiz_3_2, 12, 'onyomi:RHY_AAM:深:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 方針 (Phương Châm) đọc là gì?', 'ほうしん', 'ほうさん', 'ほうせん', 'ほうせい', 'ほうしん', 4, '方針 = 方 (Phương → ほう) + 針 (Châm → しん) → ほうしん: phương châm. 針 (Châm) → しん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "針", "hanViet": "Châm", "reading": "シン", "romaji": "shin", "focus": "方針"}', @onyomi_quiz_3_2, 13, 'onyomi:RHY_AAM:針:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 禁止 (Cấm Chỉ) đọc là gì?', 'きんし', 'けいし', 'かんし', 'きんし', 'けんし', 3, '禁止 = 禁 (Cấm → きん) + 止 (Chỉ → し) → きんし: cấm. 禁 (Cấm) → きん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "禁", "hanViet": "Cấm", "reading": "キン", "romaji": "kin", "focus": "禁止"}', @onyomi_quiz_3_2, 14, 'onyomi:RHY_AAM:禁:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 作品 (Tác Phẩm) đọc là gì?', 'さくひん', 'さくひん', 'さくへい', 'さくへん', 'さくはん', 1, '作品 = 作 (Tác → さく) + 品 (Phẩm → ひん) → さくひん: tác phẩm. 品 (Phẩm) → ひん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "品", "hanViet": "Phẩm", "reading": "ヒン", "romaji": "hin", "focus": "作品"}', @onyomi_quiz_3_2, 15, 'onyomi:RHY_AAM:品:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 音楽 (Âm Nhạc) đọc là gì?', 'おんがく', 'おんがく', 'えんがく', 'あんがく', 'うんがく', 1, '音楽 = 音 (Âm → おん) + 楽 (Nhạc → がく) → おんがく: âm nhạc. 音 (Âm) là ngoại lệ của quy tắc Vần ÂM → イン: 音 thường đọc オン thay vì イン.', '{"type": "EXCEPTION", "rule": "RHY_AAM", "kanji": "音", "hanViet": "Âm", "reading": "オン", "romaji": "on", "note": "音 thường đọc オン thay vì イン.", "word": "音楽", "wordReading": "おんがく", "meaning": "âm nhạc", "focus": "音楽"}', @onyomi_quiz_3_2, 16, 'onyomi:RHY_AAM:音:EXCEPTION'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 百円 (Bách Viên) đọc là gì?', 'ひゃくえん', 'ひゃくいん', 'ひゃくえん', 'ひゃくあん', 'ひゃくえい', 2, '百円 = 百 (Bách → ひゃく) + 円 (Viên → えん) → ひゃくえん: một trăm yên. 円 (Viên) → えん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "円", "hanViet": "Viên", "reading": "エン", "romaji": "en", "focus": "百円"}', @onyomi_quiz_3_3, 1, 'onyomi:RHY_IEN:円:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 大変 (Đại Biến) đọc là gì?', 'たいへん', 'たいへい', 'たいはん', 'たいへん', 'たいひん', 3, '大変 = 大 (Đại → たい) + 変 (Biến → へん) → たいへん: vất vả. 変 (Biến) → へん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "変", "hanViet": "Biến", "reading": "ヘン", "romaji": "hen", "focus": "大変"}', @onyomi_quiz_3_3, 2, 'onyomi:RHY_IEN:変:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 天気 (Thiên Khí) đọc là gì?', 'てんき', 'たんき', 'てんき', 'ちんき', 'ていき', 2, '天気 = 天 (Thiên → てん) + 気 (Khí → き) → てんき: thời tiết. 天 (Thiên) → てん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "天", "hanViet": "Thiên", "reading": "テン", "romaji": "ten", "focus": "天気"}', @onyomi_quiz_3_3, 3, 'onyomi:RHY_IEN:天:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 先生 (Tiên Sinh) đọc là gì?', 'せんせい', 'せいせい', 'せんせい', 'しんせい', 'さんせい', 2, '先生 = 先 (Tiên → せん) + 生 (Sinh → せい) → せんせい: giáo viên. 先 (Tiên) → せん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "先", "hanViet": "Tiên", "reading": "セン", "romaji": "sen", "focus": "先生"}', @onyomi_quiz_3_3, 4, 'onyomi:RHY_IEN:先:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 現在 (Hiện Tại) đọc là gì?', 'げんざい', 'げんざい', 'ぎんざい', 'げいざい', 'がんざい', 1, '現在 = 現 (Hiện → げん) + 在 (Tại → ざい) → げんざい: hiện tại. 現 (Hiện) → げん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "現", "hanViet": "Hiện", "reading": "ゲン", "romaji": "gen", "focus": "現在"}', @onyomi_quiz_3_3, 5, 'onyomi:RHY_IEN:現:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 電話 (Điện Thoại) đọc là gì?', 'でんわ', 'だんわ', 'でんわ', 'でいわ', 'どうわ', 2, '電話 = 電 (Điện → でん) + 話 (Thoại → わ) → でんわ: điện thoại. 電 (Điện) → でん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "電", "hanViet": "Điện", "reading": "デン", "romaji": "den", "focus": "電話"}', @onyomi_quiz_3_3, 6, 'onyomi:RHY_IEN:電:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 練習 (Luyện Tập) đọc là gì?', 'れんしゅう', 'らんしゅう', 'れんしゅう', 'れいしゅう', 'りんしゅう', 2, '練習 = 練 (Luyện → れん) + 習 (Tập → しゅう) → れんしゅう: luyện tập. 練 (Luyện) → れん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "練", "hanViet": "Luyện", "reading": "レン", "romaji": "ren", "focus": "練習"}', @onyomi_quiz_3_3, 7, 'onyomi:RHY_IEN:練:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 意見 (Ý Kiến) đọc là gì?', 'いけん', 'いけん', 'いけい', 'いかん', 'いきん', 1, '意見 = 意 (Ý → い) + 見 (Kiến → けん) → いけん: ý kiến. 見 (Kiến) → けん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "見", "hanViet": "Kiến", "reading": "ケン", "romaji": "ken", "focus": "意見"}', @onyomi_quiz_3_3, 8, 'onyomi:RHY_IEN:見:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 書店 (Thư Điếm) đọc là gì?', 'しょてん', 'しょたん', 'しょちん', 'しょてん', 'しょてい', 3, '書店 = 書 (Thư → しょ) + 店 (Điếm → てん) → しょてん: hiệu sách. 店 (Điếm) → てん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "店", "hanViet": "Điếm", "reading": "テン", "romaji": "ten", "focus": "書店"}', @onyomi_quiz_3_3, 9, 'onyomi:RHY_IEM:店:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 点数 (Điểm Số) đọc là gì?', 'てんすう', 'たんすう', 'ていすう', 'てんすう', 'ちんすう', 3, '点数 = 点 (Điểm → てん) + 数 (Số → すう) → てんすう: điểm số. 点 (Điểm) → てん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "点", "hanViet": "Điểm", "reading": "テン", "romaji": "ten", "focus": "点数"}', @onyomi_quiz_3_3, 10, 'onyomi:RHY_IEM:点:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 検査 (Kiểm Tra) đọc là gì?', 'けんさ', 'けんさ', 'かんさ', 'けいさ', 'きんさ', 1, '検査 = 検 (Kiểm → けん) + 査 (Tra → さ) → けんさ: kiểm tra. 検 (Kiểm) → けん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "検", "hanViet": "Kiểm", "reading": "ケン", "romaji": "ken", "focus": "検査"}', @onyomi_quiz_3_3, 11, 'onyomi:RHY_IEM:検:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 危険 (Nguy Hiểm) đọc là gì?', 'きけん', 'ききん', 'きけん', 'きかん', 'きけい', 2, '危険 = 危 (Nguy → き) + 険 (Hiểm → けん) → きけん: nguy hiểm. 険 (Hiểm) → けん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "険", "hanViet": "Hiểm", "reading": "ケン", "romaji": "ken", "focus": "危険"}', @onyomi_quiz_3_3, 12, 'onyomi:RHY_IEM:険:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 経験 (Kinh Nghiệm) đọc là gì?', 'けいけん', 'けいけん', 'けいきん', 'けいけい', 'けいかん', 1, '経験 = 経 (Kinh → けい) + 験 (Nghiệm → けん) → けいけん: kinh nghiệm. 験 (Nghiệm) → けん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "験", "hanViet": "Nghiệm", "reading": "ケン", "romaji": "ken", "focus": "経験"}', @onyomi_quiz_3_3, 13, 'onyomi:RHY_IEM:験:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 感染 (Cảm Nhiễm) đọc là gì?', 'かんせん', 'かんせい', 'かんしん', 'かんせん', 'かんさん', 3, '感染 = 感 (Cảm → かん) + 染 (Nhiễm → せん) → かんせん: lây nhiễm. 染 (Nhiễm) → せん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "染", "hanViet": "Nhiễm", "reading": "セン", "romaji": "sen", "focus": "感染"}', @onyomi_quiz_3_3, 14, 'onyomi:RHY_IEM:染:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 記念 (Ký Niệm) đọc là gì?', 'きねん', 'きなん', 'きねん', 'きねい', 'きにん', 2, '記念 = 記 (Ký → き) + 念 (Niệm → ねん) → きねん: kỷ niệm. 念 (Niệm) → ねん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "念", "hanViet": "Niệm", "reading": "ネン", "romaji": "nen", "focus": "記念"}', @onyomi_quiz_3_3, 15, 'onyomi:RHY_IEM:念:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 観光 (Quan Quang) đọc là gì?', 'かんこう', 'かんけん', 'かんきん', 'かんこう', 'かんくう', 3, '観光 = 観 (Quan → かん) + 光 (Quang → こう) → かんこう: tham quan du lịch. 光 (Quang) → こう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "光", "hanViet": "Quang", "reading": "コウ", "romaji": "kou", "focus": "観光"}', @onyomi_quiz_3_4, 1, 'onyomi:RHY_ANG:光:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 空港 (Không Cảng) đọc là gì?', 'くうこう', 'くうきん', 'くうくう', 'くうこう', 'くうけん', 3, '空港 = 空 (Không → くう) + 港 (Cảng → こう) → くうこう: sân bay. 港 (Cảng) → こう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "港", "hanViet": "Cảng", "reading": "コウ", "romaji": "kou", "focus": "空港"}', @onyomi_quiz_3_4, 2, 'onyomi:RHY_ANG:港:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 健康 (Kiện Khang) đọc là gì?', 'けんこう', 'けんこう', 'けんくう', 'けんきん', 'けんけん', 1, '健康 = 健 (Kiện → けん) + 康 (Khang → こう) → けんこう: sức khoẻ. 康 (Khang) → こう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "康", "hanViet": "Khang", "reading": "コウ", "romaji": "kou", "focus": "健康"}', @onyomi_quiz_3_4, 3, 'onyomi:RHY_ANG:康:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 政党 (Chính Đảng) đọc là gì?', 'せいとう', 'せいちん', 'せいとう', 'せいてん', 'せいつう', 2, '政党 = 政 (Chính → せい) + 党 (Đảng → とう) → せいとう: chính đảng. 党 (Đảng) → とう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "党", "hanViet": "Đảng", "reading": "トウ", "romaji": "tou", "focus": "政党"}', @onyomi_quiz_3_4, 4, 'onyomi:RHY_ANG:党:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 講義 (Giảng Nghĩa) đọc là gì?', 'こうぎ', 'きんぎ', 'けんぎ', 'くうぎ', 'こうぎ', 4, '講義 = 講 (Giảng → こう) + 義 (Nghĩa → ぎ) → こうぎ: bài giảng. 講 (Giảng) → こう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "講", "hanViet": "Giảng", "reading": "コウ", "romaji": "kou", "focus": "講義"}', @onyomi_quiz_3_4, 5, 'onyomi:RHY_ANG:講:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 皇居 (Hoàng Cư) đọc là gì?', 'こうきょ', 'こうきょ', 'くうきょ', 'きんきょ', 'けんきょ', 1, '皇居 = 皇 (Hoàng → こう) + 居 (Cư → きょ) → こうきょ: hoàng cung. 皇 (Hoàng) → こう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "皇", "hanViet": "Hoàng", "reading": "コウ", "romaji": "kou", "focus": "皇居"}', @onyomi_quiz_3_4, 6, 'onyomi:RHY_ANG:皇:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 項目 (Hạng Mục) đọc là gì?', 'こうもく', 'けんもく', 'こうもく', 'くうもく', 'きんもく', 2, '項目 = 項 (Hạng → こう) + 目 (Mục → もく) → こうもく: hạng mục. 項 (Hạng) → こう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "項", "hanViet": "Hạng", "reading": "コウ", "romaji": "kou", "focus": "項目"}', @onyomi_quiz_3_4, 7, 'onyomi:RHY_ANG:項:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 銭湯 (Tiền Thang) đọc là gì?', 'せんとう', 'せんつう', 'せんとう', 'せんてん', 'せんちん', 2, '銭湯 = 銭 (Tiền → せん) + 湯 (Thang → とう) → せんとう: nhà tắm công cộng. 湯 (Thang) → とう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "湯", "hanViet": "Thang", "reading": "トウ", "romaji": "tou", "focus": "銭湯"}', @onyomi_quiz_3_4, 8, 'onyomi:RHY_ANG:湯:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 社長 (Xã Trưởng) đọc là gì?', 'しゃちょう', 'しゃちょう', 'しゃちん', 'しゃちゅう', 'しゃてん', 1, '社長 = 社 (Xã → しゃ) + 長 (Trưởng → ちょう) → しゃちょう: giám đốc. 長 (Trưởng) → ちょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "長", "hanViet": "Trưởng", "reading": "チョウ", "romaji": "chou", "focus": "社長"}', @onyomi_quiz_3_4, 9, 'onyomi:RHY_UONG:長:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 文章 (Văn Chương) đọc là gì?', 'ぶんしょう', 'ぶんせん', 'ぶんしょう', 'ぶんしん', 'ぶんしゅう', 2, '文章 = 文 (Văn → ぶん) + 章 (Chương → しょう) → ぶんしょう: văn bản. 章 (Chương) → しょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "章", "hanViet": "Chương", "reading": "ショウ", "romaji": "shou", "focus": "文章"}', @onyomi_quiz_3_4, 10, 'onyomi:RHY_UONG:章:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 日常 (Nhật Thường) đọc là gì?', 'にちじょう', 'にちじょう', 'にちぜん', 'にちじゅう', 'にちじん', 1, '日常 = 日 (Nhật → にち) + 常 (Thường → じょう) → にちじょう: thường ngày. 常 (Thường) → じょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "常", "hanViet": "Thường", "reading": "ジョウ", "romaji": "jou", "focus": "日常"}', @onyomi_quiz_3_4, 11, 'onyomi:RHY_UONG:常:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 方法 (Phương Pháp) đọc là gì?', 'ほうほう', 'ほうほう', 'ふうほう', 'ひんほう', 'へんほう', 1, '方法 = 方 (Phương → ほう) + 法 (Pháp → ほう) → ほうほう: phương pháp. 方 (Phương) → ほう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "方", "hanViet": "Phương", "reading": "ホウ", "romaji": "hou", "focus": "方法"}', @onyomi_quiz_3_4, 12, 'onyomi:RHY_UONG:方:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 会場 (Hội Trường) đọc là gì?', 'かいじょう', 'かいじょう', 'かいじゅう', 'かいじん', 'かいぜん', 1, '会場 = 会 (Hội → かい) + 場 (Trường → じょう) → かいじょう: hội trường. 場 (Trường) → じょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "場", "hanViet": "Trường", "reading": "ジョウ", "romaji": "jou", "focus": "会場"}', @onyomi_quiz_3_4, 13, 'onyomi:RHY_UONG:場:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 相談 (Tương Đàm) đọc là gì?', 'そうだん', 'そうだん', 'しんだん', 'せんだん', 'すうだん', 1, '相談 = 相 (Tương → そう) + 談 (Đàm → だん) → そうだん: trao đổi. 相 (Tương) → そう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "相", "hanViet": "Tương", "reading": "ソウ", "romaji": "sou", "focus": "相談"}', @onyomi_quiz_3_4, 14, 'onyomi:RHY_UONG:相:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 方向 (Phương Hướng) đọc là gì?', 'ほうこう', 'ほうくう', 'ほうこう', 'ほうきん', 'ほうけん', 2, '方向 = 方 (Phương → ほう) + 向 (Hướng → こう) → ほうこう: phương hướng. 向 (Hướng) → こう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "向", "hanViet": "Hướng", "reading": "コウ", "romaji": "kou", "focus": "方向"}', @onyomi_quiz_3_4, 15, 'onyomi:RHY_UONG:向:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 西洋 (Tây Dương) đọc là gì?', 'せいよう', 'せいゆん', 'せいよう', 'せいゆう', 'せいやん', 2, '西洋 = 西 (Tây → せい) + 洋 (Dương → よう) → せいよう: phương Tây. 洋 (Dương) → よう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "洋", "hanViet": "Dương", "reading": "ヨウ", "romaji": "you", "focus": "西洋"}', @onyomi_quiz_3_4, 16, 'onyomi:RHY_UONG:洋:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 工場 (Công Trường) đọc là gì?', 'こうじょう', 'きんじょう', 'こうじょう', 'けんじょう', 'かんじょう', 2, '工場 = 工 (Công → こう) + 場 (Trường → じょう) → こうじょう: nhà máy. 工 (Công) → こう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "工", "hanViet": "Công", "reading": "コウ", "romaji": "kou", "focus": "工場"}', @onyomi_quiz_3_4, 17, 'onyomi:RHY_ONG:工:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 総理 (Tổng Lý) đọc là gì?', 'そうり', 'そうり', 'せんり', 'さんり', 'しんり', 1, '総理 = 総 (Tổng → そう) + 理 (Lý → り) → そうり: thủ tướng. 総 (Tổng) → そう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "総", "hanViet": "Tổng", "reading": "ソウ", "romaji": "sou", "focus": "総理"}', @onyomi_quiz_3_4, 18, 'onyomi:RHY_ONG:総:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 伝統 (Truyền Thống) đọc là gì?', 'でんとう', 'でんてん', 'でんとう', 'でんたん', 'でんちん', 2, '伝統 = 伝 (Truyền → でん) + 統 (Thống → とう) → でんとう: truyền thống. 統 (Thống) → とう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "統", "hanViet": "Thống", "reading": "トウ", "romaji": "tou", "focus": "伝統"}', @onyomi_quiz_3_4, 19, 'onyomi:RHY_ONG:統:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 東京 (Đông Kinh) đọc là gì?', 'とうきょう', 'ちんきょう', 'とうきょう', 'たんきょう', 'てんきょう', 2, '東京 = 東 (Đông → とう) + 京 (Kinh → きょう) → とうきょう: Tokyo. 東 (Đông) → とう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "東", "hanViet": "Đông", "reading": "トウ", "romaji": "tou", "focus": "東京"}', @onyomi_quiz_3_4, 20, 'onyomi:RHY_ONG:東:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 公園 (Công Viên) đọc là gì?', 'こうえん', 'かんえん', 'こうえん', 'けんえん', 'きんえん', 2, '公園 = 公 (Công → こう) + 園 (Viên → えん) → こうえん: công viên. 公 (Công) → こう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "公", "hanViet": "Công", "reading": "コウ", "romaji": "kou", "focus": "公園"}', @onyomi_quiz_3_4, 21, 'onyomi:RHY_ONG:公:WORD');
INSERT INTO assessment_question (conversion_rule_id, knowledge_tag_id, skill, jlpt_level, difficulty,
    question_type, question_text, answer_text, option_1, option_2, option_3, option_4, correct_option,
    explanation, context_json, quiz_id, order_index, title) VALUES
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 同時 (Đồng Thời) đọc là gì?', 'どうじ', 'だんじ', 'どうじ', 'でいじ', 'でんじ', 2, '同時 = 同 (Đồng → どう) + 時 (Thời → じ) → どうじ: đồng thời. 同 (Đồng) → どう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "同", "hanViet": "Đồng", "reading": "ドウ", "romaji": "dou", "focus": "同時"}', @onyomi_quiz_3_4, 22, 'onyomi:RHY_ONG:同:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 交通 (Giao Thông) đọc là gì?', 'こうつう', 'こうつう', 'こうたん', 'こうちん', 'こうてん', 1, '交通 = 交 (Giao → こう) + 通 (Thông → つう) → こうつう: giao thông. 通 (Thông) → つう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "通", "hanViet": "Thông", "reading": "ツウ", "romaji": "tsuu", "focus": "交通"}', @onyomi_quiz_3_4, 23, 'onyomi:RHY_ONG:通:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 空港 (Không Cảng) đọc là gì?', 'くうこう', 'けんこう', 'きんこう', 'かんこう', 'くうこう', 4, '空港 = 空 (Không → くう) + 港 (Cảng → こう) → くうこう: sân bay. 空 (Không) → くう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "空", "hanViet": "Không", "reading": "クウ", "romaji": "kuu", "focus": "空港"}', @onyomi_quiz_3_4, 24, 'onyomi:RHY_ONG:空:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 青年 (Thanh Niên) đọc là gì?', 'せいねん', 'せんねん', 'せいねん', 'そうねん', 'すうねん', 2, '青年 = 青 (Thanh → せい) + 年 (Niên → ねん) → せいねん: thanh niên. 青 (Thanh) → せい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "青", "hanViet": "Thanh", "reading": "セイ", "romaji": "sei", "focus": "青年"}', @onyomi_quiz_3_5, 1, 'onyomi:RHY_ANH:青:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 英語 (Anh Ngữ) đọc là gì?', 'えいご', 'ううご', 'えいご', 'えんご', 'おうご', 2, '英語 = 英 (Anh → えい) + 語 (Ngữ → ご) → えいご: tiếng Anh. 英 (Anh) → えい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "英", "hanViet": "Anh", "reading": "エイ", "romaji": "ei", "focus": "英語"}', @onyomi_quiz_3_5, 2, 'onyomi:RHY_ANH:英:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 有名 (Hữu Danh) đọc là gì?', 'ゆうめい', 'ゆうめん', 'ゆうめい', 'ゆうむう', 'ゆうもう', 2, '有名 = 有 (Hữu → ゆう) + 名 (Danh → めい) → ゆうめい: nổi tiếng. 名 (Danh) → めい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "名", "hanViet": "Danh", "reading": "メイ", "romaji": "mei", "focus": "有名"}', @onyomi_quiz_3_5, 3, 'onyomi:RHY_ANH:名:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 成功 (Thành Công) đọc là gì?', 'せいこう', 'せんこう', 'そうこう', 'すうこう', 'せいこう', 4, '成功 = 成 (Thành → せい) + 功 (Công → こう) → せいこう: thành công. 成 (Thành) → せい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "成", "hanViet": "Thành", "reading": "セイ", "romaji": "sei", "focus": "成功"}', @onyomi_quiz_3_5, 4, 'onyomi:RHY_ANH:成:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 景気 (Cảnh Khí) đọc là gì?', 'けいき', 'けんき', 'こうき', 'くうき', 'けいき', 4, '景気 = 景 (Cảnh → けい) + 気 (Khí → き) → けいき: tình hình kinh tế. 景 (Cảnh) → けい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "景", "hanViet": "Cảnh", "reading": "ケイ", "romaji": "kei", "focus": "景気"}', @onyomi_quiz_3_5, 5, 'onyomi:RHY_ANH:景:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 映画 (Ánh Họa) đọc là gì?', 'えいが', 'ううが', 'えいが', 'おうが', 'えんが', 2, '映画 = 映 (Ánh → えい) + 画 (Họa → が) → えいが: phim. 映 (Ánh) → えい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "映", "hanViet": "Ánh", "reading": "エイ", "romaji": "ei", "focus": "映画"}', @onyomi_quiz_3_5, 6, 'onyomi:RHY_ANH:映:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 警察 (Cảnh Sát) đọc là gì?', 'けいさつ', 'けんさつ', 'けいさつ', 'こうさつ', 'くうさつ', 2, '警察 = 警 (Cảnh → けい) + 察 (Sát → さつ) → けいさつ: cảnh sát. 警 (Cảnh) → けい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "警", "hanViet": "Cảnh", "reading": "ケイ", "romaji": "kei", "focus": "警察"}', @onyomi_quiz_3_5, 7, 'onyomi:RHY_ANH:警:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 経営 (Kinh Doanh) đọc là gì?', 'けいえい', 'けいおう', 'けいうう', 'けいえい', 'けいえん', 3, '経営 = 経 (Kinh → けい) + 営 (Doanh → えい) → けいえい: kinh doanh. 営 (Doanh) → えい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "営", "hanViet": "Doanh", "reading": "エイ", "romaji": "ei", "focus": "経営"}', @onyomi_quiz_3_5, 8, 'onyomi:RHY_ANH:営:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 行動 (Hành Động) đọc là gì?', 'こうどう', 'かいどう', 'こうどう', 'けんどう', 'けいどう', 2, '行動 = 行 (Hành → こう) + 動 (Động → どう) → こうどう: hành động. 行 (Hành) là ngoại lệ của quy tắc Vần ANH → エイ: 行 đọc コウ (hoặc ギョウ): ANH thành オウ thay vì エイ.', '{"type": "EXCEPTION", "rule": "RHY_ANH", "kanji": "行", "hanViet": "Hành", "reading": "コウ", "romaji": "kou", "note": "行 đọc コウ (hoặc ギョウ): ANH thành オウ thay vì エイ.", "word": "行動", "wordReading": "こうどう", "meaning": "hành động", "focus": "行動"}', @onyomi_quiz_3_5, 9, 'onyomi:RHY_ANH:行:EXCEPTION'),
    (@onyomi_rule_rhy_enh, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 生命 (Sinh Mệnh) đọc là gì?', 'せいめい', 'せいもう', 'せいめい', 'せいむう', 'せいめん', 2, '生命 = 生 (Sinh → せい) + 命 (Mệnh → めい) → せいめい: sinh mệnh. 命 (Mệnh) → めい theo quy tắc Vần ÊNH → エイ: Vần ÊNH thường thành エイ. Vần này ít chữ, nên gặp là nhớ luôn.', '{"type": "WORD", "rule": "RHY_ENH", "kanji": "命", "hanViet": "Mệnh", "reading": "メイ", "romaji": "mei", "focus": "生命"}', @onyomi_quiz_3_5, 10, 'onyomi:RHY_ENH:命:WORD'),
    (@onyomi_rule_rhy_enh, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 命令 (Mệnh Lệnh) đọc là gì?', 'めいれい', 'めいれん', 'めいろう', 'めいれい', 'めいるう', 3, '命令 = 命 (Mệnh → めい) + 令 (Lệnh → れい) → めいれい: mệnh lệnh. 令 (Lệnh) → れい theo quy tắc Vần ÊNH → エイ: Vần ÊNH thường thành エイ. Vần này ít chữ, nên gặp là nhớ luôn.', '{"type": "WORD", "rule": "RHY_ENH", "kanji": "令", "hanViet": "Lệnh", "reading": "レイ", "romaji": "rei", "focus": "命令"}', @onyomi_quiz_3_5, 11, 'onyomi:RHY_ENH:令:WORD'),
    (@onyomi_rule_rhy_enh, @onyomi_tag_3, 'KANJI', 'N3', 'HARD', 'MULTIPLE_CHOICE', 'Từ 病院 (Bệnh Viện) đọc là gì?', 'びょういん', 'びんいん', 'びょういん', 'べいいん', 'べんいん', 2, '病院 = 病 (Bệnh → びょう) + 院 (Viện → いん) → びょういん: bệnh viện. 病 (Bệnh) là ngoại lệ của quy tắc Vần ÊNH → エイ: 病 đọc ビョウ: ÊNH thành ョウ thay vì エイ.', '{"type": "EXCEPTION", "rule": "RHY_ENH", "kanji": "病", "hanViet": "Bệnh", "reading": "ビョウ", "romaji": "byou", "note": "病 đọc ビョウ: ÊNH thành ョウ thay vì エイ.", "word": "病院", "wordReading": "びょういん", "meaning": "bệnh viện", "focus": "病院"}', @onyomi_quiz_3_5, 12, 'onyomi:RHY_ENH:病:EXCEPTION'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 学生 (Học Sinh) đọc là gì?', 'がくせい', 'がくすう', 'がくせい', 'がくせん', 'がくそう', 2, '学生 = 学 (Học → がく) + 生 (Sinh → せい) → がくせい: học sinh. 生 (Sinh) → せい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "生", "hanViet": "Sinh", "reading": "セイ", "romaji": "sei", "focus": "学生"}', @onyomi_quiz_3_5, 13, 'onyomi:RHY_INH:生:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 精神 (Tinh Thần) đọc là gì?', 'せいしん', 'せいしん', 'せんしん', 'すうしん', 'そうしん', 1, '精神 = 精 (Tinh → せい) + 神 (Thần → しん) → せいしん: tinh thần. 精 (Tinh) → せい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "精", "hanViet": "Tinh", "reading": "セイ", "romaji": "sei", "focus": "精神"}', @onyomi_quiz_3_5, 14, 'onyomi:RHY_INH:精:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 予定 (Dự Định) đọc là gì?', 'よてい', 'よてい', 'よてん', 'よつう', 'よとう', 1, '予定 = 予 (Dự → よ) + 定 (Định → てい) → よてい: dự định. 定 (Định) → てい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "定", "hanViet": "Định", "reading": "テイ", "romaji": "tei", "focus": "予定"}', @onyomi_quiz_3_5, 15, 'onyomi:RHY_INH:定:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 平和 (Bình Hòa) đọc là gì?', 'へいわ', 'へいわ', 'ほうわ', 'ふうわ', 'へんわ', 1, '平和 = 平 (Bình → へい) + 和 (Hòa → わ) → へいわ: hoà bình. 平 (Bình) → へい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "平", "hanViet": "Bình", "reading": "ヘイ", "romaji": "hei", "focus": "平和"}', @onyomi_quiz_3_5, 16, 'onyomi:RHY_INH:平:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 説明 (Thuyết Minh) đọc là gì?', 'せつめい', 'せつめん', 'せつめい', 'せつもう', 'せつむう', 2, '説明 = 説 (Thuyết → せつ) + 明 (Minh → めい) → せつめい: giải thích. 明 (Minh) → めい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "明", "hanViet": "Minh", "reading": "メイ", "romaji": "mei", "focus": "説明"}', @onyomi_quiz_3_5, 17, 'onyomi:RHY_INH:明:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 図形 (Đồ Hình) đọc là gì?', 'ずけい', 'ずけん', 'ずこう', 'ずけい', 'ずくう', 3, '図形 = 図 (Đồ → ず) + 形 (Hình → けい) → ずけい: hình vẽ. 形 (Hình) → けい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "形", "hanViet": "Hình", "reading": "ケイ", "romaji": "kei", "focus": "図形"}', @onyomi_quiz_3_5, 18, 'onyomi:RHY_INH:形:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 性格 (Tính Cách) đọc là gì?', 'せいかく', 'すうかく', 'せんかく', 'せいかく', 'そうかく', 3, '性格 = 性 (Tính → せい) + 格 (Cách → かく) → せいかく: tính cách. 性 (Tính) → せい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "性", "hanViet": "Tính", "reading": "セイ", "romaji": "sei", "focus": "性格"}', @onyomi_quiz_3_5, 19, 'onyomi:RHY_INH:性:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 永遠 (Vĩnh Viễn) đọc là gì?', 'えいえん', 'おうえん', 'えいえん', 'ううえん', 'えんえん', 2, '永遠 = 永 (Vĩnh → えい) + 遠 (Viễn → えん) → えいえん: vĩnh viễn. 永 (Vĩnh) → えい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "永", "hanViet": "Vĩnh", "reading": "エイ", "romaji": "ei", "focus": "永遠"}', @onyomi_quiz_3_5, 20, 'onyomi:RHY_INH:永:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 固定 (Cố Định) đọc là gì?', 'こてい', 'ごてい', 'こてい', 'そてい', 'ほてい', 2, '固定 = 固 (Cố → こ) + 定 (Định → てい) → こてい: cố định. 固 (Cố) → こ theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "固", "hanViet": "Cố", "reading": "コ", "romaji": "ko", "focus": "固定"}', @onyomi_quiz_1_1, 25, 'onyomi:INIT_C:固:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 個人 (Cá Nhân) đọc là gì?', 'こじん', 'ほじん', 'そじん', 'こじん', 'ごじん', 3, '個人 = 個 (Cá → こ) + 人 (Nhân → じん) → こじん: cá nhân. 個 (Cá) → こ theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "個", "hanViet": "Cá", "reading": "コ", "romaji": "ko", "focus": "個人"}', @onyomi_quiz_1_1, 26, 'onyomi:INIT_C:個:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 事故 (Sự Cố) đọc là gì?', 'じこ', 'じご', 'じこ', 'じそ', 'じほ', 2, '事故 = 事 (Sự → じ) + 故 (Cố → こ) → じこ: tai nạn. 故 (Cố) → こ theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "故", "hanViet": "Cố", "reading": "コ", "romaji": "ko", "focus": "事故"}', @onyomi_quiz_1_1, 27, 'onyomi:INIT_C:故:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 九州 (Cửu Châu) đọc là gì?', 'きゅうしゅう', 'ぎゅうしゅう', 'きゅうしゅう', 'しゅうしゅう', 'ひゅうしゅう', 2, '九州 = 九 (Cửu → きゅう) + 州 (Châu → しゅう) → きゅうしゅう: đảo Kyushu. 九 (Cửu) → きゅう theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "九", "hanViet": "Cửu", "reading": "キュウ", "romaji": "kyuu", "focus": "九州"}', @onyomi_quiz_1_1, 28, 'onyomi:INIT_C:九:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 研究 (Nghiên Cứu) đọc là gì?', 'けんきゅう', 'けんひゅう', 'けんぎゅう', 'けんきゅう', 'けんしゅう', 3, '研究 = 研 (Nghiên → けん) + 究 (Cứu → きゅう) → けんきゅう: nghiên cứu. 究 (Cứu) → きゅう theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "究", "hanViet": "Cứu", "reading": "キュウ", "romaji": "kyuu", "focus": "研究"}', @onyomi_quiz_1_1, 29, 'onyomi:INIT_C:究:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 共通 (Cộng Thông) đọc là gì?', 'きょうつう', 'ぎょうつう', 'きょうつう', 'ひょうつう', 'しょうつう', 2, '共通 = 共 (Cộng → きょう) + 通 (Thông → つう) → きょうつう: chung. 共 (Cộng) → きょう theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "共", "hanViet": "Cộng", "reading": "キョウ", "romaji": "kyou", "focus": "共通"}', @onyomi_quiz_1_1, 30, 'onyomi:INIT_C:共:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 機会 (Cơ Hội) đọc là gì?', 'きかい', 'ひかい', 'ぎかい', 'しかい', 'きかい', 4, '機会 = 機 (Cơ → き) + 会 (Hội → かい) → きかい: cơ hội. 機 (Cơ) → き theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "機", "hanViet": "Cơ", "reading": "キ", "romaji": "ki", "focus": "機会"}', @onyomi_quiz_1_1, 31, 'onyomi:INIT_C:機:WORD'),
    (@onyomi_rule_init_c, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 基本 (Cơ Bản) đọc là gì?', 'きほん', 'ひほん', 'ぎほん', 'しほん', 'きほん', 4, '基本 = 基 (Cơ → き) + 本 (Bản → ほん) → きほん: cơ bản. 基 (Cơ) → き theo quy tắc Âm đầu C → K: Âm đầu C (không phải CH) thường tương ứng với hàng カ (K) trong âm On.', '{"type": "WORD", "rule": "INIT_C", "kanji": "基", "hanViet": "Cơ", "reading": "キ", "romaji": "ki", "focus": "基本"}', @onyomi_quiz_1_1, 32, 'onyomi:INIT_C:基:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 計画 (Kế Hoạch) đọc là gì?', 'けいかく', 'せいかく', 'けいかく', 'げいかく', 'へいかく', 2, '計画 = 計 (Kế → けい) + 画 (Hoạch → かく) → けいかく: kế hoạch. 計 (Kế) → けい theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "計", "hanViet": "Kế", "reading": "ケイ", "romaji": "kei", "focus": "計画"}', @onyomi_quiz_1_1, 33, 'onyomi:INIT_K:計:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 事件 (Sự Kiện) đọc là gì?', 'じけん', 'じせん', 'じへん', 'じけん', 'じげん', 3, '事件 = 事 (Sự → じ) + 件 (Kiện → けん) → じけん: vụ việc. 件 (Kiện) → けん theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "件", "hanViet": "Kiện", "reading": "ケン", "romaji": "ken", "focus": "事件"}', @onyomi_quiz_1_1, 34, 'onyomi:INIT_K:件:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 健康 (Kiện Khang) đọc là gì?', 'けんこう', 'げんこう', 'へんこう', 'せんこう', 'けんこう', 4, '健康 = 健 (Kiện → けん) + 康 (Khang → こう) → けんこう: sức khoẻ. 健 (Kiện) → けん theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "健", "hanViet": "Kiện", "reading": "ケン", "romaji": "ken", "focus": "健康"}', @onyomi_quiz_1_1, 35, 'onyomi:INIT_K:健:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 建設 (Kiến Thiết) đọc là gì?', 'けんせつ', 'けんせつ', 'せんせつ', 'げんせつ', 'へんせつ', 1, '建設 = 建 (Kiến → けん) + 設 (Thiết → せつ) → けんせつ: xây dựng. 建 (Kiến) → けん theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "建", "hanViet": "Kiến", "reading": "ケン", "romaji": "ken", "focus": "建設"}', @onyomi_quiz_1_1, 36, 'onyomi:INIT_K:建:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 寄付 (Ký Phó) đọc là gì?', 'きふ', 'ひふ', 'ぎふ', 'しふ', 'きふ', 4, '寄付 = 寄 (Ký → き) + 付 (Phó → ふ) → きふ: quyên góp. 寄 (Ký) → き theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "寄", "hanViet": "Ký", "reading": "キ", "romaji": "ki", "focus": "寄付"}', @onyomi_quiz_1_1, 37, 'onyomi:INIT_K:寄:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 尊敬 (Tôn Kính) đọc là gì?', 'そんけい', 'そんげい', 'そんへい', 'そんせい', 'そんけい', 4, '尊敬 = 尊 (Tôn → そん) + 敬 (Kính → けい) → そんけい: tôn kính. 敬 (Kính) → けい theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "敬", "hanViet": "Kính", "reading": "ケイ", "romaji": "kei", "focus": "尊敬"}', @onyomi_quiz_1_1, 38, 'onyomi:INIT_K:敬:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 今月 (Kim Nguyệt) đọc là gì?', 'こんげつ', 'そんげつ', 'ごんげつ', 'ほんげつ', 'こんげつ', 4, '今月 = 今 (Kim → こん) + 月 (Nguyệt → げつ) → こんげつ: tháng này. 今 (Kim) → こん theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "今", "hanViet": "Kim", "reading": "コン", "romaji": "kon", "focus": "今月"}', @onyomi_quiz_1_1, 39, 'onyomi:INIT_K:今:WORD'),
    (@onyomi_rule_init_k, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 国旗 (Quốc Kỳ) đọc là gì?', 'こっき', 'こっち', 'こっき', 'こっし', 'こっぴ', 2, '国旗 = 国 (Quốc → こっ) + 旗 (Kỳ → き) → こっき: quốc kỳ. 旗 (Kỳ) → き theo quy tắc Âm đầu K → K: Âm đầu K thường giữ hàng カ (K); đôi khi thành ガ (G).', '{"type": "WORD", "rule": "INIT_K", "kanji": "旗", "hanViet": "Kỳ", "reading": "キ", "romaji": "ki", "focus": "国旗"}', @onyomi_quiz_1_1, 40, 'onyomi:INIT_K:旗:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 管理 (Quản Lý) đọc là gì?', 'かんり', 'さんり', 'はんり', 'がんり', 'かんり', 4, '管理 = 管 (Quản → かん) + 理 (Lý → り) → かんり: quản lý. 管 (Quản) → かん theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "管", "hanViet": "Quản", "reading": "カン", "romaji": "kan", "focus": "管理"}', @onyomi_quiz_1_1, 41, 'onyomi:INIT_QU:管:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 観察 (Quan Sát) đọc là gì?', 'かんさつ', 'さんさつ', 'かんさつ', 'はんさつ', 'がんさつ', 2, '観察 = 観 (Quan → かん) + 察 (Sát → さつ) → かんさつ: quan sát. 観 (Quan) → かん theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "観", "hanViet": "Quan", "reading": "カン", "romaji": "kan", "focus": "観察"}', @onyomi_quiz_1_1, 42, 'onyomi:INIT_QU:観:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 広告 (Quảng Cáo) đọc là gì?', 'こうこく', 'ほうこく', 'ごうこく', 'そうこく', 'こうこく', 4, '広告 = 広 (Quảng → こう) + 告 (Cáo → こく) → こうこく: quảng cáo. 広 (Quảng) → こう theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "広", "hanViet": "Quảng", "reading": "コウ", "romaji": "kou", "focus": "広告"}', @onyomi_quiz_1_1, 43, 'onyomi:INIT_QU:広:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 季節 (Quý Tiết) đọc là gì?', 'きせつ', 'ぎせつ', 'きせつ', 'しせつ', 'ひせつ', 2, '季節 = 季 (Quý → き) + 節 (Tiết → せつ) → きせつ: mùa. 季 (Quý) → き theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "季", "hanViet": "Quý", "reading": "キ", "romaji": "ki", "focus": "季節"}', @onyomi_quiz_1_1, 44, 'onyomi:INIT_QU:季:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 貴重 (Quý Trọng) đọc là gì?', 'きちょう', 'ぎちょう', 'しちょう', 'ひちょう', 'きちょう', 4, '貴重 = 貴 (Quý → き) + 重 (Trọng → ちょう) → きちょう: quý giá. 貴 (Quý) → き theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "貴", "hanViet": "Quý", "reading": "キ", "romaji": "ki", "focus": "貴重"}', @onyomi_quiz_1_1, 45, 'onyomi:INIT_QU:貴:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 権利 (Quyền Lợi) đọc là gì?', 'けんり', 'へんり', 'せんり', 'げんり', 'けんり', 4, '権利 = 権 (Quyền → けん) + 利 (Lợi → り) → けんり: quyền lợi. 権 (Quyền) → けん theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "権", "hanViet": "Quyền", "reading": "ケン", "romaji": "ken", "focus": "権利"}', @onyomi_quiz_1_1, 46, 'onyomi:INIT_QU:権:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 帰国 (Quy Quốc) đọc là gì?', 'きこく', 'しこく', 'ぎこく', 'ひこく', 'きこく', 4, '帰国 = 帰 (Quy → き) + 国 (Quốc → こく) → きこく: về nước. 帰 (Quy) → き theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "帰", "hanViet": "Quy", "reading": "キ", "romaji": "ki", "focus": "帰国"}', @onyomi_quiz_1_1, 47, 'onyomi:INIT_QU:帰:WORD'),
    (@onyomi_rule_init_qu, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 平均 (Bình Quân) đọc là gì?', 'へいきん', 'へいぎん', 'へいしん', 'へいひん', 'へいきん', 4, '平均 = 平 (Bình → へい) + 均 (Quân → きん) → へいきん: trung bình. 均 (Quân) → きん theo quy tắc Âm đầu QU → K: Âm đầu QU thường tương ứng với hàng カ (K); một số chữ thành ガ (G).', '{"type": "WORD", "rule": "INIT_QU", "kanji": "均", "hanViet": "Quân", "reading": "キン", "romaji": "kin", "focus": "平均"}', @onyomi_quiz_1_1, 48, 'onyomi:INIT_QU:均:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 火曜日 (Hỏa Diệu Nhật) đọc là gì?', 'かようび', 'はようび', 'かようび', 'なようび', 'さようび', 2, '火曜日 = 火 (Hỏa → か) + 曜 (Diệu → よう) + 日 (Nhật → び) → かようび: thứ ba. 火 (Hỏa) → か theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "火", "hanViet": "Hỏa", "reading": "カ", "romaji": "ka", "focus": "火曜日"}', @onyomi_quiz_1_2, 18, 'onyomi:INIT_H:火:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 幸福 (Hạnh Phúc) đọc là gì?', 'こうふく', 'のうふく', 'こうふく', 'そうふく', 'ほうふく', 2, '幸福 = 幸 (Hạnh → こう) + 福 (Phúc → ふく) → こうふく: hạnh phúc. 幸 (Hạnh) → こう theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "幸", "hanViet": "Hạnh", "reading": "コウ", "romaji": "kou", "focus": "幸福"}', @onyomi_quiz_1_2, 19, 'onyomi:INIT_H:幸:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 気候 (Khí Hậu) đọc là gì?', 'きこう', 'きこう', 'きのう', 'きそう', 'きほう', 1, '気候 = 気 (Khí → き) + 候 (Hậu → こう) → きこう: khí hậu. 候 (Hậu) → こう theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "候", "hanViet": "Hậu", "reading": "コウ", "romaji": "kou", "focus": "気候"}', @onyomi_quiz_1_2, 20, 'onyomi:INIT_H:候:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 番号 (Phiên Hiệu) đọc là gì?', 'ばんごう', 'ばんほう', 'ばんのう', 'ばんそう', 'ばんごう', 4, '番号 = 番 (Phiên → ばん) + 号 (Hiệu → ごう) → ばんごう: số (thứ tự). 号 (Hiệu) → ごう theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "号", "hanViet": "Hiệu", "reading": "ゴウ", "romaji": "gou", "focus": "番号"}', @onyomi_quiz_1_2, 21, 'onyomi:INIT_H:号:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 効果 (Hiệu Quả) đọc là gì?', 'こうか', 'こうか', 'のうか', 'ほうか', 'そうか', 1, '効果 = 効 (Hiệu → こう) + 果 (Quả → か) → こうか: hiệu quả. 効 (Hiệu) → こう theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "効", "hanViet": "Hiệu", "reading": "コウ", "romaji": "kou", "focus": "効果"}', @onyomi_quiz_1_2, 22, 'onyomi:INIT_H:効:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 休日 (Hưu Nhật) đọc là gì?', 'きゅうじつ', 'ひゅうじつ', 'にゅうじつ', 'きゅうじつ', 'しゅうじつ', 3, '休日 = 休 (Hưu → きゅう) + 日 (Nhật → じつ) → きゅうじつ: ngày nghỉ. 休 (Hưu) → きゅう theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "休", "hanViet": "Hưu", "reading": "キュウ", "romaji": "kyuu", "focus": "休日"}', @onyomi_quiz_1_2, 23, 'onyomi:INIT_H:休:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 興味 (Hứng Vị) đọc là gì?', 'きょうみ', 'しょうみ', 'ひょうみ', 'にょうみ', 'きょうみ', 4, '興味 = 興 (Hứng → きょう) + 味 (Vị → み) → きょうみ: hứng thú. 興 (Hứng) → きょう theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "興", "hanViet": "Hứng", "reading": "キョウ", "romaji": "kyou", "focus": "興味"}', @onyomi_quiz_1_2, 24, 'onyomi:INIT_H:興:WORD'),
    (@onyomi_rule_init_h, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 制限 (Chế Hạn) đọc là gì?', 'せいげん', 'せいねん', 'せいへん', 'せいげん', 'せいせん', 3, '制限 = 制 (Chế → せい) + 限 (Hạn → げん) → せいげん: giới hạn. 限 (Hạn) → げん theo quy tắc Âm đầu H → K/G: Âm đầu H trong Hán Việt thường tương ứng với hàng カ (K) hoặc ガ (G), không phải ハ.', '{"type": "WORD", "rule": "INIT_H", "kanji": "限", "hanViet": "Hạn", "reading": "ゲン", "romaji": "gen", "focus": "制限"}', @onyomi_quiz_1_2, 25, 'onyomi:INIT_H:限:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 月曜日 (Nguyệt Diệu Nhật) đọc là gì?', 'げつようび', 'けつようび', 'ねつようび', 'めつようび', 'げつようび', 4, '月曜日 = 月 (Nguyệt → げつ) + 曜 (Diệu → よう) + 日 (Nhật → び) → げつようび: thứ hai. 月 (Nguyệt) → げつ theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "月", "hanViet": "Nguyệt", "reading": "ゲツ", "romaji": "getsu", "focus": "月曜日"}', @onyomi_quiz_1_2, 26, 'onyomi:INIT_NG:月:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 金魚 (Kim Ngư) đọc là gì?', 'きんぎょ', 'きんきょ', 'きんぎょ', 'きんにょ', 'きんみょ', 2, '金魚 = 金 (Kim → きん) + 魚 (Ngư → ぎょ) → きんぎょ: cá vàng. 魚 (Ngư) → ぎょ theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "魚", "hanViet": "Ngư", "reading": "ギョ", "romaji": "gyo", "focus": "金魚"}', @onyomi_quiz_1_2, 27, 'onyomi:INIT_NG:魚:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 午前 (Ngọ Tiền) đọc là gì?', 'ごぜん', 'ごぜん', 'きょぜん', 'こぜん', 'のぜん', 1, '午前 = 午 (Ngọ → ご) + 前 (Tiền → ぜん) → ごぜん: buổi sáng. 午 (Ngọ) → ご theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "午", "hanViet": "Ngọ", "reading": "ゴ", "romaji": "go", "focus": "午前"}', @onyomi_quiz_1_2, 28, 'onyomi:INIT_NG:午:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 海岸 (Hải Ngạn) đọc là gì?', 'かいがん', 'かいなん', 'かいきゃん', 'かいかん', 'かいがん', 4, '海岸 = 海 (Hải → かい) + 岸 (Ngạn → がん) → かいがん: bờ biển. 岸 (Ngạn) → がん theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "岸", "hanViet": "Ngạn", "reading": "ガン", "romaji": "gan", "focus": "海岸"}', @onyomi_quiz_1_2, 29, 'onyomi:INIT_NG:岸:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 会議 (Hội Nghị) đọc là gì?', 'かいぎ', 'かいに', 'かいき', 'かいみ', 'かいぎ', 4, '会議 = 会 (Hội → かい) + 議 (Nghị → ぎ) → かいぎ: cuộc họp. 議 (Nghị) → ぎ theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "議", "hanViet": "Nghị", "reading": "ギ", "romaji": "gi", "focus": "会議"}', @onyomi_quiz_1_2, 30, 'onyomi:INIT_NG:議:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 疑問 (Nghi Vấn) đọc là gì?', 'ぎもん', 'ぎもん', 'きもん', 'にもん', 'みもん', 1, '疑問 = 疑 (Nghi → ぎ) + 問 (Vấn → もん) → ぎもん: nghi vấn. 疑 (Nghi) → ぎ theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "疑", "hanViet": "Nghi", "reading": "ギ", "romaji": "gi", "focus": "疑問"}', @onyomi_quiz_1_2, 31, 'onyomi:INIT_NG:疑:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 芸術 (Nghệ Thuật) đọc là gì?', 'げいじゅつ', 'げいじゅつ', 'けいじゅつ', 'ねいじゅつ', 'めいじゅつ', 1, '芸術 = 芸 (Nghệ → げい) + 術 (Thuật → じゅつ) → げいじゅつ: nghệ thuật. 芸 (Nghệ) → げい theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "芸", "hanViet": "Nghệ", "reading": "ゲイ", "romaji": "gei", "focus": "芸術"}', @onyomi_quiz_1_2, 32, 'onyomi:INIT_NG:芸:WORD'),
    (@onyomi_rule_init_ng, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 原因 (Nguyên Nhân) đọc là gì?', 'げんいん', 'ねんいん', 'めんいん', 'けんいん', 'げんいん', 4, '原因 = 原 (Nguyên → げん) + 因 (Nhân → いん) → げんいん: nguyên nhân. 原 (Nguyên) → げん theo quy tắc Âm đầu NG → G: Âm đầu NG/NGH thường tương ứng với hàng ガ (G).', '{"type": "WORD", "rule": "INIT_NG", "kanji": "原", "hanViet": "Nguyên", "reading": "ゲン", "romaji": "gen", "focus": "原因"}', @onyomi_quiz_1_2, 33, 'onyomi:INIT_NG:原:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 労働 (Lao Động) đọc là gì?', 'ろうどう', 'にょうどう', 'のうどう', 'ろうどう', 'じょうどう', 3, '労働 = 労 (Lao → ろう) + 働 (Động → どう) → ろうどう: lao động. 労 (Lao) → ろう theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "労", "hanViet": "Lao", "reading": "ロウ", "romaji": "rou", "focus": "労働"}', @onyomi_quiz_1_3, 25, 'onyomi:INIT_L:労:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 国立 (Quốc Lập) đọc là gì?', 'こくりつ', 'こくきつ', 'こくにつ', 'こくりつ', 'こくじつ', 3, '国立 = 国 (Quốc → こく) + 立 (Lập → りつ) → こくりつ: quốc lập. 立 (Lập) → りつ theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "立", "hanViet": "Lập", "reading": "リツ", "romaji": "ritsu", "focus": "国立"}', @onyomi_quiz_1_3, 26, 'onyomi:INIT_L:立:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 例外 (Lệ Ngoại) đọc là gì?', 'れいがい', 'れいがい', 'ねいがい', 'ぜいがい', 'けいがい', 1, '例外 = 例 (Lệ → れい) + 外 (Ngoại → がい) → れいがい: ngoại lệ. 例 (Lệ) → れい theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "例", "hanViet": "Lệ", "reading": "レイ", "romaji": "rei", "focus": "例外"}', @onyomi_quiz_1_3, 27, 'onyomi:INIT_L:例:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 連絡 (Liên Lạc) đọc là gì?', 'れんらく', 'けんらく', 'ぜんらく', 'れんらく', 'ねんらく', 3, '連絡 = 連 (Liên → れん) + 絡 (Lạc → らく) → れんらく: liên lạc. 連 (Liên) → れん theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "連", "hanViet": "Liên", "reading": "レン", "romaji": "ren", "focus": "連絡"}', @onyomi_quiz_1_3, 28, 'onyomi:INIT_L:連:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 道路 (Đạo Lộ) đọc là gì?', 'どうろ', 'どうろ', 'どうの', 'どうじょ', 'どうにょ', 1, '道路 = 道 (Đạo → どう) + 路 (Lộ → ろ) → どうろ: đường sá. 路 (Lộ) → ろ theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "路", "hanViet": "Lộ", "reading": "ロ", "romaji": "ro", "focus": "道路"}', @onyomi_quiz_1_3, 29, 'onyomi:INIT_L:路:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 議論 (Nghị Luận) đọc là gì?', 'ぎろん', 'ぎにょん', 'ぎのん', 'ぎじょん', 'ぎろん', 4, '議論 = 議 (Nghị → ぎ) + 論 (Luận → ろん) → ぎろん: thảo luận. 論 (Luận) → ろん theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "論", "hanViet": "Luận", "reading": "ロン", "romaji": "ron", "focus": "議論"}', @onyomi_quiz_1_3, 30, 'onyomi:INIT_L:論:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 法律 (Pháp Luật) đọc là gì?', 'ほうりつ', 'ほうりつ', 'ほうじつ', 'ほうきつ', 'ほうにつ', 1, '法律 = 法 (Pháp → ほう) + 律 (Luật → りつ) → ほうりつ: pháp luật. 律 (Luật) → りつ theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "律", "hanViet": "Luật", "reading": "リツ", "romaji": "ritsu", "focus": "法律"}', @onyomi_quiz_1_3, 31, 'onyomi:INIT_L:律:WORD'),
    (@onyomi_rule_init_l, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 留学 (Lưu Học) đọc là gì?', 'りゅうがく', 'にゅうがく', 'きゅうがく', 'りゅうがく', 'じゅうがく', 3, '留学 = 留 (Lưu → りゅう) + 学 (Học → がく) → りゅうがく: du học. 留 (Lưu) → りゅう theo quy tắc Âm đầu L → R: Âm đầu L luôn tương ứng với hàng ラ (R) vì tiếng Nhật không có âm L.', '{"type": "WORD", "rule": "INIT_L", "kanji": "留", "hanViet": "Lưu", "reading": "リュウ", "romaji": "ryuu", "focus": "留学"}', @onyomi_quiz_1_3, 32, 'onyomi:INIT_L:留:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 頭脳 (Đầu Não) đọc là gì?', 'ずのう', 'ずろう', 'ずのう', 'ずじょう', 'ずりょう', 2, '頭脳 = 頭 (Đầu → ず) + 脳 (Não → のう) → ずのう: đầu óc. 脳 (Não) → のう theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "脳", "hanViet": "Não", "reading": "ノウ", "romaji": "nou", "focus": "頭脳"}', @onyomi_quiz_1_3, 33, 'onyomi:INIT_N:脳:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 納税 (Nạp Thuế) đọc là gì?', 'のうぜい', 'ろうぜい', 'のうぜい', 'りょうぜい', 'じょうぜい', 2, '納税 = 納 (Nạp → のう) + 税 (Thuế → ぜい) → のうぜい: nộp thuế. 納 (Nạp) → のう theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "納", "hanViet": "Nạp", "reading": "ノウ", "romaji": "nou", "focus": "納税"}', @onyomi_quiz_1_3, 34, 'onyomi:INIT_N:納:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 濃度 (Nùng Độ) đọc là gì?', 'のうど', 'りょうど', 'のうど', 'ろうど', 'じょうど', 2, '濃度 = 濃 (Nùng → のう) + 度 (Độ → ど) → のうど: nồng độ. 濃 (Nùng) → のう theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "濃", "hanViet": "Nùng", "reading": "ノウ", "romaji": "nou", "focus": "濃度"}', @onyomi_quiz_1_3, 35, 'onyomi:INIT_N:濃:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 丁寧 (Đinh Ninh) đọc là gì?', 'ていねい', 'ていねい', 'ていれい', 'ていぜい', 'ていけい', 1, '丁寧 = 丁 (Đinh → てい) + 寧 (Ninh → ねい) → ていねい: lịch sự. 寧 (Ninh) → ねい theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "寧", "hanViet": "Ninh", "reading": "ネイ", "romaji": "nei", "focus": "丁寧"}', @onyomi_quiz_1_3, 36, 'onyomi:INIT_N:寧:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 苦悩 (Khổ Não) đọc là gì?', 'くのう', 'くじょう', 'くろう', 'くのう', 'くりょう', 3, '苦悩 = 苦 (Khổ → く) + 悩 (Não → のう) → くのう: khổ não. 悩 (Não) → のう theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "悩", "hanViet": "Não", "reading": "ノウ", "romaji": "nou", "focus": "苦悩"}', @onyomi_quiz_1_3, 37, 'onyomi:INIT_N:悩:WORD'),
    (@onyomi_rule_init_n, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 粘土 (Niêm Thổ) đọc là gì?', 'ねんど', 'けんど', 'ねんど', 'ぜんど', 'れんど', 2, '粘土 = 粘 (Niêm → ねん) + 土 (Thổ → ど) → ねんど: đất sét. 粘 (Niêm) → ねん theo quy tắc Âm đầu N → N: Âm đầu N thường giữ hàng ナ (N).', '{"type": "WORD", "rule": "INIT_N", "kanji": "粘", "hanViet": "Niêm", "reading": "ネン", "romaji": "nen", "focus": "粘土"}', @onyomi_quiz_1_3, 38, 'onyomi:INIT_N:粘:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 忍者 (Nhẫn Giả) đọc là gì?', 'にんじゃ', 'きんじゃ', 'にんじゃ', 'ぎんじゃ', 'りんじゃ', 2, '忍者 = 忍 (Nhẫn → にん) + 者 (Giả → じゃ) → にんじゃ: ninja. 忍 (Nhẫn) → にん theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "忍", "hanViet": "Nhẫn", "reading": "ニン", "romaji": "nin", "focus": "忍者"}', @onyomi_quiz_1_3, 39, 'onyomi:INIT_NH:忍:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 譲歩 (Nhượng Bộ) đọc là gì?', 'じょうほ', 'きょうほ', 'りょうほ', 'じょうほ', 'ぎょうほ', 3, '譲歩 = 譲 (Nhượng → じょう) + 歩 (Bộ → ほ) → じょうほ: nhượng bộ. 譲 (Nhượng) → じょう theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "譲", "hanViet": "Nhượng", "reading": "ジョウ", "romaji": "jou", "focus": "譲歩"}', @onyomi_quiz_1_3, 40, 'onyomi:INIT_NH:譲:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 柔道 (Nhu Đạo) đọc là gì?', 'じゅうどう', 'りゅうどう', 'ぎゅうどう', 'じゅうどう', 'きゅうどう', 3, '柔道 = 柔 (Nhu → じゅう) + 道 (Đạo → どう) → じゅうどう: judo. 柔 (Nhu) → じゅう theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "柔", "hanViet": "Nhu", "reading": "ジュウ", "romaji": "juu", "focus": "柔道"}', @onyomi_quiz_1_3, 41, 'onyomi:INIT_NH:柔:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 牛乳 (Ngưu Nhũ) đọc là gì?', 'ぎゅうにゅう', 'ぎゅうぎゅう', 'ぎゅうきゅう', 'ぎゅうりゅう', 'ぎゅうにゅう', 4, '牛乳 = 牛 (Ngưu → ぎゅう) + 乳 (Nhũ → にゅう) → ぎゅうにゅう: sữa bò. 乳 (Nhũ) → にゅう theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "乳", "hanViet": "Nhũ", "reading": "ニュウ", "romaji": "nyuu", "focus": "牛乳"}', @onyomi_quiz_1_3, 42, 'onyomi:INIT_NH:乳:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 児童 (Nhi Đồng) đọc là gì?', 'じどう', 'じどう', 'きどう', 'りどう', 'ぎどう', 1, '児童 = 児 (Nhi → じ) + 童 (Đồng → どう) → じどう: nhi đồng. 児 (Nhi) → じ theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "児", "hanViet": "Nhi", "reading": "ジ", "romaji": "ji", "focus": "児童"}', @onyomi_quiz_1_3, 43, 'onyomi:INIT_NH:児:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 燃料 (Nhiên Liệu) đọc là gì?', 'ねんりょう', 'けんりょう', 'げんりょう', 'ねんりょう', 'れんりょう', 3, '燃料 = 燃 (Nhiên → ねん) + 料 (Liệu → りょう) → ねんりょう: nhiên liệu. 燃 (Nhiên) → ねん theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "燃", "hanViet": "Nhiên", "reading": "ネン", "romaji": "nen", "focus": "燃料"}', @onyomi_quiz_1_3, 44, 'onyomi:INIT_NH:燃:WORD'),
    (@onyomi_rule_init_nh, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 弱点 (Nhược Điểm) đọc là gì?', 'じゃくてん', 'きゃくてん', 'りゃくてん', 'じゃくてん', 'ぎゃくてん', 3, '弱点 = 弱 (Nhược → じゃく) + 点 (Điểm → てん) → じゃくてん: điểm yếu. 弱 (Nhược) → じゃく theo quy tắc Âm đầu NH → N/J: Âm đầu NH thường tương ứng với ニ/ニュ (N) hoặc ジ (J).', '{"type": "WORD", "rule": "INIT_NH", "kanji": "弱", "hanViet": "Nhược", "reading": "ジャク", "romaji": "jaku", "focus": "弱点"}', @onyomi_quiz_1_3, 45, 'onyomi:INIT_NH:弱:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 武士 (Vũ Sĩ) đọc là gì?', 'ぶし', 'ぶに', 'ぶし', 'ぶり', 'ぶじ', 2, '武士 = 武 (Vũ → ぶ) + 士 (Sĩ → し) → ぶし: võ sĩ. 士 (Sĩ) → し theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "士", "hanViet": "Sĩ", "reading": "シ", "romaji": "shi", "focus": "武士"}', @onyomi_quiz_1_4, 24, 'onyomi:INIT_S:士:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 政策 (Chính Sách) đọc là gì?', 'せいさく', 'せいなく', 'せいさく', 'せいりゃく', 'せいらく', 2, '政策 = 政 (Chính → せい) + 策 (Sách → さく) → せいさく: chính sách. 策 (Sách) → さく theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "策", "hanViet": "Sách", "reading": "サク", "romaji": "saku", "focus": "政策"}', @onyomi_quiz_1_4, 25, 'onyomi:INIT_S:策:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 差別 (Sai Biệt) đọc là gì?', 'さべつ', 'らべつ', 'さべつ', 'なべつ', 'りゃべつ', 2, '差別 = 差 (Sai → さ) + 別 (Biệt → べつ) → さべつ: phân biệt đối xử. 差 (Sai) → さ theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "差", "hanViet": "Sai", "reading": "サ", "romaji": "sa", "focus": "差別"}', @onyomi_quiz_1_4, 26, 'onyomi:INIT_S:差:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 創造 (Sáng Tạo) đọc là gì?', 'そうぞう', 'りょうぞう', 'のうぞう', 'ろうぞう', 'そうぞう', 4, '創造 = 創 (Sáng → そう) + 造 (Tạo → ぞう) → そうぞう: sáng tạo. 創 (Sáng) → そう theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "創", "hanViet": "Sáng", "reading": "ソウ", "romaji": "sou", "focus": "創造"}', @onyomi_quiz_1_4, 27, 'onyomi:INIT_S:創:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 捜査 (Sưu Tra) đọc là gì?', 'そうさ', 'のうさ', 'そうさ', 'りょうさ', 'ろうさ', 2, '捜査 = 捜 (Sưu → そう) + 査 (Tra → さ) → そうさ: điều tra. 捜 (Sưu) → そう theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "捜", "hanViet": "Sưu", "reading": "ソウ", "romaji": "sou", "focus": "捜査"}', @onyomi_quiz_1_4, 28, 'onyomi:INIT_S:捜:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 数学 (Số Học) đọc là gì?', 'すうがく', 'ぬうがく', 'すうがく', 'るうがく', 'りゅうがく', 2, '数学 = 数 (Số → すう) + 学 (Học → がく) → すうがく: toán học. 数 (Số) → すう theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "数", "hanViet": "Số", "reading": "スウ", "romaji": "suu", "focus": "数学"}', @onyomi_quiz_1_4, 29, 'onyomi:INIT_S:数:WORD'),
    (@onyomi_rule_init_s, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 衰退 (Suy Thoái) đọc là gì?', 'すいたい', 'るいたい', 'すいたい', 'りゅいたい', 'ぬいたい', 2, '衰退 = 衰 (Suy → すい) + 退 (Thoái → たい) → すいたい: suy thoái. 衰 (Suy) → すい theo quy tắc Âm đầu S → S/SH: Âm đầu S thường giữ hàng サ (S hoặc SH).', '{"type": "WORD", "rule": "INIT_S", "kanji": "衰", "hanViet": "Suy", "reading": "スイ", "romaji": "sui", "focus": "衰退"}', @onyomi_quiz_1_4, 30, 'onyomi:INIT_S:衰:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 天才 (Thiên Tài) đọc là gì?', 'てんさい', 'てんりゃい', 'てんらい', 'てんない', 'てんさい', 4, '天才 = 天 (Thiên → てん) + 才 (Tài → さい) → てんさい: thiên tài. 才 (Tài) → さい theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "才", "hanViet": "Tài", "reading": "サイ", "romaji": "sai", "focus": "天才"}', @onyomi_quiz_1_4, 31, 'onyomi:INIT_T:才:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 進歩 (Tiến Bộ) đọc là gì?', 'しんぽ', 'にんぽ', 'しんぽ', 'りんぽ', 'じんぽ', 2, '進歩 = 進 (Tiến → しん) + 歩 (Bộ → ぽ) → しんぽ: tiến bộ. 進 (Tiến) → しん theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "進", "hanViet": "Tiến", "reading": "シン", "romaji": "shin", "focus": "進歩"}', @onyomi_quiz_1_4, 32, 'onyomi:INIT_T:進:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 選挙 (Tuyển Cử) đọc là gì?', 'せんきょ', 'ねんきょ', 'れんきょ', 'ぜんきょ', 'せんきょ', 4, '選挙 = 選 (Tuyển → せん) + 挙 (Cử → きょ) → せんきょ: bầu cử. 選 (Tuyển) → せん theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "選", "hanViet": "Tuyển", "reading": "セン", "romaji": "sen", "focus": "選挙"}', @onyomi_quiz_1_4, 33, 'onyomi:INIT_T:選:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 速度 (Tốc Độ) đọc là gì?', 'そくど', 'りょくど', 'ろくど', 'そくど', 'のくど', 3, '速度 = 速 (Tốc → そく) + 度 (Độ → ど) → そくど: tốc độ. 速 (Tốc) → そく theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "速", "hanViet": "Tốc", "reading": "ソク", "romaji": "soku", "focus": "速度"}', @onyomi_quiz_1_4, 34, 'onyomi:INIT_T:速:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 組織 (Tổ Chức) đọc là gì?', 'そしき', 'ろしき', 'のしき', 'そしき', 'りょしき', 3, '組織 = 組 (Tổ → そ) + 織 (Chức → しき) → そしき: tổ chức. 組 (Tổ) → そ theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "組", "hanViet": "Tổ", "reading": "ソ", "romaji": "so", "focus": "組織"}', @onyomi_quiz_1_4, 35, 'onyomi:INIT_T:組:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 四月 (Tứ Nguyệt) đọc là gì?', 'しがつ', 'にがつ', 'りがつ', 'じがつ', 'しがつ', 4, '四月 = 四 (Tứ → し) + 月 (Nguyệt → がつ) → しがつ: tháng tư. 四 (Tứ) → し theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "四", "hanViet": "Tứ", "reading": "シ", "romaji": "shi", "focus": "四月"}', @onyomi_quiz_1_4, 36, 'onyomi:INIT_T:四:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 理想 (Lý Tưởng) đọc là gì?', 'りそう', 'りそう', 'りのう', 'りろう', 'りりょう', 1, '理想 = 理 (Lý → り) + 想 (Tưởng → そう) → りそう: lý tưởng. 想 (Tưởng) → そう theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "想", "hanViet": "Tưởng", "reading": "ソウ", "romaji": "sou", "focus": "理想"}', @onyomi_quiz_1_4, 37, 'onyomi:INIT_T:想:WORD'),
    (@onyomi_rule_init_t, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 要素 (Yếu Tố) đọc là gì?', 'ようそ', 'ようの', 'ようりょ', 'ようろ', 'ようそ', 4, '要素 = 要 (Yếu → よう) + 素 (Tố → そ) → ようそ: yếu tố. 素 (Tố) → そ theo quy tắc Âm đầu T → S: Âm đầu T (không phải TH, TR) thường tương ứng với hàng サ (S/SH), không phải タ.', '{"type": "WORD", "rule": "INIT_T", "kanji": "素", "hanViet": "Tố", "reading": "ソ", "romaji": "so", "focus": "要素"}', @onyomi_quiz_1_4, 38, 'onyomi:INIT_T:素:WORD'),
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 接触 (Tiếp Xúc) đọc là gì?', 'せっしょく', 'せっちょく', 'せっぴょく', 'せっきょく', 'せっしょく', 4, '接触 = 接 (Tiếp → せっ) + 触 (Xúc → しょく) → せっしょく: tiếp xúc. 触 (Xúc) → しょく theo quy tắc Âm đầu X → SH: Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', '{"type": "WORD", "rule": "INIT_X", "kanji": "触", "hanViet": "Xúc", "reading": "ショク", "romaji": "shoku", "focus": "接触"}', @onyomi_quiz_1_4, 39, 'onyomi:INIT_X:触:WORD');
INSERT INTO assessment_question (conversion_rule_id, knowledge_tag_id, skill, jlpt_level, difficulty,
    question_type, question_text, answer_text, option_1, option_2, option_3, option_4, correct_option,
    explanation, context_json, quiz_id, order_index, title) VALUES
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 衝突 (Xung Đột) đọc là gì?', 'しょうとつ', 'りょうとつ', 'にょうとつ', 'じょうとつ', 'しょうとつ', 4, '衝突 = 衝 (Xung → しょう) + 突 (Đột → とつ) → しょうとつ: xung đột. 衝 (Xung) → しょう theo quy tắc Âm đầu X → SH: Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', '{"type": "WORD", "rule": "INIT_X", "kanji": "衝", "hanViet": "Xung", "reading": "ショウ", "romaji": "shou", "focus": "衝突"}', @onyomi_quiz_1_4, 40, 'onyomi:INIT_X:衝:WORD'),
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 悪臭 (Ác Xú) đọc là gì?', 'あくしゅう', 'あくじゅう', 'あくりゅう', 'あくにゅう', 'あくしゅう', 4, '悪臭 = 悪 (Ác → あく) + 臭 (Xú → しゅう) → あくしゅう: mùi hôi. 臭 (Xú) → しゅう theo quy tắc Âm đầu X → SH: Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', '{"type": "WORD", "rule": "INIT_X", "kanji": "臭", "hanViet": "Xú", "reading": "シュウ", "romaji": "shuu", "focus": "悪臭"}', @onyomi_quiz_1_4, 41, 'onyomi:INIT_X:臭:WORD'),
    (@onyomi_rule_init_x, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 取捨 (Thủ Xả) đọc là gì?', 'しゅしゃ', 'しゅしゃ', 'しゅりゃ', 'しゅじゃ', 'しゅにゃ', 1, '取捨 = 取 (Thủ → しゅ) + 捨 (Xả → しゃ) → しゅしゃ: chọn lọc. 捨 (Xả) → しゃ theo quy tắc Âm đầu X → SH: Âm đầu X thường tương ứng với シャ/シュ/ショ (SH).', '{"type": "WORD", "rule": "INIT_X", "kanji": "捨", "hanViet": "Xả", "reading": "シャ", "romaji": "sha", "focus": "取捨"}', @onyomi_quiz_1_4, 42, 'onyomi:INIT_X:捨:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 他人 (Tha Nhân) đọc là gì?', 'たにん', 'らにん', 'たにん', 'なにん', 'りゃにん', 2, '他人 = 他 (Tha → た) + 人 (Nhân → にん) → たにん: người khác. 他 (Tha) → た theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "他", "hanViet": "Tha", "reading": "タ", "romaji": "ta", "focus": "他人"}', @onyomi_quiz_1_5, 17, 'onyomi:INIT_TH:他:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 太陽 (Thái Dương) đọc là gì?', 'たいよう', 'らいよう', 'ないよう', 'りゃいよう', 'たいよう', 4, '太陽 = 太 (Thái → たい) + 陽 (Dương → よう) → たいよう: mặt trời. 太 (Thái) → たい theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "太", "hanViet": "Thái", "reading": "タイ", "romaji": "tai", "focus": "太陽"}', @onyomi_quiz_1_5, 18, 'onyomi:INIT_TH:太:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 探検 (Thám Kiểm) đọc là gì?', 'たんけん', 'りゃんけん', 'らんけん', 'なんけん', 'たんけん', 4, '探検 = 探 (Thám → たん) + 検 (Kiểm → けん) → たんけん: thám hiểm. 探 (Thám) → たん theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "探", "hanViet": "Thám", "reading": "タン", "romaji": "tan", "focus": "探検"}', @onyomi_quiz_1_5, 19, 'onyomi:INIT_TH:探:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 試験 (Thí Nghiệm) đọc là gì?', 'しけん', 'りけん', 'しけん', 'にけん', 'じけん', 2, '試験 = 試 (Thí → し) + 験 (Nghiệm → けん) → しけん: kỳ thi. 試 (Thí) → し theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "試", "hanViet": "Thí", "reading": "シ", "romaji": "shi", "focus": "試験"}', @onyomi_quiz_1_5, 20, 'onyomi:INIT_TH:試:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 市民 (Thị Dân) đọc là gì?', 'しみん', 'じみん', 'りみん', 'にみん', 'しみん', 4, '市民 = 市 (Thị → し) + 民 (Dân → みん) → しみん: thị dân. 市 (Thị) → し theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "市", "hanViet": "Thị", "reading": "シ", "romaji": "shi", "focus": "市民"}', @onyomi_quiz_1_5, 21, 'onyomi:INIT_TH:市:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 少年 (Thiếu Niên) đọc là gì?', 'しょうねん', 'にょうねん', 'しょうねん', 'じょうねん', 'りょうねん', 2, '少年 = 少 (Thiếu → しょう) + 年 (Niên → ねん) → しょうねん: thiếu niên. 少 (Thiếu) → しょう theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "少", "hanViet": "Thiếu", "reading": "ショウ", "romaji": "shou", "focus": "少年"}', @onyomi_quiz_1_5, 22, 'onyomi:INIT_TH:少:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 商品 (Thương Phẩm) đọc là gì?', 'しょうひん', 'しょうひん', 'にょうひん', 'じょうひん', 'りょうひん', 1, '商品 = 商 (Thương → しょう) + 品 (Phẩm → ひん) → しょうひん: hàng hoá. 商 (Thương) → しょう theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "商", "hanViet": "Thương", "reading": "ショウ", "romaji": "shou", "focus": "商品"}', @onyomi_quiz_1_5, 23, 'onyomi:INIT_TH:商:WORD'),
    (@onyomi_rule_init_th, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 食事 (Thực Sự) đọc là gì?', 'しょくじ', 'しょくじ', 'りょくじ', 'にょくじ', 'じょくじ', 1, '食事 = 食 (Thực → しょく) + 事 (Sự → じ) → しょくじ: bữa ăn. 食 (Thực) → しょく theo quy tắc Âm đầu TH → S/T: Âm đầu TH thường tương ứng với hàng サ (S/SH) hoặc タ (T/TS).', '{"type": "WORD", "rule": "INIT_TH", "kanji": "食", "hanViet": "Thực", "reading": "ショク", "romaji": "shoku", "focus": "食事"}', @onyomi_quiz_1_5, 24, 'onyomi:INIT_TH:食:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 位置 (Vị Trí) đọc là gì?', 'いち', 'いに', 'いち', 'いし', 'いり', 2, '位置 = 位 (Vị → い) + 置 (Trí → ち) → いち: vị trí. 置 (Trí) → ち theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "置", "hanViet": "Trí", "reading": "チ", "romaji": "chi", "focus": "位置"}', @onyomi_quiz_1_5, 25, 'onyomi:INIT_TR:置:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 電池 (Điện Trì) đọc là gì?', 'でんち', 'でんち', 'でんり', 'でんに', 'でんし', 1, '電池 = 電 (Điện → でん) + 池 (Trì → ち) → でんち: pin. 池 (Trì) → ち theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "池", "hanViet": "Trì", "reading": "チ", "romaji": "chi", "focus": "電池"}', @onyomi_quiz_1_5, 26, 'onyomi:INIT_TR:池:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 遅刻 (Trì Khắc) đọc là gì?', 'ちこく', 'しこく', 'ちこく', 'りこく', 'にこく', 2, '遅刻 = 遅 (Trì → ち) + 刻 (Khắc → こく) → ちこく: đến muộn. 遅 (Trì) → ち theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "遅", "hanViet": "Trì", "reading": "チ", "romaji": "chi", "focus": "遅刻"}', @onyomi_quiz_1_5, 27, 'onyomi:INIT_TR:遅:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 発展 (Phát Triển) đọc là gì?', 'はってん', 'はっけん', 'はっせん', 'はってん', 'はっぺん', 3, '発展 = 発 (Phát → はっ) + 展 (Triển → てん) → はってん: phát triển. 展 (Triển) → てん theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "展", "hanViet": "Triển", "reading": "テン", "romaji": "ten", "focus": "発展"}', @onyomi_quiz_1_5, 28, 'onyomi:INIT_TR:展:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 建築 (Kiến Trúc) đọc là gì?', 'けんちく', 'けんちく', 'けんしく', 'けんにく', 'けんりく', 1, '建築 = 建 (Kiến → けん) + 築 (Trúc → ちく) → けんちく: kiến trúc. 築 (Trúc) → ちく theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "築", "hanViet": "Trúc", "reading": "チク", "romaji": "chiku", "focus": "建築"}', @onyomi_quiz_1_5, 29, 'onyomi:INIT_TR:築:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 特徴 (Đặc Trưng) đọc là gì?', 'とくちょう', 'とくりょう', 'とくちょう', 'とくしょう', 'とくにょう', 2, '特徴 = 特 (Đặc → とく) + 徴 (Trưng → ちょう) → とくちょう: đặc trưng. 徴 (Trưng) → ちょう theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "徴", "hanViet": "Trưng", "reading": "チョウ", "romaji": "chou", "focus": "特徴"}', @onyomi_quiz_1_5, 30, 'onyomi:INIT_TR:徴:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 駐車 (Trú Xa) đọc là gì?', 'ちゅうしゃ', 'ちゅうしゃ', 'しゅうしゃ', 'りゅうしゃ', 'にゅうしゃ', 1, '駐車 = 駐 (Trú → ちゅう) + 車 (Xa → しゃ) → ちゅうしゃ: đỗ xe. 駐 (Trú) → ちゅう theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "駐", "hanViet": "Trú", "reading": "チュウ", "romaji": "chuu", "focus": "駐車"}', @onyomi_quiz_1_5, 31, 'onyomi:INIT_TR:駐:WORD'),
    (@onyomi_rule_init_tr, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 過程 (Quá Trình) đọc là gì?', 'かてい', 'かてい', 'かせい', 'かれい', 'かねい', 1, '過程 = 過 (Quá → か) + 程 (Trình → てい) → かてい: quá trình. 程 (Trình) → てい theo quy tắc Âm đầu TR → CH/T: Âm đầu TR thường tương ứng với チ/チャ/チュ/チョ (CH), đôi khi タ (T/TS).', '{"type": "WORD", "rule": "INIT_TR", "kanji": "程", "hanViet": "Trình", "reading": "テイ", "romaji": "tei", "focus": "過程"}', @onyomi_quiz_1_5, 32, 'onyomi:INIT_TR:程:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 可能 (Khả Năng) đọc là gì?', 'かのう', 'かのう', 'がのう', 'さのう', 'ぎゃのう', 1, '可能 = 可 (Khả → か) + 能 (Năng → のう) → かのう: khả năng. 可 (Khả) → か theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "可", "hanViet": "Khả", "reading": "カ", "romaji": "ka", "focus": "可能"}', @onyomi_quiz_1_6, 17, 'onyomi:INIT_KH:可:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 楽器 (Nhạc Khí) đọc là gì?', 'がっき', 'がっし', 'がっき', 'がっぴ', 'がっち', 2, '楽器 = 楽 (Nhạc → がっ) + 器 (Khí → き) → がっき: nhạc cụ. 器 (Khí) → き theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "器", "hanViet": "Khí", "reading": "キ", "romaji": "ki", "focus": "楽器"}', @onyomi_quiz_1_6, 18, 'onyomi:INIT_KH:器:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 時刻 (Thời Khắc) đọc là gì?', 'じこく', 'じそく', 'じごく', 'じぎょく', 'じこく', 4, '時刻 = 時 (Thời → じ) + 刻 (Khắc → こく) → じこく: thời khắc. 刻 (Khắc) → こく theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "刻", "hanViet": "Khắc", "reading": "コク", "romaji": "koku", "focus": "時刻"}', @onyomi_quiz_1_6, 19, 'onyomi:INIT_KH:刻:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 課長 (Khóa Trưởng) đọc là gì?', 'かちょう', 'さちょう', 'ぎゃちょう', 'がちょう', 'かちょう', 4, '課長 = 課 (Khóa → か) + 長 (Trưởng → ちょう) → かちょう: trưởng ban. 課 (Khóa) → か theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "課", "hanViet": "Khóa", "reading": "カ", "romaji": "ka", "focus": "課長"}', @onyomi_quiz_1_6, 20, 'onyomi:INIT_KH:課:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 快適 (Khoái Thích) đọc là gì?', 'かいてき', 'がいてき', 'さいてき', 'かいてき', 'ぎゃいてき', 3, '快適 = 快 (Khoái → かい) + 適 (Thích → てき) → かいてき: dễ chịu. 快 (Khoái) → かい theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "快", "hanViet": "Khoái", "reading": "カイ", "romaji": "kai", "focus": "快適"}', @onyomi_quiz_1_6, 21, 'onyomi:INIT_KH:快:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 作曲 (Tác Khúc) đọc là gì?', 'さっきょく', 'さっとく', 'さっちょく', 'さっしょく', 'さっきょく', 4, '作曲 = 作 (Tác → さっ) + 曲 (Khúc → きょく) → さっきょく: sáng tác nhạc. 曲 (Khúc) → きょく theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "曲", "hanViet": "Khúc", "reading": "キョク", "romaji": "kyoku", "focus": "作曲"}', @onyomi_quiz_1_6, 22, 'onyomi:INIT_KH:曲:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 地区 (Địa Khu) đọc là gì?', 'ちく', 'ちす', 'ちぐ', 'ちぎゅ', 'ちく', 4, '地区 = 地 (Địa → ち) + 区 (Khu → く) → ちく: khu vực. 区 (Khu) → く theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "区", "hanViet": "Khu", "reading": "ク", "romaji": "ku", "focus": "地区"}', @onyomi_quiz_1_6, 23, 'onyomi:INIT_KH:区:WORD'),
    (@onyomi_rule_init_kh, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 去年 (Khứ Niên) đọc là gì?', 'きょねん', 'きょねん', 'ちょねん', 'ぎょねん', 'しょねん', 1, '去年 = 去 (Khứ → きょ) + 年 (Niên → ねん) → きょねん: năm ngoái. 去 (Khứ) → きょ theo quy tắc Âm đầu KH → K: Âm đầu KH thường tương ứng với hàng カ (K).', '{"type": "WORD", "rule": "INIT_KH", "kanji": "去", "hanViet": "Khứ", "reading": "キョ", "romaji": "kyo", "focus": "去年"}', @onyomi_quiz_1_6, 24, 'onyomi:INIT_KH:去:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 物価 (Vật Giá) đọc là gì?', 'ぶっか', 'ぶった', 'ぶっさ', 'ぶっか', 'ぶっしゃ', 3, '物価 = 物 (Vật → ぶっ) + 価 (Giá → か) → ぶっか: vật giá. 価 (Giá) → か theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "価", "hanViet": "Giá", "reading": "カ", "romaji": "ka", "focus": "物価"}', @onyomi_quiz_1_6, 25, 'onyomi:INIT_GI:価:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 仮定 (Giả Định) đọc là gì?', 'かてい', 'かてい', 'さてい', 'しゃてい', 'たてい', 1, '仮定 = 仮 (Giả → か) + 定 (Định → てい) → かてい: giả định. 仮 (Giả) → か theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "仮", "hanViet": "Giả", "reading": "カ", "romaji": "ka", "focus": "仮定"}', @onyomi_quiz_1_6, 26, 'onyomi:INIT_GI:仮:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 階段 (Giai Đoạn) đọc là gì?', 'かいだん', 'たいだん', 'かいだん', 'しゃいだん', 'さいだん', 2, '階段 = 階 (Giai → かい) + 段 (Đoạn → だん) → かいだん: cầu thang. 階 (Giai) → かい theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "階", "hanViet": "Giai", "reading": "カイ", "romaji": "kai", "focus": "階段"}', @onyomi_quiz_1_6, 27, 'onyomi:INIT_GI:階:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 監督 (Giám Đốc) đọc là gì?', 'かんとく', 'かんとく', 'たんとく', 'しゃんとく', 'さんとく', 1, '監督 = 監 (Giám → かん) + 督 (Đốc → とく) → かんとく: đạo diễn. 監 (Giám) → かん theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "監", "hanViet": "Giám", "reading": "カン", "romaji": "kan", "focus": "監督"}', @onyomi_quiz_1_6, 28, 'onyomi:INIT_GI:監:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 簡単 (Giản Đơn) đọc là gì?', 'かんたん', 'さんたん', 'かんたん', 'たんたん', 'しゃんたん', 2, '簡単 = 簡 (Giản → かん) + 単 (Đơn → たん) → かんたん: đơn giản. 簡 (Giản) → かん theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "簡", "hanViet": "Giản", "reading": "カン", "romaji": "kan", "focus": "簡単"}', @onyomi_quiz_1_6, 29, 'onyomi:INIT_GI:簡:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 三角 (Tam Giác) đọc là gì?', 'さんかく', 'さんさく', 'さんかく', 'さんたく', 'さんしゃく', 2, '三角 = 三 (Tam → さん) + 角 (Giác → かく) → さんかく: tam giác. 角 (Giác) → かく theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "角", "hanViet": "Giác", "reading": "カク", "romaji": "kaku", "focus": "三角"}', @onyomi_quiz_1_6, 30, 'onyomi:INIT_GI:角:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 感覚 (Cảm Giác) đọc là gì?', 'かんかく', 'かんしゃく', 'かんさく', 'かんたく', 'かんかく', 4, '感覚 = 感 (Cảm → かん) + 覚 (Giác → かく) → かんかく: cảm giác. 覚 (Giác) → かく theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "覚", "hanViet": "Giác", "reading": "カク", "romaji": "kaku", "focus": "感覚"}', @onyomi_quiz_1_6, 31, 'onyomi:INIT_GI:覚:WORD'),
    (@onyomi_rule_init_gi, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 警戒 (Cảnh Giới) đọc là gì?', 'けいかい', 'けいかい', 'けいたい', 'けいしゃい', 'けいさい', 1, '警戒 = 警 (Cảnh → けい) + 戒 (Giới → かい) → けいかい: cảnh giác. 戒 (Giới) → かい theo quy tắc Âm đầu GI → K/G: Âm đầu GI thường tương ứng với hàng カ (K), đôi khi ガ (G).', '{"type": "WORD", "rule": "INIT_GI", "kanji": "戒", "hanViet": "Giới", "reading": "カイ", "romaji": "kai", "focus": "警戒"}', @onyomi_quiz_1_6, 32, 'onyomi:INIT_GI:戒:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 博物館 (Bác Vật Quán) đọc là gì?', 'はくぶつかん', 'はくぶつかん', 'かくぶつかん', 'がくぶつかん', 'きゃくぶつかん', 1, '博物館 = 博 (Bác → はく) + 物 (Vật → ぶつ) + 館 (Quán → かん) → はくぶつかん: viện bảo tàng. 博 (Bác) → はく theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "博", "hanViet": "Bác", "reading": "ハク", "romaji": "haku", "focus": "博物館"}', @onyomi_quiz_1_7, 15, 'onyomi:INIT_B:博:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 黒板 (Hắc Bản) đọc là gì?', 'こくばん', 'こくかん', 'こくきゃん', 'こくばん', 'こくがん', 3, '黒板 = 黒 (Hắc → こく) + 板 (Bản → ばん) → こくばん: bảng đen. 板 (Bản) → ばん theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "板", "hanViet": "Bản", "reading": "バン", "romaji": "ban", "focus": "黒板"}', @onyomi_quiz_1_7, 16, 'onyomi:INIT_B:板:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 保険 (Bảo Hiểm) đọc là gì?', 'ほけん', 'ほけん', 'きょけん', 'ごけん', 'こけん', 1, '保険 = 保 (Bảo → ほ) + 険 (Hiểm → けん) → ほけん: bảo hiểm. 保 (Bảo) → ほ theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "保", "hanViet": "Bảo", "reading": "ホ", "romaji": "ho", "focus": "保険"}', @onyomi_quiz_1_7, 17, 'onyomi:INIT_B:保:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 情報 (Tình Báo) đọc là gì?', 'じょうほう', 'じょうきょう', 'じょうほう', 'じょうこう', 'じょうごう', 2, '情報 = 情 (Tình → じょう) + 報 (Báo → ほう) → じょうほう: thông tin. 報 (Báo) → ほう theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "報", "hanViet": "Báo", "reading": "ホウ", "romaji": "hou", "focus": "情報"}', @onyomi_quiz_1_7, 18, 'onyomi:INIT_B:報:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 設備 (Thiết Bị) đọc là gì?', 'せつび', 'せつき', 'せつぎ', 'せつし', 'せつび', 4, '設備 = 設 (Thiết → せつ) + 備 (Bị → び) → せつび: thiết bị. 備 (Bị) → び theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "備", "hanViet": "Bị", "reading": "ビ", "romaji": "bi", "focus": "設備"}', @onyomi_quiz_1_7, 19, 'onyomi:INIT_B:備:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 秘密 (Bí Mật) đọc là gì?', 'ひみつ', 'きみつ', 'しみつ', 'ひみつ', 'ぎみつ', 3, '秘密 = 秘 (Bí → ひ) + 密 (Mật → みつ) → ひみつ: bí mật. 秘 (Bí) → ひ theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "秘", "hanViet": "Bí", "reading": "ヒ", "romaji": "hi", "focus": "秘密"}', @onyomi_quiz_1_7, 20, 'onyomi:INIT_B:秘:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 表現 (Biểu Hiện) đọc là gì?', 'ひょうげん', 'しょうげん', 'ひょうげん', 'ぎょうげん', 'きょうげん', 2, '表現 = 表 (Biểu → ひょう) + 現 (Hiện → げん) → ひょうげん: biểu hiện. 表 (Biểu) → ひょう theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "表", "hanViet": "Biểu", "reading": "ヒョウ", "romaji": "hyou", "focus": "表現"}', @onyomi_quiz_1_7, 21, 'onyomi:INIT_B:表:WORD'),
    (@onyomi_rule_init_b, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 部長 (Bộ Trưởng) đọc là gì?', 'ぶちょう', 'ぶちょう', 'ぐちょう', 'きゅちょう', 'くちょう', 1, '部長 = 部 (Bộ → ぶ) + 長 (Trưởng → ちょう) → ぶちょう: trưởng phòng. 部 (Bộ) → ぶ theo quy tắc Âm đầu B → H/B: Âm đầu B thường tương ứng với hàng ハ (H), đôi khi giữ バ (B).', '{"type": "WORD", "rule": "INIT_B", "kanji": "部", "hanViet": "Bộ", "reading": "ブ", "romaji": "bu", "focus": "部長"}', @onyomi_quiz_1_7, 22, 'onyomi:INIT_B:部:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 洋服 (Dương Phục) đọc là gì?', 'ようふく', 'ようくく', 'ようふく', 'ようびゅく', 'ようぶく', 2, '洋服 = 洋 (Dương → よう) + 服 (Phục → ふく) → ようふく: quần áo kiểu Âu. 服 (Phục) → ふく theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "服", "hanViet": "Phục", "reading": "フク", "romaji": "fuku", "focus": "洋服"}', @onyomi_quiz_1_7, 23, 'onyomi:INIT_PH:服:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 回復 (Hồi Phục) đọc là gì?', 'かいふく', 'かいふく', 'かいぶく', 'かいくく', 'かいびゅく', 1, '回復 = 回 (Hồi → かい) + 復 (Phục → ふく) → かいふく: hồi phục. 復 (Phục) → ふく theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "復", "hanViet": "Phục", "reading": "フク", "romaji": "fuku", "focus": "回復"}', @onyomi_quiz_1_7, 24, 'onyomi:INIT_PH:復:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 福祉 (Phúc Chỉ) đọc là gì?', 'ふくし', 'ぶくし', 'ふくし', 'くくし', 'びゅくし', 2, '福祉 = 福 (Phúc → ふく) + 祉 (Chỉ → し) → ふくし: phúc lợi. 福 (Phúc) → ふく theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "福", "hanViet": "Phúc", "reading": "フク", "romaji": "fuku", "focus": "福祉"}', @onyomi_quiz_1_7, 25, 'onyomi:INIT_PH:福:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 豊富 (Phong Phú) đọc là gì?', 'ほうふ', 'ほうぶ', 'ほうく', 'ほうびゅ', 'ほうふ', 4, '豊富 = 豊 (Phong → ほう) + 富 (Phú → ふ) → ほうふ: phong phú. 富 (Phú) → ふ theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "富", "hanViet": "Phú", "reading": "フ", "romaji": "fu", "focus": "豊富"}', @onyomi_quiz_1_7, 26, 'onyomi:INIT_PH:富:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 台風 (Đài Phong) đọc là gì?', 'たいふう', 'たいびゅう', 'たいふう', 'たいぶう', 'たいくう', 2, '台風 = 台 (Đài → たい) + 風 (Phong → ふう) → たいふう: bão. 風 (Phong) → ふう theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "風", "hanViet": "Phong", "reading": "フウ", "romaji": "fuu", "focus": "台風"}', @onyomi_quiz_1_7, 27, 'onyomi:INIT_PH:風:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 普通 (Phổ Thông) đọc là gì?', 'ふつう', 'くつう', 'ぶつう', 'びゅつう', 'ふつう', 4, '普通 = 普 (Phổ → ふ) + 通 (Thông → つう) → ふつう: bình thường. 普 (Phổ) → ふ theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "普", "hanViet": "Phổ", "reading": "フ", "romaji": "fu", "focus": "普通"}', @onyomi_quiz_1_7, 28, 'onyomi:INIT_PH:普:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 判断 (Phán Đoán) đọc là gì?', 'はんだん', 'きゃんだん', 'びゃんだん', 'かんだん', 'はんだん', 4, '判断 = 判 (Phán → はん) + 断 (Đoán → だん) → はんだん: phán đoán. 判 (Phán) → はん theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "判", "hanViet": "Phán", "reading": "ハン", "romaji": "han", "focus": "判断"}', @onyomi_quiz_1_7, 29, 'onyomi:INIT_PH:判:WORD'),
    (@onyomi_rule_init_ph, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 破産 (Phá Sản) đọc là gì?', 'はさん', 'ばさん', 'びゃさん', 'はさん', 'かさん', 3, '破産 = 破 (Phá → は) + 産 (Sản → さん) → はさん: phá sản. 破 (Phá) → は theo quy tắc Âm đầu PH → H: Âm đầu PH thường tương ứng với hàng ハ (H, kể cả フ).', '{"type": "WORD", "rule": "INIT_PH", "kanji": "破", "hanViet": "Phá", "reading": "ハ", "romaji": "ha", "focus": "破産"}', @onyomi_quiz_1_7, 30, 'onyomi:INIT_PH:破:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 満足 (Mãn Túc) đọc là gì?', 'まんぞく', 'あんぞく', 'まんぞく', 'ひゃんぞく', 'はんぞく', 2, '満足 = 満 (Mãn → まん) + 足 (Túc → ぞく) → まんぞく: hài lòng. 満 (Mãn) → まん theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "満", "hanViet": "Mãn", "reading": "マン", "romaji": "man", "focus": "満足"}', @onyomi_quiz_1_8, 17, 'onyomi:INIT_M:満:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 我慢 (Ngã Mạn) đọc là gì?', 'がまん', 'があん', 'がまん', 'がはん', 'がひゃん', 2, '我慢 = 我 (Ngã → が) + 慢 (Mạn → まん) → がまん: chịu đựng. 慢 (Mạn) → まん theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "慢", "hanViet": "Mạn", "reading": "マン", "romaji": "man", "focus": "我慢"}', @onyomi_quiz_1_8, 18, 'onyomi:INIT_M:慢:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 密度 (Mật Độ) đọc là gì?', 'みつど', 'いつど', 'ひつど', 'きつど', 'みつど', 4, '密度 = 密 (Mật → みつ) + 度 (Độ → ど) → みつど: mật độ. 密 (Mật) → みつ theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "密", "hanViet": "Mật", "reading": "ミツ", "romaji": "mitsu", "focus": "密度"}', @onyomi_quiz_1_8, 19, 'onyomi:INIT_M:密:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 睡眠 (Thụy Miên) đọc là gì?', 'すいみん', 'すいひん', 'すいきん', 'すいいん', 'すいみん', 4, '睡眠 = 睡 (Thụy → すい) + 眠 (Miên → みん) → すいみん: giấc ngủ. 眠 (Miên) → みん theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "眠", "hanViet": "Miên", "reading": "ミン", "romaji": "min", "focus": "睡眠"}', @onyomi_quiz_1_8, 20, 'onyomi:INIT_M:眠:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 免許 (Miễn Hứa) đọc là gì?', 'めんきょ', 'えんきょ', 'めんきょ', 'へんきょ', 'けんきょ', 2, '免許 = 免 (Miễn → めん) + 許 (Hứa → きょ) → めんきょ: giấy phép. 免 (Miễn) → めん theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "免", "hanViet": "Miễn", "reading": "メン", "romaji": "men", "focus": "免許"}', @onyomi_quiz_1_8, 21, 'onyomi:INIT_M:免:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 木曜日 (Mộc Diệu Nhật) đọc là gì?', 'もくようび', 'もくようび', 'ひょくようび', 'おくようび', 'ほくようび', 1, '木曜日 = 木 (Mộc → もく) + 曜 (Diệu → よう) + 日 (Nhật → び) → もくようび: thứ năm. 木 (Mộc) → もく theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "木", "hanViet": "Mộc", "reading": "モク", "romaji": "moku", "focus": "木曜日"}', @onyomi_quiz_1_8, 22, 'onyomi:INIT_M:木:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 貿易 (Mậu Dịch) đọc là gì?', 'ぼうえき', 'ぼうえき', 'ひょうえき', 'ほうえき', 'おうえき', 1, '貿易 = 貿 (Mậu → ぼう) + 易 (Dịch → えき) → ぼうえき: mậu dịch. 貿 (Mậu) → ぼう theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "貿", "hanViet": "Mậu", "reading": "ボウ", "romaji": "bou", "focus": "貿易"}', @onyomi_quiz_1_8, 23, 'onyomi:INIT_M:貿:WORD'),
    (@onyomi_rule_init_m, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 帽子 (Mạo Tử) đọc là gì?', 'ぼうし', 'ひょうし', 'ほうし', 'おうし', 'ぼうし', 4, '帽子 = 帽 (Mạo → ぼう) + 子 (Tử → し) → ぼうし: cái mũ. 帽 (Mạo) → ぼう theo quy tắc Âm đầu M → M/B: Âm đầu M thường tương ứng với hàng マ (M) hoặc バ (B).', '{"type": "WORD", "rule": "INIT_M", "kanji": "帽", "hanViet": "Mạo", "reading": "ボウ", "romaji": "bou", "focus": "帽子"}', @onyomi_quiz_1_8, 24, 'onyomi:INIT_M:帽:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 意味 (Ý Vị) đọc là gì?', 'いみ', 'いき', 'いひ', 'いぎ', 'いみ', 4, '意味 = 意 (Ý → い) + 味 (Vị → み) → いみ: ý nghĩa. 味 (Vị) → み theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "味", "hanViet": "Vị", "reading": "ミ", "romaji": "mi", "focus": "意味"}', @onyomi_quiz_1_8, 25, 'onyomi:INIT_V:味:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 事務 (Sự Vụ) đọc là gì?', 'じむ', 'じひゅ', 'じむ', 'じふ', 'じく', 2, '事務 = 事 (Sự → じ) + 務 (Vụ → む) → じむ: công việc văn phòng. 務 (Vụ) → む theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "務", "hanViet": "Vụ", "reading": "ム", "romaji": "mu", "focus": "事務"}', @onyomi_quiz_1_8, 26, 'onyomi:INIT_V:務:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 武器 (Vũ Khí) đọc là gì?', 'ぶき', 'ぶき', 'くき', 'ひゅき', 'ふき', 1, '武器 = 武 (Vũ → ぶ) + 器 (Khí → き) → ぶき: vũ khí. 武 (Vũ) → ぶ theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "武", "hanViet": "Vũ", "reading": "ブ", "romaji": "bu", "focus": "武器"}', @onyomi_quiz_1_8, 27, 'onyomi:INIT_V:武:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 雨季 (Vũ Quý) đọc là gì?', 'うき', 'くき', 'ひゅき', 'ふき', 'うき', 4, '雨季 = 雨 (Vũ → う) + 季 (Quý → き) → うき: mùa mưa. 雨 (Vũ) → う theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "雨", "hanViet": "Vũ", "reading": "ウ", "romaji": "u", "focus": "雨季"}', @onyomi_quiz_1_8, 28, 'onyomi:INIT_V:雨:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 入院 (Nhập Viện) đọc là gì?', 'にゅういん', 'にゅうきん', 'にゅうぎん', 'にゅういん', 'にゅうひん', 3, '入院 = 入 (Nhập → にゅう) + 院 (Viện → いん) → にゅういん: nhập viện. 院 (Viện) → いん theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "院", "hanViet": "Viện", "reading": "イン", "romaji": "in", "focus": "入院"}', @onyomi_quiz_1_8, 29, 'onyomi:INIT_V:院:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 遠足 (Viễn Túc) đọc là gì?', 'えんそく', 'えんそく', 'けんそく', 'げんそく', 'へんそく', 1, '遠足 = 遠 (Viễn → えん) + 足 (Túc → そく) → えんそく: dã ngoại. 遠 (Viễn) → えん theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "遠", "hanViet": "Viễn", "reading": "エン", "romaji": "en", "focus": "遠足"}', @onyomi_quiz_1_8, 30, 'onyomi:INIT_V:遠:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 希望 (Hy Vọng) đọc là gì?', 'きぼう', 'きこう', 'きぼう', 'きひょう', 'きほう', 2, '希望 = 希 (Hy → き) + 望 (Vọng → ぼう) → きぼう: hy vọng. 望 (Vọng) → ぼう theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "望", "hanViet": "Vọng", "reading": "ボウ", "romaji": "bou", "focus": "希望"}', @onyomi_quiz_1_8, 31, 'onyomi:INIT_V:望:WORD'),
    (@onyomi_rule_init_v, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 運転 (Vận Chuyển) đọc là gì?', 'うんてん', 'ふんてん', 'くんてん', 'ひゅんてん', 'うんてん', 4, '運転 = 運 (Vận → うん) + 転 (Chuyển → てん) → うんてん: lái xe. 運 (Vận) → うん theo quy tắc Âm đầu V → B/M hoặc mất phụ âm: Âm đầu V thường tương ứng với バ (B), マ (M), hoặc mất hẳn phụ âm đầu.', '{"type": "WORD", "rule": "INIT_V", "kanji": "運", "hanViet": "Vận", "reading": "ウン", "romaji": "un", "focus": "運転"}', @onyomi_quiz_1_8, 32, 'onyomi:INIT_V:運:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 単語 (Đơn Ngữ) đọc là gì?', 'たんご', 'たんご', 'やんご', 'まんご', 'みゃんご', 1, '単語 = 単 (Đơn → たん) + 語 (Ngữ → ご) → たんご: từ vựng. 単 (Đơn) → たん theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "単", "hanViet": "Đơn", "reading": "タン", "romaji": "tan", "focus": "単語"}', @onyomi_quiz_1_9, 18, 'onyomi:INIT_DD:単:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 半島 (Bán Đảo) đọc là gì?', 'はんとう', 'はんもう', 'はんみょう', 'はんとう', 'はんよう', 3, '半島 = 半 (Bán → はん) + 島 (Đảo → とう) → はんとう: bán đảo. 島 (Đảo) → とう theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "島", "hanViet": "Đảo", "reading": "トウ", "romaji": "tou", "focus": "半島"}', @onyomi_quiz_1_9, 19, 'onyomi:INIT_DD:島:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 問題 (Vấn Đề) đọc là gì?', 'もんだい', 'もんまい', 'もんやい', 'もんだい', 'もんみゃい', 3, '問題 = 問 (Vấn → もん) + 題 (Đề → だい) → もんだい: vấn đề. 題 (Đề) → だい theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "題", "hanViet": "Đề", "reading": "ダイ", "romaji": "dai", "focus": "問題"}', @onyomi_quiz_1_9, 20, 'onyomi:INIT_DD:題:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 辞典 (Từ Điển) đọc là gì?', 'じてん', 'じえん', 'じてん', 'じべん', 'じめん', 2, '辞典 = 辞 (Từ → じ) + 典 (Điển → てん) → じてん: từ điển. 典 (Điển) → てん theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "典", "hanViet": "Điển", "reading": "テン", "romaji": "ten", "focus": "辞典"}', @onyomi_quiz_1_9, 21, 'onyomi:INIT_DD:典:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 団体 (Đoàn Thể) đọc là gì?', 'だんたい', 'やんたい', 'みゃんたい', 'まんたい', 'だんたい', 4, '団体 = 団 (Đoàn → だん) + 体 (Thể → たい) → だんたい: đoàn thể. 団 (Đoàn) → だん theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "団", "hanViet": "Đoàn", "reading": "ダン", "romaji": "dan", "focus": "団体"}', @onyomi_quiz_1_9, 22, 'onyomi:INIT_DD:団:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 読書 (Độc Thư) đọc là gì?', 'どくしょ', 'もくしょ', 'よくしょ', 'みょくしょ', 'どくしょ', 4, '読書 = 読 (Độc → どく) + 書 (Thư → しょ) → どくしょ: đọc sách. 読 (Độc) → どく theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "読", "hanViet": "Độc", "reading": "ドク", "romaji": "doku", "focus": "読書"}', @onyomi_quiz_1_9, 23, 'onyomi:INIT_DD:読:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 突然 (Đột Nhiên) đọc là gì?', 'とつぜん', 'とつぜん', 'もつぜん', 'みょつぜん', 'よつぜん', 1, '突然 = 突 (Đột → とつ) + 然 (Nhiên → ぜん) → とつぜん: đột nhiên. 突 (Đột) → とつ theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "突", "hanViet": "Đột", "reading": "トツ", "romaji": "totsu", "focus": "突然"}', @onyomi_quiz_1_9, 24, 'onyomi:INIT_DD:突:WORD'),
    (@onyomi_rule_init_dd, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 砂糖 (Sa Đường) đọc là gì?', 'さとう', 'さもう', 'さよう', 'さとう', 'さみょう', 3, '砂糖 = 砂 (Sa → さ) + 糖 (Đường → とう) → さとう: đường ăn. 糖 (Đường) → とう theo quy tắc Âm đầu Đ → T/D: Âm đầu Đ thường tương ứng với hàng タ (T) hoặc ダ (D).', '{"type": "WORD", "rule": "INIT_DD", "kanji": "糖", "hanViet": "Đường", "reading": "トウ", "romaji": "tou", "focus": "砂糖"}', @onyomi_quiz_1_9, 25, 'onyomi:INIT_DD:糖:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 今夜 (Kim Dạ) đọc là gì?', 'こんや', 'こんだ', 'こんま', 'こんた', 'こんや', 4, '今夜 = 今 (Kim → こん) + 夜 (Dạ → や) → こんや: tối nay. 夜 (Dạ) → や theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "夜", "hanViet": "Dạ", "reading": "ヤ", "romaji": "ya", "focus": "今夜"}', @onyomi_quiz_1_9, 26, 'onyomi:INIT_D:夜:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 野菜 (Dã Thái) đọc là gì?', 'やさい', 'たさい', 'やさい', 'まさい', 'ださい', 2, '野菜 = 野 (Dã → や) + 菜 (Thái → さい) → やさい: rau. 野 (Dã) → や theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "野", "hanViet": "Dã", "reading": "ヤ", "romaji": "ya", "focus": "野菜"}', @onyomi_quiz_1_9, 27, 'onyomi:INIT_D:野:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 通訳 (Thông Dịch) đọc là gì?', 'つうやく', 'つうたく', 'つうやく', 'つうまく', 'つうだく', 2, '通訳 = 通 (Thông → つう) + 訳 (Dịch → やく) → つうやく: phiên dịch. 訳 (Dịch) → やく theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "訳", "hanViet": "Dịch", "reading": "ヤク", "romaji": "yaku", "focus": "通訳"}', @onyomi_quiz_1_9, 28, 'onyomi:INIT_D:訳:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 役所 (Dịch Sở) đọc là gì?', 'やくしょ', 'やくしょ', 'たくしょ', 'まくしょ', 'だくしょ', 1, '役所 = 役 (Dịch → やく) + 所 (Sở → しょ) → やくしょ: cơ quan hành chính. 役 (Dịch) → やく theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "役", "hanViet": "Dịch", "reading": "ヤク", "romaji": "yaku", "focus": "役所"}', @onyomi_quiz_1_9, 29, 'onyomi:INIT_D:役:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 内容 (Nội Dung) đọc là gì?', 'ないよう', 'ないもう', 'ないよう', 'ないとう', 'ないどう', 2, '内容 = 内 (Nội → ない) + 容 (Dung → よう) → ないよう: nội dung. 容 (Dung) → よう theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "容", "hanViet": "Dung", "reading": "ヨウ", "romaji": "you", "focus": "内容"}', @onyomi_quiz_1_9, 30, 'onyomi:INIT_D:容:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 栄養 (Vinh Dưỡng) đọc là gì?', 'えいよう', 'えいどう', 'えいよう', 'えいもう', 'えいとう', 2, '栄養 = 栄 (Vinh → えい) + 養 (Dưỡng → よう) → えいよう: dinh dưỡng. 養 (Dưỡng) → よう theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "養", "hanViet": "Dưỡng", "reading": "ヨウ", "romaji": "you", "focus": "栄養"}', @onyomi_quiz_1_9, 31, 'onyomi:INIT_D:養:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 輸入 (Du Nhập) đọc là gì?', 'ゆにゅう', 'むにゅう', 'みゅにゅう', 'ゆにゅう', 'つにゅう', 3, '輸入 = 輸 (Du → ゆ) + 入 (Nhập → にゅう) → ゆにゅう: nhập khẩu. 輸 (Du) → ゆ theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "輸", "hanViet": "Du", "reading": "ユ", "romaji": "yu", "focus": "輸入"}', @onyomi_quiz_1_9, 32, 'onyomi:INIT_D:輸:WORD'),
    (@onyomi_rule_init_d, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 預金 (Dự Kim) đọc là gì?', 'よきん', 'もきん', 'どきん', 'よきん', 'ときん', 3, '預金 = 預 (Dự → よ) + 金 (Kim → きん) → よきん: tiền gửi. 預 (Dự) → よ theo quy tắc Âm đầu D → Y: Âm đầu D (không phải Đ) thường tương ứng với hàng ヤ (Y).', '{"type": "WORD", "rule": "INIT_D", "kanji": "預", "hanViet": "Dự", "reading": "ヨ", "romaji": "yo", "focus": "預金"}', @onyomi_quiz_1_9, 33, 'onyomi:INIT_D:預:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 支店 (Chi Điếm) đọc là gì?', 'してん', 'みてん', 'ちてん', 'いてん', 'してん', 4, '支店 = 支 (Chi → し) + 店 (Điếm → てん) → してん: chi nhánh. 支 (Chi) → し theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "支", "hanViet": "Chi", "reading": "シ", "romaji": "shi", "focus": "支店"}', @onyomi_quiz_1_10, 18, 'onyomi:INIT_CH:支:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 指導 (Chỉ Đạo) đọc là gì?', 'しどう', 'ちどう', 'しどう', 'みどう', 'いどう', 2, '指導 = 指 (Chỉ → し) + 導 (Đạo → どう) → しどう: chỉ đạo. 指 (Chỉ) → し theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "指", "hanViet": "Chỉ", "reading": "シ", "romaji": "shi", "focus": "指導"}', @onyomi_quiz_1_10, 19, 'onyomi:INIT_CH:指:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 制度 (Chế Độ) đọc là gì?', 'せいど', 'ていど', 'えいど', 'せいど', 'でいど', 3, '制度 = 制 (Chế → せい) + 度 (Độ → ど) → せいど: chế độ. 制 (Chế) → せい theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "制", "hanViet": "Chế", "reading": "セイ", "romaji": "sei", "focus": "制度"}', @onyomi_quiz_1_10, 20, 'onyomi:INIT_CH:制:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 製品 (Chế Phẩm) đọc là gì?', 'せいひん', 'ていひん', 'せいひん', 'えいひん', 'でいひん', 2, '製品 = 製 (Chế → せい) + 品 (Phẩm → ひん) → せいひん: sản phẩm. 製 (Chế) → せい theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "製", "hanViet": "Chế", "reading": "セイ", "romaji": "sei", "focus": "製品"}', @onyomi_quiz_1_10, 21, 'onyomi:INIT_CH:製:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 招待 (Chiêu Đãi) đọc là gì?', 'しょうたい', 'しょうたい', 'おうたい', 'ようたい', 'ちょうたい', 1, '招待 = 招 (Chiêu → しょう) + 待 (Đãi → たい) → しょうたい: mời. 招 (Chiêu) → しょう theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "招", "hanViet": "Chiêu", "reading": "ショウ", "romaji": "shou", "focus": "招待"}', @onyomi_quiz_1_10, 22, 'onyomi:INIT_CH:招:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 地震 (Địa Chấn) đọc là gì?', 'じしん', 'じちん', 'じしん', 'じいん', 'じみん', 2, '地震 = 地 (Địa → じ) + 震 (Chấn → しん) → じしん: động đất. 震 (Chấn) → しん theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "震", "hanViet": "Chấn", "reading": "シン", "romaji": "shin", "focus": "地震"}', @onyomi_quiz_1_10, 23, 'onyomi:INIT_CH:震:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 職業 (Chức Nghiệp) đọc là gì?', 'しょくぎょう', 'よくぎょう', 'おくぎょう', 'ちょくぎょう', 'しょくぎょう', 4, '職業 = 職 (Chức → しょく) + 業 (Nghiệp → ぎょう) → しょくぎょう: nghề nghiệp. 職 (Chức) → しょく theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "職", "hanViet": "Chức", "reading": "ショク", "romaji": "shoku", "focus": "職業"}', @onyomi_quiz_1_10, 24, 'onyomi:INIT_CH:職:WORD'),
    (@onyomi_rule_init_ch, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 証明 (Chứng Minh) đọc là gì?', 'しょうめい', 'しょうめい', 'ようめい', 'ちょうめい', 'おうめい', 1, '証明 = 証 (Chứng → しょう) + 明 (Minh → めい) → しょうめい: chứng minh. 証 (Chứng) → しょう theo quy tắc Âm đầu CH → S/SH: Âm đầu CH thường tương ứng với hàng サ (S/SH), không phải チ.', '{"type": "WORD", "rule": "INIT_CH", "kanji": "証", "hanViet": "Chứng", "reading": "ショウ", "romaji": "shou", "focus": "証明"}', @onyomi_quiz_1_10, 25, 'onyomi:INIT_CH:証:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 恋愛 (Luyến Ái) đọc là gì?', 'れんあい', 'れんあい', 'れんしゃい', 'れんたい', 'れんさい', 1, '恋愛 = 恋 (Luyến → れん) + 愛 (Ái → あい) → れんあい: tình yêu. 愛 (Ái) → あい theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "愛", "hanViet": "Ái", "reading": "アイ", "romaji": "ai", "focus": "恋愛"}', @onyomi_quiz_1_10, 26, 'onyomi:INIT_VOWEL:愛:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 飲料 (Ẩm Liệu) đọc là gì?', 'いんりょう', 'ちんりょう', 'しんりょう', 'いんりょう', 'みんりょう', 3, '飲料 = 飲 (Ẩm → いん) + 料 (Liệu → りょう) → いんりょう: đồ uống. 飲 (Ẩm) → いん theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "飲", "hanViet": "Ẩm", "reading": "イン", "romaji": "in", "focus": "飲料"}', @onyomi_quiz_1_10, 27, 'onyomi:INIT_VOWEL:飲:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 影響 (Ảnh Hưởng) đọc là gì?', 'えいきょう', 'でいきょう', 'せいきょう', 'ていきょう', 'えいきょう', 4, '影響 = 影 (Ảnh → えい) + 響 (Hưởng → きょう) → えいきょう: ảnh hưởng. 影 (Ảnh) → えい theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "影", "hanViet": "Ảnh", "reading": "エイ", "romaji": "ei", "focus": "影響"}', @onyomi_quiz_1_10, 28, 'onyomi:INIT_VOWEL:影:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 屋上 (Ốc Thượng) đọc là gì?', 'おくじょう', 'しょくじょう', 'そくじょう', 'おくじょう', 'とくじょう', 3, '屋上 = 屋 (Ốc → おく) + 上 (Thượng → じょう) → おくじょう: sân thượng. 屋 (Ốc) → おく theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "屋", "hanViet": "Ốc", "reading": "オク", "romaji": "oku", "focus": "屋上"}', @onyomi_quiz_1_10, 29, 'onyomi:INIT_VOWEL:屋:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 優秀 (Ưu Tú) đọc là gì?', 'ゆうしゅう', 'ゆうしゅう', 'すうしゅう', 'しゅうしゅう', 'つうしゅう', 1, '優秀 = 優 (Ưu → ゆう) + 秀 (Tú → しゅう) → ゆうしゅう: ưu tú. 優 (Ưu) → ゆう theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "優", "hanViet": "Ưu", "reading": "ユウ", "romaji": "yuu", "focus": "優秀"}', @onyomi_quiz_1_10, 30, 'onyomi:INIT_VOWEL:優:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 応援 (Ứng Viện) đọc là gì?', 'おうえん', 'とうえん', 'おうえん', 'しょうえん', 'そうえん', 2, '応援 = 応 (Ứng → おう) + 援 (Viện → えん) → おうえん: cổ vũ. 応 (Ứng) → おう theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "応", "hanViet": "Ứng", "reading": "オウ", "romaji": "ou", "focus": "応援"}', @onyomi_quiz_1_10, 31, 'onyomi:INIT_VOWEL:応:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 利益 (Lợi Ích) đọc là gì?', 'りえき', 'りてき', 'りせき', 'りでき', 'りえき', 4, '利益 = 利 (Lợi → り) + 益 (Ích → えき) → りえき: lợi ích. 益 (Ích) → えき theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "益", "hanViet": "Ích", "reading": "エキ", "romaji": "eki", "focus": "利益"}', @onyomi_quiz_1_10, 32, 'onyomi:INIT_VOWEL:益:WORD'),
    (@onyomi_rule_init_vowel, @onyomi_tag_1, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 重要 (Trọng Yếu) đọc là gì?', 'じゅうよう', 'じゅうよう', 'じゅうとう', 'じゅうそう', 'じゅうしょう', 1, '重要 = 重 (Trọng → じゅう) + 要 (Yếu → よう) → じゅうよう: quan trọng. 要 (Yếu) → よう theo quy tắc Nguyên âm đầu → nguyên âm/Y: Chữ Hán Việt bắt đầu bằng nguyên âm (A, Â, Ư, Ô, Y…) thường giữ nguyên âm ở âm On, hoặc thêm Y.', '{"type": "WORD", "rule": "INIT_VOWEL", "kanji": "要", "hanViet": "Yếu", "reading": "ヨウ", "romaji": "you", "focus": "重要"}', @onyomi_quiz_1_10, 33, 'onyomi:INIT_VOWEL:要:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 飲食 (Ẩm Thực) đọc là gì?', 'いんしょく', 'いつしょく', 'いんしょく', 'いくしょく', 'いいしょく', 2, '飲食 = 飲 (Ẩm → いん) + 食 (Thực → しょく) → いんしょく: ăn uống. 飲 (Ẩm) → いん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "飲", "hanViet": "Ẩm", "reading": "イン", "romaji": "in", "focus": "飲食"}', @onyomi_quiz_2_1, 17, 'onyomi:FIN_M:飲:WORD');
INSERT INTO assessment_question (conversion_rule_id, knowledge_tag_id, skill, jlpt_level, difficulty,
    question_type, question_text, answer_text, option_1, option_2, option_3, option_4, correct_option,
    explanation, context_json, quiz_id, order_index, title) VALUES
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 厳重 (Nghiêm Trọng) đọc là gì?', 'げんじゅう', 'げいじゅう', 'げくじゅう', 'げつじゅう', 'げんじゅう', 4, '厳重 = 厳 (Nghiêm → げん) + 重 (Trọng → じゅう) → げんじゅう: nghiêm ngặt. 厳 (Nghiêm) → げん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "厳", "hanViet": "Nghiêm", "reading": "ゲン", "romaji": "gen", "focus": "厳重"}', @onyomi_quiz_2_1, 18, 'onyomi:FIN_M:厳:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 監視 (Giám Thị) đọc là gì?', 'かんし', 'かんし', 'かいし', 'かくし', 'かつし', 1, '監視 = 監 (Giám → かん) + 視 (Thị → し) → かんし: giám sát. 監 (Giám) → かん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "監", "hanViet": "Giám", "reading": "カン", "romaji": "kan", "focus": "監視"}', @onyomi_quiz_2_1, 19, 'onyomi:FIN_M:監:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 侵入 (Xâm Nhập) đọc là gì?', 'しんにゅう', 'しいにゅう', 'しくにゅう', 'しんにゅう', 'しつにゅう', 3, '侵入 = 侵 (Xâm → しん) + 入 (Nhập → にゅう) → しんにゅう: xâm nhập. 侵 (Xâm) → しん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "侵", "hanViet": "Xâm", "reading": "シン", "romaji": "shin", "focus": "侵入"}', @onyomi_quiz_2_1, 20, 'onyomi:FIN_M:侵:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 範囲 (Phạm Vi) đọc là gì?', 'はんい', 'はいい', 'はつい', 'はんい', 'はくい', 3, '範囲 = 範 (Phạm → はん) + 囲 (Vi → い) → はんい: phạm vi. 範 (Phạm) → はん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "範", "hanViet": "Phạm", "reading": "ハン", "romaji": "han", "focus": "範囲"}', @onyomi_quiz_2_1, 21, 'onyomi:FIN_M:範:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 犯人 (Phạm Nhân) đọc là gì?', 'はんにん', 'はくにん', 'はいにん', 'はんにん', 'はつにん', 3, '犯人 = 犯 (Phạm → はん) + 人 (Nhân → にん) → はんにん: thủ phạm. 犯 (Phạm) → はん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "犯", "hanViet": "Phạm", "reading": "ハン", "romaji": "han", "focus": "犯人"}', @onyomi_quiz_2_1, 22, 'onyomi:FIN_M:犯:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 独占 (Độc Chiếm) đọc là gì?', 'どくせん', 'どくせつ', 'どくせく', 'どくせん', 'どくせい', 3, '独占 = 独 (Độc → どく) + 占 (Chiếm → せん) → どくせん: độc chiếm. 占 (Chiếm) → せん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "占", "hanViet": "Chiếm", "reading": "セン", "romaji": "sen", "focus": "独占"}', @onyomi_quiz_2_1, 23, 'onyomi:FIN_M:占:WORD'),
    (@onyomi_rule_fin_m, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 展覧会 (Triển Lãm Hội) đọc là gì?', 'てんらんかい', 'てんらつかい', 'てんらいかい', 'てんらくかい', 'てんらんかい', 4, '展覧会 = 展 (Triển → てん) + 覧 (Lãm → らん) + 会 (Hội → かい) → てんらんかい: triển lãm. 覧 (Lãm) → らん theo quy tắc Âm cuối M → ン: Âm cuối -M của Hán Việt thành ン (n) trong âm On.', '{"type": "WORD", "rule": "FIN_M", "kanji": "覧", "hanViet": "Lãm", "reading": "ラン", "romaji": "ran", "focus": "展覧会"}', @onyomi_quiz_2_1, 24, 'onyomi:FIN_M:覧:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 全部 (Toàn Bộ) đọc là gì?', 'ぜんぶ', 'ぜいぶ', 'ぜんぶ', 'ぜくぶ', 'ぜつぶ', 2, '全部 = 全 (Toàn → ぜん) + 部 (Bộ → ぶ) → ぜんぶ: toàn bộ. 全 (Toàn) → ぜん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "全", "hanViet": "Toàn", "reading": "ゼン", "romaji": "zen", "focus": "全部"}', @onyomi_quiz_2_1, 25, 'onyomi:FIN_N:全:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 以前 (Dĩ Tiền) đọc là gì?', 'いぜん', 'いぜん', 'いぜい', 'いぜつ', 'いぜく', 1, '以前 = 以 (Dĩ → い) + 前 (Tiền → ぜん) → いぜん: trước đây. 前 (Tiền) → ぜん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "前", "hanViet": "Tiền", "reading": "ゼン", "romaji": "zen", "focus": "以前"}', @onyomi_quiz_2_1, 26, 'onyomi:FIN_N:前:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 結婚 (Kết Hôn) đọc là gì?', 'けっこん', 'けっこい', 'けっこく', 'けっこつ', 'けっこん', 4, '結婚 = 結 (Kết → けっ) + 婚 (Hôn → こん) → けっこん: kết hôn. 婚 (Hôn) → こん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "婚", "hanViet": "Hôn", "reading": "コン", "romaji": "kon", "focus": "結婚"}', @onyomi_quiz_2_1, 27, 'onyomi:FIN_N:婚:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 存在 (Tồn Tại) đọc là gì?', 'そんざい', 'そいざい', 'そんざい', 'そつざい', 'そくざい', 2, '存在 = 存 (Tồn → そん) + 在 (Tại → ざい) → そんざい: tồn tại. 存 (Tồn) → そん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "存", "hanViet": "Tồn", "reading": "ソン", "romaji": "son", "focus": "存在"}', @onyomi_quiz_2_1, 28, 'onyomi:FIN_N:存:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 結論 (Kết Luận) đọc là gì?', 'けつろん', 'けつろい', 'けつろん', 'けつろつ', 'けつろく', 2, '結論 = 結 (Kết → けつ) + 論 (Luận → ろん) → けつろん: kết luận. 論 (Luận) → ろん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "論", "hanViet": "Luận", "reading": "ロン", "romaji": "ron", "focus": "結論"}', @onyomi_quiz_2_1, 29, 'onyomi:FIN_N:論:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 新鮮 (Tân Tiên) đọc là gì?', 'しんせん', 'しんせつ', 'しんせく', 'しんせい', 'しんせん', 4, '新鮮 = 新 (Tân → しん) + 鮮 (Tiên → せん) → しんせん: tươi. 鮮 (Tiên) → せん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "鮮", "hanViet": "Tiên", "reading": "セン", "romaji": "sen", "focus": "新鮮"}', @onyomi_quiz_2_1, 30, 'onyomi:FIN_N:鮮:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 大根 (Đại Căn) đọc là gì?', 'だいこん', 'だいこつ', 'だいこい', 'だいこん', 'だいこく', 3, '大根 = 大 (Đại → だい) + 根 (Căn → こん) → だいこん: củ cải trắng. 根 (Căn) → こん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "根", "hanViet": "Căn", "reading": "コン", "romaji": "kon", "focus": "大根"}', @onyomi_quiz_2_1, 31, 'onyomi:FIN_N:根:WORD'),
    (@onyomi_rule_fin_n, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 提案 (Đề Án) đọc là gì?', 'ていあん', 'ていあく', 'ていあつ', 'ていあん', 'ていあい', 3, '提案 = 提 (Đề → てい) + 案 (Án → あん) → ていあん: đề xuất. 案 (Án) → あん theo quy tắc Âm cuối N → ン: Âm cuối -N của Hán Việt giữ nguyên thành ン.', '{"type": "WORD", "rule": "FIN_N", "kanji": "案", "hanViet": "Án", "reading": "アン", "romaji": "an", "focus": "提案"}', @onyomi_quiz_2_1, 32, 'onyomi:FIN_N:案:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 民族 (Dân Tộc) đọc là gì?', 'みんぞく', 'みんぞつ', 'みんぞき', 'みんぞん', 'みんぞく', 4, '民族 = 民 (Dân → みん) + 族 (Tộc → ぞく) → みんぞく: dân tộc. 族 (Tộc) → ぞく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "族", "hanViet": "Tộc", "reading": "ゾク", "romaji": "zoku", "focus": "民族"}', @onyomi_quiz_2_2, 11, 'onyomi:FIN_C:族:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 高速 (Cao Tốc) đọc là gì?', 'こうそく', 'こうそつ', 'こうそく', 'こうそき', 'こうそん', 2, '高速 = 高 (Cao → こう) + 速 (Tốc → そく) → こうそく: cao tốc. 速 (Tốc) → そく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "速", "hanViet": "Tốc", "reading": "ソク", "romaji": "soku", "focus": "高速"}', @onyomi_quiz_2_2, 12, 'onyomi:FIN_C:速:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 連続 (Liên Tục) đọc là gì?', 'れんぞく', 'れんぞき', 'れんぞく', 'れんぞつ', 'れんぞん', 2, '連続 = 連 (Liên → れん) + 続 (Tục → ぞく) → れんぞく: liên tục. 続 (Tục) → ぞく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "続", "hanViet": "Tục", "reading": "ゾク", "romaji": "zoku", "focus": "連続"}', @onyomi_quiz_2_2, 13, 'onyomi:FIN_C:続:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 制服 (Chế Phục) đọc là gì?', 'せいふく', 'せいふく', 'せいふつ', 'せいふん', 'せいふき', 1, '制服 = 制 (Chế → せい) + 服 (Phục → ふく) → せいふく: đồng phục. 服 (Phục) → ふく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "服", "hanViet": "Phục", "reading": "フク", "romaji": "fuku", "focus": "制服"}', @onyomi_quiz_2_2, 14, 'onyomi:FIN_C:服:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 読者 (Độc Giả) đọc là gì?', 'どくしゃ', 'どつしゃ', 'どんしゃ', 'どくしゃ', 'どきしゃ', 3, '読者 = 読 (Độc → どく) + 者 (Giả → しゃ) → どくしゃ: độc giả. 読 (Độc) → どく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "読", "hanViet": "Độc", "reading": "ドク", "romaji": "doku", "focus": "読者"}', @onyomi_quiz_2_2, 15, 'onyomi:FIN_C:読:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 薬局 (Dược Cục) đọc là gì?', 'やっきょく', 'やっきょき', 'やっきょく', 'やっきょん', 'やっきょつ', 2, '薬局 = 薬 (Dược → やっ) + 局 (Cục → きょく) → やっきょく: hiệu thuốc. 局 (Cục) → きょく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "局", "hanViet": "Cục", "reading": "キョク", "romaji": "kyoku", "focus": "薬局"}', @onyomi_quiz_2_2, 16, 'onyomi:FIN_C:局:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 気楽 (Khí Lạc) đọc là gì?', 'きらく', 'きらつ', 'きらき', 'きらく', 'きらん', 3, '気楽 = 気 (Khí → き) + 楽 (Lạc → らく) → きらく: thoải mái. 楽 (Lạc) → らく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "楽", "hanViet": "Lạc", "reading": "ラク", "romaji": "raku", "focus": "気楽"}', @onyomi_quiz_2_2, 17, 'onyomi:FIN_C:楽:WORD'),
    (@onyomi_rule_fin_c, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 原則 (Nguyên Tắc) đọc là gì?', 'げんそく', 'げんそん', 'げんそつ', 'げんそく', 'げんそき', 3, '原則 = 原 (Nguyên → げん) + 則 (Tắc → そく) → げんそく: nguyên tắc. 則 (Tắc) → そく theo quy tắc Âm cuối C → ク: Âm cuối -C của Hán Việt thường thành ク (ku) ở cuối âm On.', '{"type": "WORD", "rule": "FIN_C", "kanji": "則", "hanViet": "Tắc", "reading": "ソク", "romaji": "soku", "focus": "原則"}', @onyomi_quiz_2_2, 18, 'onyomi:FIN_C:則:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 成績 (Thành Tích) đọc là gì?', 'せいせき', 'せいせき', 'せいせつ', 'せいせん', 'せいせち', 1, '成績 = 成 (Thành → せい) + 績 (Tích → せき) → せいせき: thành tích. 績 (Tích) → せき theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "績", "hanViet": "Tích", "reading": "セキ", "romaji": "seki", "focus": "成績"}', @onyomi_quiz_2_3, 9, 'onyomi:FIN_CH:績:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 面積 (Diện Tích) đọc là gì?', 'めんせき', 'めんせん', 'めんせつ', 'めんせき', 'めんせち', 3, '面積 = 面 (Diện → めん) + 積 (Tích → せき) → めんせき: diện tích. 積 (Tích) → せき theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "積", "hanViet": "Tích", "reading": "セキ", "romaji": "seki", "focus": "面積"}', @onyomi_quiz_2_3, 10, 'onyomi:FIN_CH:積:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 無敵 (Vô Địch) đọc là gì?', 'むてき', 'むてき', 'むてち', 'むてん', 'むてつ', 1, '無敵 = 無 (Vô → む) + 敵 (Địch → てき) → むてき: vô địch. 敵 (Địch) → てき theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "敵", "hanViet": "Địch", "reading": "テキ", "romaji": "teki", "focus": "無敵"}', @onyomi_quiz_2_3, 11, 'onyomi:FIN_CH:敵:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 感激 (Cảm Kích) đọc là gì?', 'かんげき', 'かんげき', 'かんげん', 'かんげち', 'かんげつ', 1, '感激 = 感 (Cảm → かん) + 激 (Kích → げき) → かんげき: cảm kích. 激 (Kích) → げき theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "激", "hanViet": "Kích", "reading": "ゲキ", "romaji": "geki", "focus": "感激"}', @onyomi_quiz_2_3, 12, 'onyomi:FIN_CH:激:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 対策 (Đối Sách) đọc là gì?', 'たいさく', 'たいさつ', 'たいさく', 'たいさち', 'たいさん', 2, '対策 = 対 (Đối → たい) + 策 (Sách → さく) → たいさく: đối sách. 策 (Sách) → さく theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "策", "hanViet": "Sách", "reading": "サク", "romaji": "saku", "focus": "対策"}', @onyomi_quiz_2_3, 13, 'onyomi:FIN_CH:策:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 拍手 (Phách Thủ) đọc là gì?', 'はくしゅ', 'はくしゅ', 'はんしゅ', 'はつしゅ', 'はちしゅ', 1, '拍手 = 拍 (Phách → はく) + 手 (Thủ → しゅ) → はくしゅ: vỗ tay. 拍 (Phách) → はく theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "拍", "hanViet": "Phách", "reading": "ハク", "romaji": "haku", "focus": "拍手"}', @onyomi_quiz_2_3, 14, 'onyomi:FIN_CH:拍:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 革命 (Cách Mệnh) đọc là gì?', 'かくめい', 'かんめい', 'かつめい', 'かくめい', 'かちめい', 3, '革命 = 革 (Cách → かく) + 命 (Mệnh → めい) → かくめい: cách mạng. 革 (Cách) → かく theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "革", "hanViet": "Cách", "reading": "カク", "romaji": "kaku", "focus": "革命"}', @onyomi_quiz_2_3, 15, 'onyomi:FIN_CH:革:WORD'),
    (@onyomi_rule_fin_ch, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 国籍 (Quốc Tịch) đọc là gì?', 'こくせき', 'こくせき', 'こくせつ', 'こくせん', 'こくせち', 1, '国籍 = 国 (Quốc → こく) + 籍 (Tịch → せき) → こくせき: quốc tịch. 籍 (Tịch) → せき theo quy tắc Âm cuối CH → キ/ク: Âm cuối -CH thành キ (ki) sau I/Ê (-ICH, -ÊCH), và thành ク (ku) sau A (-ACH).', '{"type": "WORD", "rule": "FIN_CH", "kanji": "籍", "hanViet": "Tịch", "reading": "セキ", "romaji": "seki", "focus": "国籍"}', @onyomi_quiz_2_3, 16, 'onyomi:FIN_CH:籍:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 団結 (Đoàn Kết) đọc là gì?', 'だんけつ', 'だんけつ', 'だんけう', 'だんけき', 'だんけく', 1, '団結 = 団 (Đoàn → だん) + 結 (Kết → けつ) → だんけつ: đoàn kết. 結 (Kết) → けつ theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "結", "hanViet": "Kết", "reading": "ケツ", "romaji": "ketsu", "focus": "団結"}', @onyomi_quiz_2_4, 16, 'onyomi:FIN_T:結:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 規律 (Quy Luật) đọc là gì?', 'きりつ', 'きりつ', 'きりく', 'きりき', 'きりう', 1, '規律 = 規 (Quy → き) + 律 (Luật → りつ) → きりつ: kỷ luật. 律 (Luật) → りつ theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "律", "hanViet": "Luật", "reading": "リツ", "romaji": "ritsu", "focus": "規律"}', @onyomi_quiz_2_4, 17, 'onyomi:FIN_T:律:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 親密 (Thân Mật) đọc là gì?', 'しんみつ', 'しんみう', 'しんみき', 'しんみく', 'しんみつ', 4, '親密 = 親 (Thân → しん) + 密 (Mật → みつ) → しんみつ: thân mật. 密 (Mật) → みつ theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "密", "hanViet": "Mật", "reading": "ミツ", "romaji": "mitsu", "focus": "親密"}', @onyomi_quiz_2_4, 18, 'onyomi:FIN_T:密:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 考察 (Khảo Sát) đọc là gì?', 'こうさつ', 'こうさく', 'こうさき', 'こうさう', 'こうさつ', 4, '考察 = 考 (Khảo → こう) + 察 (Sát → さつ) → こうさつ: khảo sát. 察 (Sát) → さつ theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "察", "hanViet": "Sát", "reading": "サツ", "romaji": "satsu", "focus": "考察"}', @onyomi_quiz_2_4, 19, 'onyomi:FIN_T:察:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 節約 (Tiết Ước) đọc là gì?', 'せつやく', 'せうやく', 'せくやく', 'せつやく', 'せきやく', 3, '節約 = 節 (Tiết → せつ) + 約 (Ước → やく) → せつやく: tiết kiệm. 節 (Tiết) → せつ theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "節", "hanViet": "Tiết", "reading": "セツ", "romaji": "setsu", "focus": "節約"}', @onyomi_quiz_2_4, 20, 'onyomi:FIN_T:節:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 手術 (Thủ Thuật) đọc là gì?', 'しゅじゅつ', 'しゅじゅき', 'しゅじゅく', 'しゅじゅう', 'しゅじゅつ', 4, '手術 = 手 (Thủ → しゅ) + 術 (Thuật → じゅつ) → しゅじゅつ: phẫu thuật. 術 (Thuật) → じゅつ theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "術", "hanViet": "Thuật", "reading": "ジュツ", "romaji": "jutsu", "focus": "手術"}', @onyomi_quiz_2_4, 21, 'onyomi:FIN_T:術:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 週末 (Chu Mạt) đọc là gì?', 'しゅうまつ', 'しゅうまつ', 'しゅうまう', 'しゅうまく', 'しゅうまき', 1, '週末 = 週 (Chu → しゅう) + 末 (Mạt → まつ) → しゅうまつ: cuối tuần. 末 (Mạt) → まつ theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "末", "hanViet": "Mạt", "reading": "マツ", "romaji": "matsu", "focus": "週末"}', @onyomi_quiz_2_4, 22, 'onyomi:FIN_T:末:WORD'),
    (@onyomi_rule_fin_t, @onyomi_tag_2, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 七月 (Thất Nguyệt) đọc là gì?', 'しちがつ', 'しちがつ', 'しうがつ', 'しくがつ', 'しきがつ', 1, '七月 = 七 (Thất → しち) + 月 (Nguyệt → がつ) → しちがつ: tháng bảy. 七 (Thất) → しち theo quy tắc Âm cuối T → ツ/チ: Âm cuối -T của Hán Việt thành ツ (tsu), đôi khi チ (chi).', '{"type": "WORD", "rule": "FIN_T", "kanji": "七", "hanViet": "Thất", "reading": "シチ", "romaji": "shichi", "focus": "七月"}', @onyomi_quiz_2_4, 23, 'onyomi:FIN_T:七:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 工業 (Công Nghiệp) đọc là gì?', 'こうぎょう', 'こうぎょう', 'こうぎょち', 'こうぎょつ', 'こうぎょき', 1, '工業 = 工 (Công → こう) + 業 (Nghiệp → ぎょう) → こうぎょう: công nghiệp. 業 (Nghiệp) → ぎょう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "業", "hanViet": "Nghiệp", "reading": "ギョウ", "romaji": "gyou", "focus": "工業"}', @onyomi_quiz_2_4, 24, 'onyomi:FIN_P:業:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 紅葉 (Hồng Diệp) đọc là gì?', 'こうよう', 'こうよち', 'こうよつ', 'こうよき', 'こうよう', 4, '紅葉 = 紅 (Hồng → こう) + 葉 (Diệp → よう) → こうよう: lá đỏ mùa thu. 葉 (Diệp) → よう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "葉", "hanViet": "Diệp", "reading": "ヨウ", "romaji": "you", "focus": "紅葉"}', @onyomi_quiz_2_4, 25, 'onyomi:FIN_P:葉:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 給料 (Cấp Liệu) đọc là gì?', 'きゅうりょう', 'きゅつりょう', 'きゅうりょう', 'きゅちりょう', 'きゅきりょう', 2, '給料 = 給 (Cấp → きゅう) + 料 (Liệu → りょう) → きゅうりょう: tiền lương. 給 (Cấp) → きゅう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "給", "hanViet": "Cấp", "reading": "キュウ", "romaji": "kyuu", "focus": "給料"}', @onyomi_quiz_2_4, 26, 'onyomi:FIN_P:給:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 普及 (Phổ Cập) đọc là gì?', 'ふきゅう', 'ふきゅき', 'ふきゅち', 'ふきゅう', 'ふきゅつ', 3, '普及 = 普 (Phổ → ふ) + 及 (Cập → きゅう) → ふきゅう: phổ cập. 及 (Cập) → きゅう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "及", "hanViet": "Cập", "reading": "キュウ", "romaji": "kyuu", "focus": "普及"}', @onyomi_quiz_2_4, 27, 'onyomi:FIN_P:及:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 呼吸 (Hô Hấp) đọc là gì?', 'こきゅう', 'こきゅう', 'こきゅち', 'こきゅき', 'こきゅつ', 1, '呼吸 = 呼 (Hô → こ) + 吸 (Hấp → きゅう) → こきゅう: hô hấp. 吸 (Hấp) → きゅう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "吸", "hanViet": "Hấp", "reading": "キュウ", "romaji": "kyuu", "focus": "呼吸"}', @onyomi_quiz_2_4, 28, 'onyomi:FIN_P:吸:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 海峡 (Hải Hiệp) đọc là gì?', 'かいきょう', 'かいきょう', 'かいきょき', 'かいきょち', 'かいきょつ', 1, '海峡 = 海 (Hải → かい) + 峡 (Hiệp → きょう) → かいきょう: eo biển. 峡 (Hiệp) → きょう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "峡", "hanViet": "Hiệp", "reading": "キョウ", "romaji": "kyou", "focus": "海峡"}', @onyomi_quiz_2_4, 29, 'onyomi:FIN_P:峡:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 貧乏 (Bần Phạp) đọc là gì?', 'びんぼう', 'びんぼう', 'びんぼつ', 'びんぼき', 'びんぼち', 1, '貧乏 = 貧 (Bần → びん) + 乏 (Phạp → ぼう) → びんぼう: nghèo. 乏 (Phạp) → ぼう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "乏", "hanViet": "Phạp", "reading": "ボウ", "romaji": "bou", "focus": "貧乏"}', @onyomi_quiz_2_4, 30, 'onyomi:FIN_P:乏:WORD'),
    (@onyomi_rule_fin_p, @onyomi_tag_2, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 収納 (Thu Nạp) đọc là gì?', 'しゅうのう', 'しゅうのつ', 'しゅうのき', 'しゅうのう', 'しゅうのち', 3, '収納 = 収 (Thu → しゅう) + 納 (Nạp → のう) → しゅうのう: cất giữ. 納 (Nạp) → のう theo quy tắc Âm cuối P → ウ (trường âm): Âm cuối -P của Hán Việt thường biến thành trường âm ウ: -ap/-ơp → オウ, -âp/-ip → ュウ.', '{"type": "WORD", "rule": "FIN_P", "kanji": "納", "hanViet": "Nạp", "reading": "ノウ", "romaji": "nou", "focus": "収納"}', @onyomi_quiz_2_4, 31, 'onyomi:FIN_P:納:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 主観 (Chủ Quan) đọc là gì?', 'しゅかん', 'しゅかん', 'しゅけん', 'しゅきん', 'しゅこう', 1, '主観 = 主 (Chủ → しゅ) + 観 (Quan → かん) → しゅかん: chủ quan. 観 (Quan) → かん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "観", "hanViet": "Quan", "reading": "カン", "romaji": "kan", "focus": "主観"}', @onyomi_quiz_3_1, 16, 'onyomi:RHY_AN:観:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 保管 (Bảo Quản) đọc là gì?', 'ほかん', 'ほこう', 'ほかん', 'ほきん', 'ほけん', 2, '保管 = 保 (Bảo → ほ) + 管 (Quản → かん) → ほかん: bảo quản. 管 (Quản) → かん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "管", "hanViet": "Quản", "reading": "カン", "romaji": "kan", "focus": "保管"}', @onyomi_quiz_3_1, 17, 'onyomi:RHY_AN:管:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 満員 (Mãn Viên) đọc là gì?', 'まんいん', 'まんいん', 'もういん', 'みんいん', 'めんいん', 1, '満員 = 満 (Mãn → まん) + 員 (Viên → いん) → まんいん: chật kín người. 満 (Mãn) → まん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "満", "hanViet": "Mãn", "reading": "マン", "romaji": "man", "focus": "満員"}', @onyomi_quiz_3_1, 18, 'onyomi:RHY_AN:満:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 混乱 (Hỗn Loạn) đọc là gì?', 'こんらん', 'こんれん', 'こんらん', 'こんろう', 'こんりん', 2, '混乱 = 混 (Hỗn → こん) + 乱 (Loạn → らん) → こんらん: hỗn loạn. 乱 (Loạn) → らん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "乱", "hanViet": "Loạn", "reading": "ラン", "romaji": "ran", "focus": "混乱"}', @onyomi_quiz_3_1, 19, 'onyomi:RHY_AN:乱:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 週刊 (Chu San) đọc là gì?', 'しゅうかん', 'しゅうこう', 'しゅうきん', 'しゅうけん', 'しゅうかん', 4, '週刊 = 週 (Chu → しゅう) + 刊 (San → かん) → しゅうかん: ra hằng tuần. 刊 (San) → かん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "刊", "hanViet": "San", "reading": "カン", "romaji": "kan", "focus": "週刊"}', @onyomi_quiz_3_1, 20, 'onyomi:RHY_AN:刊:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 看板 (Khán Bản) đọc là gì?', 'かんばん', 'きんばん', 'こうばん', 'かんばん', 'けんばん', 3, '看板 = 看 (Khán → かん) + 板 (Bản → ばん) → かんばん: biển hiệu. 看 (Khán) → かん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "看", "hanViet": "Khán", "reading": "カン", "romaji": "kan", "focus": "看板"}', @onyomi_quiz_3_1, 21, 'onyomi:RHY_AN:看:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 交換 (Giao Hoán) đọc là gì?', 'こうかん', 'こうきん', 'こうかん', 'こうけん', 'こうこう', 2, '交換 = 交 (Giao → こう) + 換 (Hoán → かん) → こうかん: trao đổi. 換 (Hoán) → かん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "換", "hanViet": "Hoán", "reading": "カン", "romaji": "kan", "focus": "交換"}', @onyomi_quiz_3_1, 22, 'onyomi:RHY_AN:換:WORD'),
    (@onyomi_rule_rhy_an, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 習慣 (Tập Quán) đọc là gì?', 'しゅうかん', 'しゅうけん', 'しゅうかん', 'しゅうきん', 'しゅうこう', 2, '習慣 = 習 (Tập → しゅう) + 慣 (Quán → かん) → しゅうかん: thói quen. 慣 (Quán) → かん theo quy tắc Vần AN → アン: Vần AN (kể cả OAN) thường giữ nguyên thành アン.', '{"type": "WORD", "rule": "RHY_AN", "kanji": "慣", "hanViet": "Quán", "reading": "カン", "romaji": "kan", "focus": "習慣"}', @onyomi_quiz_3_1, 23, 'onyomi:RHY_AN:慣:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 監査 (Giám Tra) đọc là gì?', 'かんさ', 'きんさ', 'けんさ', 'こうさ', 'かんさ', 4, '監査 = 監 (Giám → かん) + 査 (Tra → さ) → かんさ: kiểm toán. 監 (Giám) → かん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "監", "hanViet": "Giám", "reading": "カン", "romaji": "kan", "focus": "監査"}', @onyomi_quiz_3_1, 24, 'onyomi:RHY_AM:監:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 模範 (Mô Phạm) đọc là gì?', 'もはん', 'もひん', 'もへん', 'もはん', 'もほう', 3, '模範 = 模 (Mô → も) + 範 (Phạm → はん) → もはん: mẫu mực. 範 (Phạm) → はん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "範", "hanViet": "Phạm", "reading": "ハン", "romaji": "han", "focus": "模範"}', @onyomi_quiz_3_1, 25, 'onyomi:RHY_AM:範:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 犯罪 (Phạm Tội) đọc là gì?', 'はんざい', 'はんざい', 'へんざい', 'ほうざい', 'ひんざい', 1, '犯罪 = 犯 (Phạm → はん) + 罪 (Tội → ざい) → はんざい: tội phạm. 犯 (Phạm) → はん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "犯", "hanViet": "Phạm", "reading": "ハン", "romaji": "han", "focus": "犯罪"}', @onyomi_quiz_3_1, 26, 'onyomi:RHY_AM:犯:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 一覧 (Nhất Lãm) đọc là gì?', 'いちらん', 'いちろう', 'いちりん', 'いちらん', 'いちれん', 3, '一覧 = 一 (Nhất → いち) + 覧 (Lãm → らん) → いちらん: danh sách. 覧 (Lãm) → らん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "覧", "hanViet": "Lãm", "reading": "ラン", "romaji": "ran", "focus": "一覧"}', @onyomi_quiz_3_1, 27, 'onyomi:RHY_AM:覧:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 悲惨 (Bi Thảm) đọc là gì?', 'ひさん', 'ひさん', 'ひそう', 'ひせん', 'ひしん', 1, '悲惨 = 悲 (Bi → ひ) + 惨 (Thảm → さん) → ひさん: bi thảm. 惨 (Thảm) → さん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "惨", "hanViet": "Thảm", "reading": "サン", "romaji": "san", "focus": "悲惨"}', @onyomi_quiz_3_1, 28, 'onyomi:RHY_AM:惨:WORD'),
    (@onyomi_rule_rhy_am, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 冷淡 (Lãnh Đạm) đọc là gì?', 'れいたん', 'れいとう', 'れいたん', 'れいてん', 'れいちん', 2, '冷淡 = 冷 (Lãnh → れい) + 淡 (Đạm → たん) → れいたん: lạnh nhạt. 淡 (Đạm) → たん theo quy tắc Vần AM → アン: Vần AM cũng thành アン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AM", "kanji": "淡", "hanViet": "Đạm", "reading": "タン", "romaji": "tan", "focus": "冷淡"}', @onyomi_quiz_3_1, 29, 'onyomi:RHY_AM:淡:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 神話 (Thần Thoại) đọc là gì?', 'しんわ', 'さんわ', 'せいわ', 'しんわ', 'せんわ', 3, '神話 = 神 (Thần → しん) + 話 (Thoại → わ) → しんわ: thần thoại. 神 (Thần) → しん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "神", "hanViet": "Thần", "reading": "シン", "romaji": "shin", "focus": "神話"}', @onyomi_quiz_3_2, 17, 'onyomi:RHY_AAN:神:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 震度 (Chấn Độ) đọc là gì?', 'しんど', 'しんど', 'せいど', 'さんど', 'せんど', 1, '震度 = 震 (Chấn → しん) + 度 (Độ → ど) → しんど: cấp độ động đất. 震 (Chấn) → しん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "震", "hanViet": "Chấn", "reading": "シン", "romaji": "shin", "focus": "震度"}', @onyomi_quiz_3_2, 18, 'onyomi:RHY_AAN:震:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 通勤 (Thông Cần) đọc là gì?', 'つうきん', 'つうけい', 'つうかん', 'つうけん', 'つうきん', 4, '通勤 = 通 (Thông → つう) + 勤 (Cần → きん) → つうきん: đi làm. 勤 (Cần) → きん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "勤", "hanViet": "Cần", "reading": "キン", "romaji": "kin", "focus": "通勤"}', @onyomi_quiz_3_2, 19, 'onyomi:RHY_AAN:勤:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 筋肉 (Cân Nhục) đọc là gì?', 'きんにく', 'けいにく', 'かんにく', 'きんにく', 'けんにく', 3, '筋肉 = 筋 (Cân → きん) + 肉 (Nhục → にく) → きんにく: cơ bắp. 筋 (Cân) → きん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "筋", "hanViet": "Cân", "reading": "キン", "romaji": "kin", "focus": "筋肉"}', @onyomi_quiz_3_2, 20, 'onyomi:RHY_AAN:筋:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 隣人 (Lân Nhân) đọc là gì?', 'りんじん', 'らんじん', 'りんじん', 'れんじん', 'れいじん', 2, '隣人 = 隣 (Lân → りん) + 人 (Nhân → じん) → りんじん: hàng xóm. 隣 (Lân) → りん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "隣", "hanViet": "Lân", "reading": "リン", "romaji": "rin", "focus": "隣人"}', @onyomi_quiz_3_2, 21, 'onyomi:RHY_AAN:隣:WORD'),
    (@onyomi_rule_rhy_aan, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 均一 (Quân Nhất) đọc là gì?', 'きんいつ', 'けいいつ', 'けんいつ', 'きんいつ', 'かんいつ', 3, '均一 = 均 (Quân → きん) + 一 (Nhất → いつ) → きんいつ: đồng đều. 均 (Quân) → きん theo quy tắc Vần ÂN → イン: Vần ÂN thường thành イン.', '{"type": "WORD", "rule": "RHY_AAN", "kanji": "均", "hanViet": "Quân", "reading": "キン", "romaji": "kin", "focus": "均一"}', @onyomi_quiz_3_2, 22, 'onyomi:RHY_AAN:均:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 飲酒 (Ẩm Tửu) đọc là gì?', 'いんしゅ', 'いんしゅ', 'えいしゅ', 'あんしゅ', 'えんしゅ', 1, '飲酒 = 飲 (Ẩm → いん) + 酒 (Tửu → しゅ) → いんしゅ: uống rượu. 飲 (Ẩm) → いん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "飲", "hanViet": "Ẩm", "reading": "イン", "romaji": "in", "focus": "飲酒"}', @onyomi_quiz_3_2, 23, 'onyomi:RHY_AAM:飲:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 審判 (Thẩm Phán) đọc là gì?', 'しんぱん', 'さんぱん', 'せいぱん', 'せんぱん', 'しんぱん', 4, '審判 = 審 (Thẩm → しん) + 判 (Phán → ぱん) → しんぱん: trọng tài. 審 (Thẩm) → しん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "審", "hanViet": "Thẩm", "reading": "シン", "romaji": "shin", "focus": "審判"}', @onyomi_quiz_3_2, 24, 'onyomi:RHY_AAM:審:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 侵略 (Xâm Lược) đọc là gì?', 'しんりゃく', 'せんりゃく', 'しんりゃく', 'せいりゃく', 'さんりゃく', 2, '侵略 = 侵 (Xâm → しん) + 略 (Lược → りゃく) → しんりゃく: xâm lược. 侵 (Xâm) → しん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "侵", "hanViet": "Xâm", "reading": "シン", "romaji": "shin", "focus": "侵略"}', @onyomi_quiz_3_2, 25, 'onyomi:RHY_AAM:侵:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 尋問 (Tầm Vấn) đọc là gì?', 'じんもん', 'じんもん', 'ざんもん', 'ぜんもん', 'ぜいもん', 1, '尋問 = 尋 (Tầm → じん) + 問 (Vấn → もん) → じんもん: thẩm vấn. 尋 (Tầm) → じん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "尋", "hanViet": "Tầm", "reading": "ジン", "romaji": "jin", "focus": "尋問"}', @onyomi_quiz_3_2, 26, 'onyomi:RHY_AAM:尋:WORD'),
    (@onyomi_rule_rhy_aam, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 臨時 (Lâm Thời) đọc là gì?', 'りんじ', 'らんじ', 'れいじ', 'れんじ', 'りんじ', 4, '臨時 = 臨 (Lâm → りん) + 時 (Thời → じ) → りんじ: lâm thời. 臨 (Lâm) → りん theo quy tắc Vần ÂM → イン: Vần ÂM thường thành イン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_AAM", "kanji": "臨", "hanViet": "Lâm", "reading": "リン", "romaji": "rin", "focus": "臨時"}', @onyomi_quiz_3_2, 27, 'onyomi:RHY_AAM:臨:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 人権 (Nhân Quyền) đọc là gì?', 'じんけん', 'じんきん', 'じんかん', 'じんけん', 'じんけい', 3, '人権 = 人 (Nhân → じん) + 権 (Quyền → けん) → じんけん: nhân quyền. 権 (Quyền) → けん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "権", "hanViet": "Quyền", "reading": "ケン", "romaji": "ken", "focus": "人権"}', @onyomi_quiz_3_3, 16, 'onyomi:RHY_IEN:権:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 県立 (Huyện Lập) đọc là gì?', 'けんりつ', 'けんりつ', 'けいりつ', 'きんりつ', 'かんりつ', 1, '県立 = 県 (Huyện → けん) + 立 (Lập → りつ) → けんりつ: do tỉnh lập. 県 (Huyện) → けん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "県", "hanViet": "Huyện", "reading": "ケン", "romaji": "ken", "focus": "県立"}', @onyomi_quiz_3_3, 17, 'onyomi:RHY_IEN:県:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 伝言 (Truyền Ngôn) đọc là gì?', 'でんごん', 'どうごん', 'だんごん', 'でいごん', 'でんごん', 4, '伝言 = 伝 (Truyền → でん) + 言 (Ngôn → ごん) → でんごん: lời nhắn. 伝 (Truyền) → でん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "伝", "hanViet": "Truyền", "reading": "デン", "romaji": "den", "focus": "伝言"}', @onyomi_quiz_3_3, 18, 'onyomi:RHY_IEN:伝:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 演技 (Diễn Kỹ) đọc là gì?', 'えんぎ', 'えいぎ', 'いんぎ', 'えんぎ', 'あんぎ', 3, '演技 = 演 (Diễn → えん) + 技 (Kỹ → ぎ) → えんぎ: diễn xuất. 演 (Diễn) → えん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "演", "hanViet": "Diễn", "reading": "エン", "romaji": "en", "focus": "演技"}', @onyomi_quiz_3_3, 19, 'onyomi:RHY_IEN:演:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 周辺 (Chu Biên) đọc là gì?', 'しゅうへん', 'しゅうはん', 'しゅうへい', 'しゅうへん', 'しゅうひん', 3, '周辺 = 周 (Chu → しゅう) + 辺 (Biên → へん) → しゅうへん: vùng xung quanh. 辺 (Biên) → へん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "辺", "hanViet": "Biên", "reading": "ヘン", "romaji": "hen", "focus": "周辺"}', @onyomi_quiz_3_3, 20, 'onyomi:RHY_IEN:辺:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 研修 (Nghiên Tu) đọc là gì?', 'けんしゅう', 'けんしゅう', 'きんしゅう', 'けいしゅう', 'かんしゅう', 1, '研修 = 研 (Nghiên → けん) + 修 (Tu → しゅう) → けんしゅう: tu nghiệp. 研 (Nghiên) → けん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "研", "hanViet": "Nghiên", "reading": "ケン", "romaji": "ken", "focus": "研修"}', @onyomi_quiz_3_3, 21, 'onyomi:RHY_IEN:研:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 転校 (Chuyển Hiệu) đọc là gì?', 'てんこう', 'ちんこう', 'ていこう', 'たんこう', 'てんこう', 4, '転校 = 転 (Chuyển → てん) + 校 (Hiệu → こう) → てんこう: chuyển trường. 転 (Chuyển) → てん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "転", "hanViet": "Chuyển", "reading": "テン", "romaji": "ten", "focus": "転校"}', @onyomi_quiz_3_3, 22, 'onyomi:RHY_IEN:転:WORD'),
    (@onyomi_rule_rhy_ien, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 新幹線 (Tân Cán Tuyến) đọc là gì?', 'しんかんせん', 'しんかんしん', 'しんかんさん', 'しんかんせん', 'しんかんせい', 3, '新幹線 = 新 (Tân → しん) + 幹 (Cán → かん) + 線 (Tuyến → せん) → しんかんせん: tàu cao tốc Shinkansen. 線 (Tuyến) → せん theo quy tắc Vần IÊN → エン: Vần IÊN (kể cả UYÊN) thường thành エン.', '{"type": "WORD", "rule": "RHY_IEN", "kanji": "線", "hanViet": "Tuyến", "reading": "セン", "romaji": "sen", "focus": "新幹線"}', @onyomi_quiz_3_3, 23, 'onyomi:RHY_IEN:線:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 占領 (Chiếm Lĩnh) đọc là gì?', 'せんりょう', 'さんりょう', 'しんりょう', 'せいりょう', 'せんりょう', 4, '占領 = 占 (Chiếm → せん) + 領 (Lĩnh → りょう) → せんりょう: chiếm đóng. 占 (Chiếm) → せん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "占", "hanViet": "Chiếm", "reading": "セン", "romaji": "sen", "focus": "占領"}', @onyomi_quiz_3_3, 24, 'onyomi:RHY_IEM:占:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 厳格 (Nghiêm Cách) đọc là gì?', 'げんかく', 'ぎんかく', 'げんかく', 'げいかく', 'がんかく', 2, '厳格 = 厳 (Nghiêm → げん) + 格 (Cách → かく) → げんかく: nghiêm khắc. 厳 (Nghiêm) → げん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "厳", "hanViet": "Nghiêm", "reading": "ゲン", "romaji": "gen", "focus": "厳格"}', @onyomi_quiz_3_3, 25, 'onyomi:RHY_IEM:厳:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 剣道 (Kiếm Đạo) đọc là gì?', 'けんどう', 'けいどう', 'かんどう', 'けんどう', 'きんどう', 3, '剣道 = 剣 (Kiếm → けん) + 道 (Đạo → どう) → けんどう: kiếm đạo. 剣 (Kiếm) → けん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "剣", "hanViet": "Kiếm", "reading": "ケン", "romaji": "ken", "focus": "剣道"}', @onyomi_quiz_3_3, 26, 'onyomi:RHY_IEM:剣:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 添付 (Thiêm Phó) đọc là gì?', 'てんぷ', 'たんぷ', 'てんぷ', 'ていぷ', 'ちんぷ', 2, '添付 = 添 (Thiêm → てん) + 付 (Phó → ぷ) → てんぷ: đính kèm. 添 (Thiêm) → てん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "添", "hanViet": "Thiêm", "reading": "テン", "romaji": "ten", "focus": "添付"}', @onyomi_quiz_3_3, 27, 'onyomi:RHY_IEM:添:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 食塩 (Thực Diêm) đọc là gì?', 'しょくえん', 'しょくいん', 'しょくえん', 'しょくえい', 'しょくあん', 2, '食塩 = 食 (Thực → しょく) + 塩 (Diêm → えん) → しょくえん: muối ăn. 塩 (Diêm) → えん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "塩", "hanViet": "Diêm", "reading": "エン", "romaji": "en", "focus": "食塩"}', @onyomi_quiz_3_3, 28, 'onyomi:RHY_IEM:塩:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 嫌悪 (Hiềm Ác) đọc là gì?', 'けんお', 'かんお', 'けいお', 'けんお', 'きんお', 3, '嫌悪 = 嫌 (Hiềm → けん) + 悪 (Ác → お) → けんお: ghê tởm. 嫌 (Hiềm) → けん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "嫌", "hanViet": "Hiềm", "reading": "ケン", "romaji": "ken", "focus": "嫌悪"}', @onyomi_quiz_3_3, 29, 'onyomi:RHY_IEM:嫌:WORD'),
    (@onyomi_rule_rhy_iem, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 謙虚 (Khiêm Hư) đọc là gì?', 'けんきょ', 'けいきょ', 'けんきょ', 'きんきょ', 'かんきょ', 2, '謙虚 = 謙 (Khiêm → けん) + 虚 (Hư → きょ) → けんきょ: khiêm tốn. 謙 (Khiêm) → けん theo quy tắc Vần IÊM → エン: Vần IÊM thường thành エン (âm cuối -M thành ン).', '{"type": "WORD", "rule": "RHY_IEM", "kanji": "謙", "hanViet": "Khiêm", "reading": "ケン", "romaji": "ken", "focus": "謙虚"}', @onyomi_quiz_3_3, 30, 'onyomi:RHY_IEM:謙:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 航空 (Hàng Không) đọc là gì?', 'こうくう', 'くうくう', 'きんくう', 'こうくう', 'けんくう', 3, '航空 = 航 (Hàng → こう) + 空 (Không → くう) → こうくう: hàng không. 航 (Hàng) → こう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "航", "hanViet": "Hàng", "reading": "コウ", "romaji": "kou", "focus": "航空"}', @onyomi_quiz_3_4, 25, 'onyomi:RHY_ANG:航:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 往復 (Vãng Phục) đọc là gì?', 'おうふく', 'えんふく', 'おうふく', 'いんふく', 'ううふく', 2, '往復 = 往 (Vãng → おう) + 復 (Phục → ふく) → おうふく: khứ hồi. 往 (Vãng) → おう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "往", "hanViet": "Vãng", "reading": "オウ", "romaji": "ou", "focus": "往復"}', @onyomi_quiz_3_4, 26, 'onyomi:RHY_ANG:往:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 鉱物 (Khoáng Vật) đọc là gì?', 'こうぶつ', 'くうぶつ', 'けんぶつ', 'きんぶつ', 'こうぶつ', 4, '鉱物 = 鉱 (Khoáng → こう) + 物 (Vật → ぶつ) → こうぶつ: khoáng vật. 鉱 (Khoáng) → こう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "鉱", "hanViet": "Khoáng", "reading": "コウ", "romaji": "kou", "focus": "鉱物"}', @onyomi_quiz_3_4, 27, 'onyomi:RHY_ANG:鉱:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 服装 (Phục Trang) đọc là gì?', 'ふくそう', 'ふくしん', 'ふくせん', 'ふくそう', 'ふくすう', 3, '服装 = 服 (Phục → ふく) + 装 (Trang → そう) → ふくそう: trang phục. 装 (Trang) → そう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "装", "hanViet": "Trang", "reading": "ソウ", "romaji": "sou", "focus": "服装"}', @onyomi_quiz_3_4, 28, 'onyomi:RHY_ANG:装:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 冷蔵庫 (Lãnh Tàng Khố) đọc là gì?', 'れいぞうこ', 'れいじんこ', 'れいぜんこ', 'れいずうこ', 'れいぞうこ', 4, '冷蔵庫 = 冷 (Lãnh → れい) + 蔵 (Tàng → ぞう) + 庫 (Khố → こ) → れいぞうこ: tủ lạnh. 蔵 (Tàng) → ぞう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "蔵", "hanViet": "Tàng", "reading": "ゾウ", "romaji": "zou", "focus": "冷蔵庫"}', @onyomi_quiz_3_4, 29, 'onyomi:RHY_ANG:蔵:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 浪費 (Lãng Phí) đọc là gì?', 'ろうひ', 'ろうひ', 'るうひ', 'りんひ', 'れんひ', 1, '浪費 = 浪 (Lãng → ろう) + 費 (Phí → ひ) → ろうひ: lãng phí. 浪 (Lãng) → ろう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "浪", "hanViet": "Lãng", "reading": "ロウ", "romaji": "rou", "focus": "浪費"}', @onyomi_quiz_3_4, 30, 'onyomi:RHY_ANG:浪:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 多忙 (Đa Mang) đọc là gì?', 'たぼう', 'たぶう', 'たぼう', 'たびん', 'たべん', 2, '多忙 = 多 (Đa → た) + 忙 (Mang → ぼう) → たぼう: bận rộn. 忙 (Mang) → ぼう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "忙", "hanViet": "Mang", "reading": "ボウ", "romaji": "bou", "focus": "多忙"}', @onyomi_quiz_3_4, 31, 'onyomi:RHY_ANG:忙:WORD'),
    (@onyomi_rule_rhy_ang, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 抵抗 (Để Kháng) đọc là gì?', 'ていこう', 'ていけん', 'ていくう', 'ていきん', 'ていこう', 4, '抵抗 = 抵 (Để → てい) + 抗 (Kháng → こう) → ていこう: kháng cự. 抗 (Kháng) → こう theo quy tắc Vần ANG → オウ: Vần ANG (kể cả OANG) thường thành trường âm オウ.', '{"type": "WORD", "rule": "RHY_ANG", "kanji": "抗", "hanViet": "Kháng", "reading": "コウ", "romaji": "kou", "focus": "抵抗"}', @onyomi_quiz_3_4, 32, 'onyomi:RHY_ANG:抗:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 数量 (Số Lượng) đọc là gì?', 'すうりょう', 'すうりゅう', 'すうりょう', 'すうりん', 'すうれん', 2, '数量 = 数 (Số → すう) + 量 (Lượng → りょう) → すうりょう: số lượng. 量 (Lượng) → りょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "量", "hanViet": "Lượng", "reading": "リョウ", "romaji": "ryou", "focus": "数量"}', @onyomi_quiz_3_4, 33, 'onyomi:RHY_UONG:量:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N4', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 両親 (Lưỡng Thân) đọc là gì?', 'りょうしん', 'りんしん', 'りゅうしん', 'れんしん', 'りょうしん', 4, '両親 = 両 (Lưỡng → りょう) + 親 (Thân → しん) → りょうしん: bố mẹ. 両 (Lưỡng) → りょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "両", "hanViet": "Lưỡng", "reading": "リョウ", "romaji": "ryou", "focus": "両親"}', @onyomi_quiz_3_4, 34, 'onyomi:RHY_UONG:両:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 将来 (Tương Lai) đọc là gì?', 'しょうらい', 'しゅうらい', 'しんらい', 'せんらい', 'しょうらい', 4, '将来 = 将 (Tương → しょう) + 来 (Lai → らい) → しょうらい: tương lai. 将 (Tương) → しょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "将", "hanViet": "Tương", "reading": "ショウ", "romaji": "shou", "focus": "将来"}', @onyomi_quiz_3_4, 35, 'onyomi:RHY_UONG:将:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 感想 (Cảm Tưởng) đọc là gì?', 'かんそう', 'かんしん', 'かんすう', 'かんそう', 'かんせん', 3, '感想 = 感 (Cảm → かん) + 想 (Tưởng → そう) → かんそう: cảm tưởng. 想 (Tưởng) → そう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "想", "hanViet": "Tưởng", "reading": "ソウ", "romaji": "sou", "focus": "感想"}', @onyomi_quiz_3_4, 36, 'onyomi:RHY_UONG:想:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N5', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 勉強 (Miễn Cường) đọc là gì?', 'べんきょう', 'べんきん', 'べんきゅう', 'べんきょう', 'べんけん', 3, '勉強 = 勉 (Miễn → べん) + 強 (Cường → きょう) → べんきょう: học tập. 強 (Cường) → きょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "強", "hanViet": "Cường", "reading": "キョウ", "romaji": "kyou", "focus": "勉強"}', @onyomi_quiz_3_4, 37, 'onyomi:RHY_UONG:強:WORD');
INSERT INTO assessment_question (conversion_rule_id, knowledge_tag_id, skill, jlpt_level, difficulty,
    question_type, question_text, answer_text, option_1, option_2, option_3, option_4, correct_option,
    explanation, context_json, quiz_id, order_index, title) VALUES
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 故郷 (Cố Hương) đọc là gì?', 'こきょう', 'こきょう', 'こきん', 'こけん', 'こきゅう', 1, '故郷 = 故 (Cố → こ) + 郷 (Hương → きょう) → こきょう: quê hương. 郷 (Hương) → きょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "郷", "hanViet": "Hương", "reading": "キョウ", "romaji": "kyou", "focus": "故郷"}', @onyomi_quiz_3_4, 38, 'onyomi:RHY_UONG:郷:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 故障 (Cố Chướng) đọc là gì?', 'こしょう', 'こしん', 'こせん', 'こしゅう', 'こしょう', 4, '故障 = 故 (Cố → こ) + 障 (Chướng → しょう) → こしょう: hỏng hóc. 障 (Chướng) → しょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "障", "hanViet": "Chướng", "reading": "ショウ", "romaji": "shou", "focus": "故障"}', @onyomi_quiz_3_4, 39, 'onyomi:RHY_UONG:障:WORD'),
    (@onyomi_rule_rhy_uong, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 現象 (Hiện Tượng) đọc là gì?', 'げんしょう', 'げんしょう', 'げんしゅう', 'げんせん', 'げんしん', 1, '現象 = 現 (Hiện → げん) + 象 (Tượng → しょう) → げんしょう: hiện tượng. 象 (Tượng) → しょう theo quy tắc Vần ƯƠNG → オウ: Vần ƯƠNG thường thành trường âm オウ, hay đi với âm ghép (ョウ).', '{"type": "WORD", "rule": "RHY_UONG", "kanji": "象", "hanViet": "Tượng", "reading": "ショウ", "romaji": "shou", "focus": "現象"}', @onyomi_quiz_3_4, 40, 'onyomi:RHY_UONG:象:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 公共 (Công Cộng) đọc là gì?', 'こうきょう', 'こうけん', 'こうきょう', 'こうきゃん', 'こうきん', 2, '公共 = 公 (Công → こう) + 共 (Cộng → きょう) → こうきょう: công cộng. 共 (Cộng) → きょう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "共", "hanViet": "Cộng", "reading": "キョウ", "romaji": "kyou", "focus": "公共"}', @onyomi_quiz_3_4, 41, 'onyomi:RHY_ONG:共:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 紅白 (Hồng Bạch) đọc là gì?', 'こうはく', 'かんはく', 'こうはく', 'けんはく', 'きんはく', 2, '紅白 = 紅 (Hồng → こう) + 白 (Bạch → はく) → こうはく: đỏ và trắng. 紅 (Hồng) → こう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "紅", "hanViet": "Hồng", "reading": "コウ", "romaji": "kou", "focus": "紅白"}', @onyomi_quiz_3_4, 42, 'onyomi:RHY_ONG:紅:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 宗教 (Tông Giáo) đọc là gì?', 'しゅうきょう', 'しゅうきょう', 'せんきょう', 'しんきょう', 'しゃんきょう', 1, '宗教 = 宗 (Tông → しゅう) + 教 (Giáo → きょう) → しゅうきょう: tôn giáo. 宗 (Tông) → しゅう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "宗", "hanViet": "Tông", "reading": "シュウ", "romaji": "shuu", "focus": "宗教"}', @onyomi_quiz_3_4, 43, 'onyomi:RHY_ONG:宗:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 送料 (Tống Liệu) đọc là gì?', 'そうりょう', 'せんりょう', 'さんりょう', 'そうりょう', 'しんりょう', 3, '送料 = 送 (Tống → そう) + 料 (Liệu → りょう) → そうりょう: phí gửi hàng. 送 (Tống) → そう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "送", "hanViet": "Tống", "reading": "ソウ", "romaji": "sou", "focus": "送料"}', @onyomi_quiz_3_4, 44, 'onyomi:RHY_ONG:送:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 貢献 (Cống Hiến) đọc là gì?', 'こうけん', 'こうけん', 'かんけん', 'けんけん', 'きんけん', 1, '貢献 = 貢 (Cống → こう) + 献 (Hiến → けん) → こうけん: cống hiến. 貢 (Cống) → こう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "貢", "hanViet": "Cống", "reading": "コウ", "romaji": "kou", "focus": "貢献"}', @onyomi_quiz_3_4, 45, 'onyomi:RHY_ONG:貢:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 功績 (Công Tích) đọc là gì?', 'こうせき', 'きんせき', 'けんせき', 'かんせき', 'こうせき', 4, '功績 = 功 (Công → こう) + 績 (Tích → せき) → こうせき: công lao. 功 (Công) → こう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "功", "hanViet": "Công", "reading": "コウ", "romaji": "kou", "focus": "功績"}', @onyomi_quiz_3_4, 46, 'onyomi:RHY_ONG:功:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 洪水 (Hồng Thủy) đọc là gì?', 'こうずい', 'けんずい', 'きんずい', 'こうずい', 'かんずい', 3, '洪水 = 洪 (Hồng → こう) + 水 (Thủy → ずい) → こうずい: lũ lụt. 洪 (Hồng) → こう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "洪", "hanViet": "Hồng", "reading": "コウ", "romaji": "kou", "focus": "洪水"}', @onyomi_quiz_3_4, 47, 'onyomi:RHY_ONG:洪:WORD'),
    (@onyomi_rule_rhy_ong, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 冬季 (Đông Quý) đọc là gì?', 'とうき', 'とうき', 'てんき', 'たんき', 'ちんき', 1, '冬季 = 冬 (Đông → とう) + 季 (Quý → き) → とうき: mùa đông. 冬 (Đông) → とう theo quy tắc Vần ÔNG → オウ/ウウ: Vần ÔNG thường thành オウ, đôi khi ウウ.', '{"type": "WORD", "rule": "RHY_ONG", "kanji": "冬", "hanViet": "Đông", "reading": "トウ", "romaji": "tou", "focus": "冬季"}', @onyomi_quiz_3_4, 48, 'onyomi:RHY_ONG:冬:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 清潔 (Thanh Khiết) đọc là gì?', 'せいけつ', 'せいけつ', 'せんけつ', 'すうけつ', 'そうけつ', 1, '清潔 = 清 (Thanh → せい) + 潔 (Khiết → けつ) → せいけつ: sạch sẽ. 清 (Thanh) → せい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "清", "hanViet": "Thanh", "reading": "セイ", "romaji": "sei", "focus": "清潔"}', @onyomi_quiz_3_5, 21, 'onyomi:RHY_ANH:清:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 音声 (Âm Thanh) đọc là gì?', 'おんせい', 'おんそう', 'おんせい', 'おんせん', 'おんすう', 2, '音声 = 音 (Âm → おん) + 声 (Thanh → せい) → おんせい: âm thanh. 声 (Thanh) → せい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "声", "hanViet": "Thanh", "reading": "セイ", "romaji": "sei", "focus": "音声"}', @onyomi_quiz_3_5, 22, 'onyomi:RHY_ANH:声:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 神聖 (Thần Thánh) đọc là gì?', 'しんせい', 'しんせん', 'しんせい', 'しんそう', 'しんすう', 2, '神聖 = 神 (Thần → しん) + 聖 (Thánh → せい) → しんせい: thần thánh. 聖 (Thánh) → せい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "聖", "hanViet": "Thánh", "reading": "セイ", "romaji": "sei", "focus": "神聖"}', @onyomi_quiz_3_5, 23, 'onyomi:RHY_ANH:聖:WORD'),
    (@onyomi_rule_rhy_anh, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 誠実 (Thành Thực) đọc là gì?', 'せいじつ', 'せんじつ', 'そうじつ', 'すうじつ', 'せいじつ', 4, '誠実 = 誠 (Thành → せい) + 実 (Thực → じつ) → せいじつ: thành thật. 誠 (Thành) → せい theo quy tắc Vần ANH → エイ: Vần ANH (kể cả OANH) thường thành エイ.', '{"type": "WORD", "rule": "RHY_ANH", "kanji": "誠", "hanViet": "Thành", "reading": "セイ", "romaji": "sei", "focus": "誠実"}', @onyomi_quiz_3_5, 24, 'onyomi:RHY_ANH:誠:WORD'),
    (@onyomi_rule_rhy_enh, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 歓迎 (Hoan Nghênh) đọc là gì?', 'かんげい', 'かんぐう', 'かんげい', 'かんげん', 'かんごう', 2, '歓迎 = 歓 (Hoan → かん) + 迎 (Nghênh → げい) → かんげい: hoan nghênh. 迎 (Nghênh) → げい theo quy tắc Vần ÊNH → エイ: Vần ÊNH thường thành エイ. Vần này ít chữ, nên gặp là nhớ luôn.', '{"type": "WORD", "rule": "RHY_ENH", "kanji": "迎", "hanViet": "Nghênh", "reading": "ゲイ", "romaji": "gei", "focus": "歓迎"}', @onyomi_quiz_3_5, 25, 'onyomi:RHY_ENH:迎:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 兵士 (Binh Sĩ) đọc là gì?', 'へいし', 'ほうし', 'へいし', 'へんし', 'ふうし', 2, '兵士 = 兵 (Binh → へい) + 士 (Sĩ → し) → へいし: binh sĩ. 兵 (Binh) → へい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "兵", "hanViet": "Binh", "reading": "ヘイ", "romaji": "hei", "focus": "兵士"}', @onyomi_quiz_3_5, 26, 'onyomi:RHY_INH:兵:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 安静 (An Tĩnh) đọc là gì?', 'あんせい', 'あんそう', 'あんせん', 'あんすう', 'あんせい', 4, '安静 = 安 (An → あん) + 静 (Tĩnh → せい) → あんせい: nghỉ ngơi yên tĩnh. 静 (Tĩnh) → せい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "静", "hanViet": "Tĩnh", "reading": "セイ", "romaji": "sei", "focus": "安静"}', @onyomi_quiz_3_5, 27, 'onyomi:RHY_INH:静:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 光栄 (Quang Vinh) đọc là gì?', 'こうえい', 'こううう', 'こうえい', 'こうえん', 'こうおう', 2, '光栄 = 光 (Quang → こう) + 栄 (Vinh → えい) → こうえい: vinh dự. 栄 (Vinh) → えい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "栄", "hanViet": "Vinh", "reading": "エイ", "romaji": "ei", "focus": "光栄"}', @onyomi_quiz_3_5, 28, 'onyomi:RHY_INH:栄:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 水泳 (Thủy Vịnh) đọc là gì?', 'すいえい', 'すいえい', 'すいうう', 'すいおう', 'すいえん', 1, '水泳 = 水 (Thủy → すい) + 泳 (Vịnh → えい) → すいえい: bơi lội. 泳 (Vịnh) → えい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "泳", "hanViet": "Vịnh", "reading": "エイ", "romaji": "ei", "focus": "水泳"}', @onyomi_quiz_3_5, 29, 'onyomi:RHY_INH:泳:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N3', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 家庭 (Gia Đình) đọc là gì?', 'かてい', 'かてい', 'かとう', 'かつう', 'かてん', 1, '家庭 = 家 (Gia → か) + 庭 (Đình → てい) → かてい: gia đình. 庭 (Đình) → てい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "庭", "hanViet": "Đình", "reading": "テイ", "romaji": "tei", "focus": "家庭"}', @onyomi_quiz_3_5, 30, 'onyomi:RHY_INH:庭:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 幽霊 (U Linh) đọc là gì?', 'ゆうれい', 'ゆうれん', 'ゆうろう', 'ゆうれい', 'ゆうるう', 3, '幽霊 = 幽 (U → ゆう) + 霊 (Linh → れい) → ゆうれい: con ma. 霊 (Linh) → れい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "霊", "hanViet": "Linh", "reading": "レイ", "romaji": "rei", "focus": "幽霊"}', @onyomi_quiz_3_5, 31, 'onyomi:RHY_INH:霊:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N1', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 同盟 (Đồng Minh) đọc là gì?', 'どうめい', 'どうもう', 'どうむう', 'どうめい', 'どうめん', 3, '同盟 = 同 (Đồng → どう) + 盟 (Minh → めい) → どうめい: đồng minh. 盟 (Minh) → めい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "盟", "hanViet": "Minh", "reading": "メイ", "romaji": "mei", "focus": "同盟"}', @onyomi_quiz_3_5, 32, 'onyomi:RHY_INH:盟:WORD'),
    (@onyomi_rule_rhy_inh, @onyomi_tag_3, 'KANJI', 'N2', 'MEDIUM', 'MULTIPLE_CHOICE', 'Từ 整理 (Chỉnh Lý) đọc là gì?', 'せいり', 'そうり', 'せんり', 'すうり', 'せいり', 4, '整理 = 整 (Chỉnh → せい) + 理 (Lý → り) → せいり: sắp xếp. 整 (Chỉnh) → せい theo quy tắc Vần INH → エイ: Vần INH thường thành エイ.', '{"type": "WORD", "rule": "RHY_INH", "kanji": "整", "hanViet": "Chỉnh", "reading": "セイ", "romaji": "sei", "focus": "整理"}', @onyomi_quiz_3_5, 33, 'onyomi:RHY_INH:整:WORD');

-- Bài kiểm tra cuối giai đoạn dùng lại câu hỏi luyện tập của các bài trong giai đoạn.
INSERT INTO assessment_quiz_question (quiz_id, question_id, order_index)
SELECT @onyomi_final_1, linked.question_id,
       ROW_NUMBER() OVER (ORDER BY linked.quiz_id, linked.order_index, linked.question_id)
  FROM assessment_quiz_question linked WHERE linked.quiz_id IN (@onyomi_quiz_1_1, @onyomi_quiz_1_2, @onyomi_quiz_1_3, @onyomi_quiz_1_4, @onyomi_quiz_1_5, @onyomi_quiz_1_6, @onyomi_quiz_1_7, @onyomi_quiz_1_8, @onyomi_quiz_1_9, @onyomi_quiz_1_10);
INSERT INTO assessment_quiz_question (quiz_id, question_id, order_index)
SELECT @onyomi_final_2, linked.question_id,
       ROW_NUMBER() OVER (ORDER BY linked.quiz_id, linked.order_index, linked.question_id)
  FROM assessment_quiz_question linked WHERE linked.quiz_id IN (@onyomi_quiz_2_1, @onyomi_quiz_2_2, @onyomi_quiz_2_3, @onyomi_quiz_2_4);
INSERT INTO assessment_quiz_question (quiz_id, question_id, order_index)
SELECT @onyomi_final_3, linked.question_id,
       ROW_NUMBER() OVER (ORDER BY linked.quiz_id, linked.order_index, linked.question_id)
  FROM assessment_quiz_question linked WHERE linked.quiz_id IN (@onyomi_quiz_3_1, @onyomi_quiz_3_2, @onyomi_quiz_3_3, @onyomi_quiz_3_4, @onyomi_quiz_3_5);

-- =============================================================================
-- 5) Dữ liệu mẫu của 2 tài khoản demo (demo@jlab.local / admin@jlab.local)
--    Mọi dòng gắn với @demo_user/@demo_admin và được tra theo khoá tự nhiên,
--    nên chạy lại không nhân đôi và không chạm dữ liệu của tài khoản khác.
-- =============================================================================

-- ── AI hội thoại: một phiên đã chấm điểm ─────────────────────────────────────
INSERT INTO conversation_session
    (user_id, scenario_id, status, overall_score, score_details_json,
     mistakes_count, suggestions, started_at, ended_at, scored_at)
SELECT @demo_user, 1, 'COMPLETED', 86.50,
       '{"grammar":90,"fluency":83,"vocabulary":86}', 1,
       'Ôn lại mẫu câu 〜をください và cách xác nhận đơn hàng.',
       DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY),
       DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY),
       DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM conversation_session WHERE user_id = @demo_user AND scenario_id = 1);

-- ── Tiến độ bài học Onyomi ──────────────────────────────────────────────────
INSERT IGNORE INTO lesson_progress (user_id, lesson_id, status, started_at, completed_at)
VALUES (@demo_user, @onyomi_first_lesson, 'COMPLETED',
        DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 26 HOUR),
        DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY));

-- ── Flashcard: 2 bộ, thẻ, trạng thái học ────────────────────────────────────
INSERT INTO flashcard_set (owner_id, title, description, visibility, moderation_status)
SELECT @demo_user, 'N5 giao tiếp cơ bản', 'Bộ từ vựng dùng trong hội thoại hằng ngày.', 'PUBLIC', 'SAFE'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM flashcard_set WHERE owner_id = @demo_user AND title = 'N5 giao tiếp cơ bản');
INSERT INTO flashcard_set (owner_id, title, description, visibility, moderation_status)
SELECT @demo_user, 'Onyomi cần ôn', 'Bộ riêng để luyện âm On.', 'PRIVATE', 'NOT_SCANNED'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM flashcard_set WHERE owner_id = @demo_user AND title = 'Onyomi cần ôn');
SET @demo_set_1 = (SELECT id FROM flashcard_set WHERE owner_id = @demo_user AND title = 'N5 giao tiếp cơ bản' ORDER BY id LIMIT 1);
SET @demo_set_2 = (SELECT id FROM flashcard_set WHERE owner_id = @demo_user AND title = 'Onyomi cần ôn' ORDER BY id LIMIT 1);

INSERT IGNORE INTO flashcard_card
    (set_id, front, back, reading, han_viet, meaning, jlpt_level, word_type, example_ja, example_vi, position)
VALUES
    (@demo_set_1, '学校', 'trường học', 'がっこう', 'HỌC HIỆU', 'trường học', 'N5', 'NOUN', '学校へ行きます。', 'Tôi đi đến trường.', 1),
    (@demo_set_1, '先生', 'giáo viên', 'せんせい', 'TIÊN SINH', 'giáo viên', 'N5', 'NOUN', '先生に聞きます。', 'Tôi hỏi giáo viên.', 2),
    (@demo_set_1, 'お願いします', 'làm ơn / nhờ bạn', 'おねがいします', NULL, 'làm ơn', 'N5', 'PHRASE', 'これをお願いします。', 'Làm ơn cho tôi món này.', 3),
    (@demo_set_2, '海外', 'nước ngoài', 'かいがい', 'HẢI NGOẠI', 'nước ngoài', 'N4', 'NOUN', '海外で働きます。', 'Tôi làm việc ở nước ngoài.', 1);
SET @demo_card_1 = (SELECT id FROM flashcard_card WHERE set_id = @demo_set_1 AND position = 1);
SET @demo_card_2 = (SELECT id FROM flashcard_card WHERE set_id = @demo_set_1 AND position = 2);
SET @demo_card_3 = (SELECT id FROM flashcard_card WHERE set_id = @demo_set_1 AND position = 3);

INSERT IGNORE INTO flashcard_user_set_state
    (user_id, set_id, is_favorite, rating, first_completed_at, last_accessed_at)
VALUES
    (@demo_user, @demo_set_1, 1, 5, DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 3 DAY), CURRENT_TIMESTAMP(6)),
    (@demo_user, @demo_set_2, 0, 4, NULL, CURRENT_TIMESTAMP(6));

INSERT IGNORE INTO flashcard_card_progress
    (user_id, card_id, status, review_count, last_quality, easiness_factor,
     repetition_count, interval_days, last_reviewed_at, next_review_at, is_starred)
VALUES
    (@demo_user, @demo_card_1, 'MASTERED', 3, 5, 2.60, 3, 16, DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY), DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 14 DAY), 1),
    (@demo_user, @demo_card_2, 'LEARNING', 1, 3, 2.30, 1, 1, DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY), CURRENT_TIMESTAMP(6), 0),
    (@demo_user, @demo_card_3, 'NEW', 0, NULL, 2.50, 0, 0, NULL, CURRENT_TIMESTAMP(6), 0);

INSERT IGNORE INTO flashcard_study_session
    (user_id, set_id, creation_request_id, session_type, mode, status, started_at, finished_at)
VALUES
    (@demo_user, @demo_set_1, 'seed-demo-session-1', 'FLASHCARD', 'KANJI', 'COMPLETED',
     DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY), DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY));
SET @demo_session = (SELECT id FROM flashcard_study_session WHERE user_id = @demo_user AND creation_request_id = 'seed-demo-session-1');

INSERT IGNORE INTO flashcard_study_session_item
    (session_id, card_id, chosen_card_id, position, is_wrong_review, is_correct, answered_at)
VALUES
    (@demo_session, @demo_card_1, @demo_card_1, 1, 0, 1, DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY)),
    (@demo_session, @demo_card_2, @demo_card_2, 2, 0, 0, DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY));

INSERT INTO content_comment (target_type, target_id, user_id, content, is_hidden)
SELECT 'FLASHCARD_SET', @demo_set_1, @demo_user, 'Bộ từ vựng dễ dùng, phần ví dụ rất hữu ích.', 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM content_comment
                   WHERE target_type = 'FLASHCARD_SET' AND target_id = @demo_set_1 AND user_id = @demo_user);

-- ── Shadowing: video, phụ đề, tag, trạng thái xem, bình luận ────────────────
INSERT INTO video
    (owner_id, title, video_path, source_url, duration_seconds, status,
     processing_step, progress_percent, target_language, visibility,
     moderation_status, moderation_summary)
SELECT @demo_user, 'Luyện nghe gọi món ramen', 'videos/demo-ramen.mp4',
       'https://example.com/demo-ramen', 42.500, 'DRAFT', 'DONE', 100,
       'vi', 'PRIVATE', 'SAFE', 'Archived demo fixture; no playable media; excluded from public library'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM video WHERE owner_id = @demo_user AND title = 'Luyện nghe gọi món ramen');
SET @demo_video = (SELECT id FROM video WHERE owner_id = @demo_user AND title = 'Luyện nghe gọi món ramen' ORDER BY id LIMIT 1);

INSERT IGNORE INTO subtitle (video_id, start_time, end_time, text, translation, sort_order, ai_status) VALUES
    (@demo_video, 0.000, 3.500, 'いらっしゃいませ。', 'Xin chào quý khách.', 1, 'COMPLETED'),
    (@demo_video, 3.500, 7.000, '何を注文しますか。', 'Bạn muốn gọi món gì?', 2, 'COMPLETED');
SET @demo_sub_1 = (SELECT id FROM subtitle WHERE video_id = @demo_video AND sort_order = 1);
SET @demo_sub_2 = (SELECT id FROM subtitle WHERE video_id = @demo_video AND sort_order = 2);

INSERT IGNORE INTO video_tag (video_id, tag_id)
SELECT @demo_video, id FROM tag WHERE code IN ('VIDEO-N5', 'VIDEO-RESTAURANT', 'VIDEO-LISTENING');

INSERT IGNORE INTO video_user_state
    (user_id, video_id, max_position_sec, counted_as_view, rating, is_favorite, last_watched_at)
VALUES (@demo_user, @demo_video, 38.200, 1, 5, 1, CURRENT_TIMESTAMP(6));

INSERT INTO content_comment (target_type, target_id, user_id, content, is_hidden)
SELECT 'VIDEO', @demo_video, @demo_user, 'Âm thanh rõ và câu thoại vừa sức N5.', 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM content_comment
                   WHERE target_type = 'VIDEO' AND target_id = @demo_video AND user_id = @demo_user);

INSERT IGNORE INTO shadowing_exercise_analysis
    (subtitle_id, source_hash, prompt_version, vocabulary_factor, grammar_factor,
     listening_speed_factor, script_complexity_factor, listening_traps_factor,
     sentence_difficulty, analysis_source)
VALUES
    (@demo_sub_1, SHA2(CONCAT('いらっしゃいませ。', CHAR(31), '', CHAR(31), '0.0', CHAR(31), '3.5'), 256),
     'cloze-v1', 0, 0, 1, 0, 0, 12, 'SEED'),
    (@demo_sub_2, SHA2(CONCAT('何を注文しますか。', CHAR(31), '', CHAR(31), '3.5', CHAR(31), '7.0'), 256),
     'cloze-v1', 1, 1, 1, 1, 0, 31, 'SEED');

INSERT IGNORE INTO shadowing_cloze_candidate
    (analysis_id, candidate_order, answer_text, reading_text, occurrence_index,
     candidate_type, jlpt_level, importance_factor, grammar_complexity_factor,
     context_predictability_factor, acoustic_confusability_factor, confidence_factor,
     difficulty_score, generation_source)
SELECT a.id, 0, c.answer_text, c.reading_text, 1, c.candidate_type, c.jlpt_level, c.importance, 1, 2, 1, 4, c.score, 'SEED'
FROM (
    SELECT @demo_sub_1 AS subtitle_id, 'ませ' AS answer_text, 'ませ' AS reading_text,
           'GRAMMAR' AS candidate_type, 'N5' AS jlpt_level, 3 AS importance, 20 AS score
    UNION ALL
    SELECT @demo_sub_2, '注文', 'ちゅうもん', 'VOCABULARY', 'N4', 4, 39
) c
JOIN shadowing_exercise_analysis a ON a.subtitle_id = c.subtitle_id;

-- ── Dịch câu, kiểm duyệt, kháng nghị, hạn mức AI ────────────────────────────
INSERT IGNORE INTO sentence_translation (source_hash, target_language, source_text, translated_text)
VALUES (SHA2('学校へ行きます。', 256), 'vi', '学校へ行きます。', 'Tôi đi đến trường.');

INSERT INTO content_moderation_log
    (target_type, target_id, owner_user_id, content_hash, source, verdict,
     primary_category, categories_json, confidence, risk_score, model_name, summary, decision_reason)
SELECT 'VIDEO', @demo_video, @demo_user,
       'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
       'TEXT', 'SAFE', 'SAFE', '["SAFE"]', 0.9900, 1, 'demo-moderation',
       'Sample content passed moderation.', 'No unsafe content detected.'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM content_moderation_log
                   WHERE target_type = 'VIDEO' AND target_id = @demo_video AND model_name = 'demo-moderation');
SET @demo_modlog = (SELECT id FROM content_moderation_log
                     WHERE target_type = 'VIDEO' AND target_id = @demo_video AND model_name = 'demo-moderation'
                     ORDER BY id LIMIT 1);

INSERT IGNORE INTO moderation_policy (policy_type, policy_key, language, action, config_json, is_active, updated_by) VALUES
    ('TERM',      'blocked_demo_term', 'vi',  'REVIEW', '{"reason":"sample policy"}', 1, @demo_admin),
    ('THRESHOLD', 'comment_risk',      'ALL', 'REVIEW', '{"risk_score":70}',         1, @demo_admin);

INSERT INTO review_case
    (case_type, target_type, target_id, moderation_log_id, requester_user_id,
     owner_user_id, title_snapshot, reason, status)
SELECT 'CONTENT_REVIEW', 'VIDEO', @demo_video, @demo_modlog, @demo_user, @demo_user,
       'Luyện nghe gọi món ramen', 'Sample review queue case.', 'OPEN'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM review_case
                   WHERE case_type = 'CONTENT_REVIEW' AND target_type = 'VIDEO' AND target_id = @demo_video);

INSERT INTO user_sanction (user_id, created_by, sanction_type, reason, starts_at, ends_at, revoked_at)
SELECT @demo_user, @demo_admin, 'MUTE', 'Historical sample sanction',
       DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 10 DAY),
       DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 7 DAY),
       DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 8 DAY)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM user_sanction WHERE user_id = @demo_user AND reason = 'Historical sample sanction');
SET @demo_sanction = (SELECT id FROM user_sanction
                       WHERE user_id = @demo_user AND reason = 'Historical sample sanction' ORDER BY id LIMIT 1);

INSERT INTO review_case
    (case_type, target_type, target_id, moderation_log_id, requester_user_id,
     owner_user_id, title_snapshot, reason, status)
SELECT 'APPEAL', 'USER_SANCTION', @demo_sanction, NULL, @demo_user, @demo_user,
       'Demo appeal', 'Mẫu đơn kháng nghị để test màn hình appeal.', 'OPEN'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM review_case
                   WHERE case_type = 'APPEAL' AND target_type = 'USER_SANCTION' AND target_id = @demo_sanction);

INSERT IGNORE INTO ai_usage_daily (user_id, usage_date, minutes_used) VALUES
    (@demo_user, CURRENT_DATE, 4.500),
    (@demo_user, DATE_SUB(CURRENT_DATE, INTERVAL 1 DAY), 7.250);

-- ── Ví CP: tổng 52 CP, khớp credit_balance của demo@jlab.local ──────────────
INSERT INTO user_credit_transaction (user_id, amount, reason, reference_type, reference_id, note)
SELECT @demo_user, t.amount, t.reason, t.reference_type, t.reference_id, t.note
FROM (
    SELECT 15 AS amount, 'STUDY_REWARD' AS reason, 'FLASHCARD_SET' AS reference_type,
           @demo_set_1 AS reference_id, 'Sample study reward' AS note
    UNION ALL SELECT 2, 'CREATOR_REWARD', 'VIDEO', @demo_video, 'Sample video view reward'
    UNION ALL SELECT 5, 'DAILY_CHECKIN', 'DAILY_MISSION',
                     (SELECT id FROM mission_definition WHERE code = 'DAILY_CHECK_IN'), 'Sample daily check-in reward'
    UNION ALL SELECT 30, 'STREAK_MILESTONE_REWARD', 'STREAK_MILESTONE',
                     (SELECT id FROM mission_definition WHERE code = 'STREAK_3'), 'Sample 3-day streak reward'
) t
WHERE NOT EXISTS (SELECT 1 FROM user_credit_transaction x
                   WHERE x.user_id = @demo_user AND x.note = t.note COLLATE utf8mb4_unicode_ci);

-- ── Mục tiêu và chuỗi ngày: chuỗi 4 ngày, hôm nay đang học dở ───────────────
-- Nhiệm vụ hằng ngày (user_mission) do ứng dụng tự tạo nên không seed ở đây.
INSERT IGNORE INTO user_learning_targets (user_id, daily_vocab_target, daily_quiz_target, daily_study_minutes)
VALUES (@demo_user, 15, 2, 20);

INSERT IGNORE INTO user_daily_progress
    (user_id, progress_date, vocabulary_learned, quizzes_completed, study_minutes, target_completed_at)
VALUES
    (@demo_user, CURRENT_DATE, 12, 1, 18, NULL),
    (@demo_user, DATE_SUB(CURRENT_DATE, INTERVAL 1 DAY), 20, 2, 30, DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY));

INSERT IGNORE INTO user_streaks
    (user_id, current_streak, longest_streak, last_completed_date, freeze_count, last_freeze_used_date)
VALUES (@demo_user, 4, 7, DATE_SUB(CURRENT_DATE, INTERVAL 1 DAY), 1, DATE_SUB(CURRENT_DATE, INTERVAL 4 DAY));

-- ── Game: một trận Memory Match, kết quả Air Defense, tàu và nâng cấp ───────
INSERT INTO game_match
    (flashcard_set_id, room_id, room_code, game_type, status, started_at, ended_at, winner_user_id, settings_snapshot)
SELECT @demo_set_1, 'demo-room-1', 'DEMO01', 'MEMORY_MATCH', 'COMPLETED',
       DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY), DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY),
       @demo_user, '{"boardSize":4,"level":"N5"}'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM game_match WHERE room_id = 'demo-room-1');
SET @demo_match = (SELECT match_id FROM game_match WHERE room_id = 'demo-room-1' ORDER BY match_id LIMIT 1);

INSERT IGNORE INTO game_match_player
    (match_id, user_id, display_name, slot, team, score, result, joined_at, disconnected)
VALUES
    (@demo_match, @demo_user,  'Demo Learner',       1, NULL, 920, 'WIN',  DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY), 0),
    (@demo_match, @demo_admin, 'Demo Administrator', 2, NULL, 740, 'LOSE', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY), 0);

INSERT IGNORE INTO memory_match_result
    (session_id, match_id, room_id, user_id, play_mode, objective, pair_mode,
     jlpt_level, outcome, board_size, pairs_found, mistakes, moves, best_streak,
     accuracy_percent, average_decision_ms, duration_ms, ranked, winner, finished_at)
VALUES
    ('memory-demo-1', @demo_match, 'demo-room-1', @demo_user, 'MULTIPLAYER', 'CLASSIC', 'KANJI_TO_MEANING',
     'N5', 'CLEARED', 4, 4, 2, 12, 3, 83, 1250, 48000, 1, 1, DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 DAY));

INSERT IGNORE INTO air_defense_spaceships
    (ship_id, name, role, description, price_coins, base_hp, speed_mult, passive_skill_code, color_theme)
VALUES
    ('NOVA-01',  'Nova',  'BALANCED', 'Tàu khởi đầu cân bằng.', 0,   100, 1.00, 'STARTER',    'cyan'),
    ('BOLT-02',  'Bolt',  'SPEED',    'Tàu tốc độ cao.',        250, 80,  1.25, 'FAST_START', 'yellow'),
    ('TITAN-03', 'Titan', 'TANK',     'Tàu có nhiều máu.',      400, 150, 0.85, 'EXTRA_HP',   'red');

INSERT IGNORE INTO air_defense_result
    (session_id, match_id, room_id, user_id, play_mode, objective, difficulty,
     answer_mode, jlpt_level, outcome, hp_remaining, score, questions_answered,
     correct_answers, incorrect_answers, accuracy_percent, best_combo, average_response_ms,
     duration_ms, ranked, winner, finished_at)
VALUES
    ('air-demo-1', NULL, NULL, @demo_user, 'SOLO', 'SURVIVAL', 'NORMAL', 'KANJI_TO_MEANING',
     'N5', 'CLEARED', 72, 860, 10, 8, 2, 80, 4, 2100, 90000, 0, 1, DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY));

INSERT IGNORE INTO user_spaceships (user_id, ship_id, purchased_at) VALUES
    (@demo_user, 'NOVA-01', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 5 DAY)),
    (@demo_user, 'BOLT-02', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY));

INSERT IGNORE INTO user_permanent_upgrades
    (user_id, coins_balance, extra_base_hp_level, coin_bonus_level, reroll_count_level, fast_start_level, equipped_ship_id)
VALUES (@demo_user, 120, 1, 1, 0, 0, 'BOLT-02');

-- =============================================================================
-- 6) Seed của nhánh update: 5 bộ flashcard + dữ liệu trang phân tích (Stitch)
-- =============================================================================
-- Demo seed: 5 bo flashcard cho tai khoan huyphw2@gmail.com.
-- Chi chay tren database da co bang users va cac bang flashcard.
-- File co the chay lai: cac bo demo cu duoc nhan dien boi moderation_summary.

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

START TRANSACTION;

SET @demo_email = 'huyphw2@gmail.com';
SET @demo_user_id = (
    SELECT id
    FROM users
    WHERE email COLLATE utf8mb4_unicode_ci = @demo_email COLLATE utf8mb4_unicode_ci
    LIMIT 1
);

-- Không có tài khoản này (DB mới): dùng tài khoản demo thay thế.
SET @demo_user_id = COALESCE(@demo_user_id, (SELECT id FROM users WHERE email = 'demo@jlab.local'));
SELECT @demo_user_id AS demo_user_id, @demo_email AS demo_email;

-- Thu muc rieng cho 5 bo demo.
INSERT INTO flashcard_folder (owner_id, name)
VALUES (@demo_user_id, 'Demo Nhat ngu')
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    updated_at = CURRENT_TIMESTAMP(6);
SET @demo_folder_id = LAST_INSERT_ID();

-- Xoa cac bo demo cu. Cac bang phu thuoc se tu dong cascade theo flashcard_set.
DELETE FROM flashcard_set
WHERE owner_id = @demo_user_id
  AND moderation_summary COLLATE utf8mb4_unicode_ci =
      'DEMO_FLASHCARD_5SETS' COLLATE utf8mb4_unicode_ci;

INSERT INTO flashcard_set (
    owner_id, folder_id, title, description, visibility, is_draft,
    moderation_status, moderation_checked_at, moderation_summary
)
VALUES (
    @demo_user_id, @demo_folder_id,
    'Hiragana N5 - Co ban',
    'Lam quen 46 am Hiragana thong dung cho nguoi moi bat dau.',
    'PUBLIC', 0, 'SAFE', CURRENT_TIMESTAMP(6), 'DEMO_FLASHCARD_5SETS'
);
SET @set_hiragana = LAST_INSERT_ID();

INSERT INTO flashcard_set (
    owner_id, folder_id, title, description, visibility, is_draft,
    moderation_status, moderation_checked_at, moderation_summary
)
VALUES (
    @demo_user_id, @demo_folder_id,
    'Katakana N5 - Co ban',
    'Bo the luyen doc Katakana va cac tu vay muon quen thuoc.',
    'PUBLIC', 0, 'SAFE', CURRENT_TIMESTAMP(6), 'DEMO_FLASHCARD_5SETS'
);
SET @set_katakana = LAST_INSERT_ID();

INSERT INTO flashcard_set (
    owner_id, folder_id, title, description, visibility, is_draft,
    moderation_status, moderation_checked_at, moderation_summary
)
VALUES (
    @demo_user_id, @demo_folder_id,
    'Dong tu N5 thiet yeu',
    'Nhung dong tu can thiet cho giao tiep va bai hoc N5.',
    'PUBLIC', 0, 'SAFE', CURRENT_TIMESTAMP(6), 'DEMO_FLASHCARD_5SETS'
);
SET @set_verbs = LAST_INSERT_ID();

INSERT INTO flashcard_set (
    owner_id, folder_id, title, description, visibility, is_draft,
    moderation_status, moderation_checked_at, moderation_summary
)
VALUES (
    @demo_user_id, @demo_folder_id,
    'Tinh tu N4 thuong gap',
    'Tinh tu mo ta nguoi, do vat va cam nhan trong tieng Nhat.',
    'PUBLIC', 0, 'SAFE', CURRENT_TIMESTAMP(6), 'DEMO_FLASHCARD_5SETS'
);
SET @set_adjectives = LAST_INSERT_ID();

INSERT INTO flashcard_set (
    owner_id, folder_id, title, description, visibility, is_draft,
    moderation_status, moderation_checked_at, moderation_summary
)
VALUES (
    @demo_user_id, @demo_folder_id,
    'Hoi thoai du lich Nhat Ban',
    'Cau giao tiep thuc te khi di lai, mua sam va goi mon o Nhat.',
    'PUBLIC', 0, 'SAFE', CURRENT_TIMESTAMP(6), 'DEMO_FLASHCARD_5SETS'
);
SET @set_travel = LAST_INSERT_ID();

INSERT INTO flashcard_card (
    set_id, front, back, reading, meaning, han_viet, jlpt_level, word_type,
    example_ja, example_vi, position
)
VALUES
    (@set_hiragana, 'あ', 'a', 'あ', 'Am a', NULL, 'N5', 'Am Hiragana', 'あさです。', 'Buoi sang.', 1),
    (@set_hiragana, 'い', 'i', 'い', 'Am i', NULL, 'N5', 'Am Hiragana', 'いえへ かえります。', 'Toi ve nha.', 2),
    (@set_hiragana, 'う', 'u', 'う', 'Am u', NULL, 'N5', 'Am Hiragana', 'うみが すきです。', 'Toi thich bien.', 3),
    (@set_hiragana, 'え', 'e', 'え', 'Am e', NULL, 'N5', 'Am Hiragana', 'えきは どこですか。', 'Ga o dau?', 4),
    (@set_hiragana, 'お', 'o', 'お', 'Am o', NULL, 'N5', 'Am Hiragana', 'おはようございます。', 'Chao buoi sang.', 5),
    (@set_hiragana, 'か', 'ka', 'か', 'Am ka', NULL, 'N5', 'Am Hiragana', 'かさを もちます。', 'Mang theo o.', 6),
    (@set_hiragana, 'き', 'ki', 'き', 'Am ki', NULL, 'N5', 'Am Hiragana', 'きょうは あついです。', 'Hom nay nong.', 7),
    (@set_hiragana, 'く', 'ku', 'く', 'Am ku', NULL, 'N5', 'Am Hiragana', 'くるまが あります。', 'Co mot chiec o to.', 8),

    (@set_katakana, 'ア', 'a', 'ア', 'Am a', NULL, 'N5', 'Am Katakana', 'アメリカへ いきます。', 'Toi di My.', 1),
    (@set_katakana, 'イ', 'i', 'イ', 'Am i', NULL, 'N5', 'Am Katakana', 'イタリアの りょうりです。', 'Mon an Y cua Italy.', 2),
    (@set_katakana, 'ウ', 'u', 'ウ', 'Am u', NULL, 'N5', 'Am Katakana', 'ウイルスに きをつけます。', 'Hay can than virus.', 3),
    (@set_katakana, 'エ', 'e', 'エ', 'Am e', NULL, 'N5', 'Am Katakana', 'エレベーターは あちらです。', 'Thang may o phia kia.', 4),
    (@set_katakana, 'オ', 'o', 'オ', 'Am o', NULL, 'N5', 'Am Katakana', 'オレンジを たべます。', 'Toi an cam.', 5),
    (@set_katakana, 'カ', 'ka', 'カ', 'Am ka', NULL, 'N5', 'Am Katakana', 'カメラを かいました。', 'Toi da mua may anh.', 6),
    (@set_katakana, 'キ', 'ki', 'キ', 'Am ki', NULL, 'N5', 'Am Katakana', 'キロメートルを はかります。', 'Do kilomet.', 7),
    (@set_katakana, 'ク', 'ku', 'ク', 'Am ku', NULL, 'N5', 'Am Katakana', 'クラスは 3かいです。', 'Lop hoc o tang 3.', 8),

    (@set_verbs, '食べる', 'an', 'たべる', 'An', 'Thuc', 'N5', 'Dong tu', '毎朝、パンを食べます。', 'Moi sang toi an banh mi.', 1),
    (@set_verbs, '飲む', 'uong', 'のむ', 'Uong', 'Am', 'N5', 'Dong tu', '水を飲んでください。', 'Hay uong nuoc.', 2),
    (@set_verbs, '行く', 'di', 'いく', 'Di', 'Hanh', 'N5', 'Dong tu', '学校へ行きます。', 'Toi di den truong.', 3),
    (@set_verbs, '来る', 'den', 'くる', 'Den', 'Lai', 'N5', 'Dong tu', '友だちが来ます。', 'Ban toi se den.', 4),
    (@set_verbs, '見る', 'xem, nhin', 'みる', 'Xem, nhin', 'Kien', 'N5', 'Dong tu', '映画を見ます。', 'Toi xem phim.', 5),
    (@set_verbs, '読む', 'doc', 'よむ', 'Doc', 'Doc', 'N5', 'Dong tu', '本を読みます。', 'Toi doc sach.', 6),
    (@set_verbs, '書く', 'viet', 'かく', 'Viet', 'Thu', 'N5', 'Dong tu', '名前を書きます。', 'Toi viet ten.', 7),
    (@set_verbs, '話す', 'noi', 'はなす', 'Noi, noi chuyen', 'Thoai', 'N5', 'Dong tu', '日本語を話します。', 'Toi noi tieng Nhat.', 8),

    (@set_adjectives, '大きい', 'lon', 'おおきい', 'Lon', 'Dai', 'N4', 'Tinh tu i', '大きい犬がいます。', 'Co mot con cho lon.', 1),
    (@set_adjectives, '小さい', 'nho', 'ちいさい', 'Nho', 'Tieu', 'N4', 'Tinh tu i', '小さいかばんです。', 'Day la chiec tui nho.', 2),
    (@set_adjectives, '新しい', 'moi', 'あたらしい', 'Moi', 'Tan', 'N4', 'Tinh tu i', '新しい本を買いました。', 'Toi da mua sach moi.', 3),
    (@set_adjectives, '古い', 'cu', 'ふるい', 'Cu', 'Co', 'N4', 'Tinh tu i', '古い町を歩きます。', 'Toi di bo trong pho co.', 4),
    (@set_adjectives, '高い', 'cao, dat', 'たかい', 'Cao, dat', 'Cao', 'N4', 'Tinh tu i', 'この店は少し高いです。', 'Cua hang nay hoi dat.', 5),
    (@set_adjectives, '安い', 're', 'やすい', 'Re', 'An', 'N4', 'Tinh tu i', 'このりんごは安いです。', 'Tao nay re.', 6),
    (@set_adjectives, '面白い', 'thu vi', 'おもしろい', 'Thu vi', 'Dien Bach', 'N4', 'Tinh tu i', '面白い映画でした。', 'Do la bo phim thu vi.', 7),
    (@set_adjectives, '難しい', 'kho', 'むずかしい', 'Kho', 'Nan', 'N4', 'Tinh tu i', 'この問題は難しいです。', 'Cau hoi nay kho.', 8),

    (@set_travel, 'こんにちは', 'xin chao', 'こんにちは', 'Xin chao', NULL, 'N5', 'Chao hoi', 'こんにちは、お元気ですか。', 'Xin chao, ban khoe khong?', 1),
    (@set_travel, 'ありがとうございます', 'cam on rat nhieu', 'ありがとうございます', 'Cam on rat nhieu', NULL, 'N5', 'Bieu cam', 'ありがとうございます。助かりました。', 'Cam on, ban da giup toi.', 2),
    (@set_travel, 'すみません', 'xin loi, xin phep', 'すみません', 'Xin loi, xin phep', NULL, 'N5', 'Bieu cam', 'すみません、駅はどこですか。', 'Xin loi, ga o dau?', 3),
    (@set_travel, '駅はどこですか', 'Ga o dau?', 'えきはどこですか', 'Hoi duong den ga', NULL, 'N5', 'Cau hoi', '駅はどこですか。', 'Ga o dau?', 4),
    (@set_travel, 'これをください', 'Cho toi cai nay', 'これをください', 'Cho toi cai nay', NULL, 'N5', 'Cau yeu cau', 'これをください。', 'Cho toi cai nay.', 5),
    (@set_travel, 'お願いします', 'Lam on, nho ban', 'おねがいします', 'Lam on, nho ban', NULL, 'N5', 'Cau yeu cau', 'よろしくお願いします。', 'Rat mong ban giup do.', 6),
    (@set_travel, 'いくらですか', 'Bao nhieu tien?', 'いくらですか', 'Hoi gia', NULL, 'N5', 'Cau hoi', 'これはいくらですか。', 'Cai nay bao nhieu tien?', 7),
    (@set_travel, 'トイレはどこですか', 'Nha ve sinh o dau?', 'トイレはどこですか', 'Hoi vi tri nha ve sinh', NULL, 'N5', 'Cau hoi', 'トイレはどこですか。', 'Nha ve sinh o dau?', 8);

-- Tao tien do mau: 3 the dau da hoc, 2 the dang hoc, cac the con lai moi.
INSERT INTO flashcard_card_progress (
    user_id, card_id, status, review_count, last_quality,
    easiness_factor, repetition_count, interval_days,
    last_reviewed_at, next_review_at, is_starred
)
SELECT
    @demo_user_id,
    card.id,
    CASE
        WHEN card.position <= 3 THEN 'MASTERED'
        WHEN card.position <= 5 THEN 'LEARNING'
        ELSE 'NEW'
    END,
    CASE WHEN card.position <= 5 THEN 2 ELSE 0 END,
    CASE WHEN card.position <= 3 THEN 5 WHEN card.position <= 5 THEN 2 ELSE NULL END,
    2.50,
    CASE WHEN card.position <= 3 THEN 3 WHEN card.position <= 5 THEN 1 ELSE 0 END,
    CASE WHEN card.position <= 3 THEN 6 WHEN card.position <= 5 THEN 1 ELSE 0 END,
    CASE WHEN card.position <= 5 THEN DATE_SUB(NOW(6), INTERVAL 1 DAY) ELSE NULL END,
    CASE WHEN card.position <= 3 THEN DATE_ADD(NOW(6), INTERVAL 5 DAY)
         WHEN card.position <= 5 THEN DATE_ADD(NOW(6), INTERVAL 1 DAY)
         ELSE NOW(6) END,
    CASE WHEN card.position = 1 THEN 1 ELSE 0 END
FROM flashcard_card card
WHERE card.set_id IN (@set_hiragana, @set_katakana, @set_verbs, @set_adjectives, @set_travel);

-- Trang thai, danh gia va thoi diem truy cap de thu cac bo loc trong thu vien.
INSERT INTO flashcard_user_set_state (
    user_id, set_id, is_favorite, rating, first_completed_at, last_accessed_at
)
VALUES
    (@demo_user_id, @set_hiragana, 1, 5, DATE_SUB(NOW(6), INTERVAL 6 DAY), DATE_SUB(NOW(6), INTERVAL 1 DAY)),
    (@demo_user_id, @set_katakana, 1, 4, DATE_SUB(NOW(6), INTERVAL 5 DAY), DATE_SUB(NOW(6), INTERVAL 2 DAY)),
    (@demo_user_id, @set_verbs, 0, 5, DATE_SUB(NOW(6), INTERVAL 4 DAY), DATE_SUB(NOW(6), INTERVAL 3 DAY)),
    (@demo_user_id, @set_adjectives, 0, 4, DATE_SUB(NOW(6), INTERVAL 3 DAY), DATE_SUB(NOW(6), INTERVAL 4 DAY)),
    (@demo_user_id, @set_travel, 1, 5, DATE_SUB(NOW(6), INTERVAL 2 DAY), NOW(6));

-- Lich su hoc mau de cot "Hoc nhieu nhat" co du lieu hien thi.
INSERT INTO flashcard_study_session (
    user_id, set_id, session_type, mode, status, started_at, finished_at
)
VALUES
    (@demo_user_id, @set_hiragana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 10 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 10 DAY), INTERVAL 8 MINUTE)),
    (@demo_user_id, @set_hiragana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 7 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 7 DAY), INTERVAL 9 MINUTE)),
    (@demo_user_id, @set_hiragana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 6 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 6 DAY), INTERVAL 7 MINUTE)),
    (@demo_user_id, @set_hiragana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 3 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 3 DAY), INTERVAL 8 MINUTE)),
    (@demo_user_id, @set_hiragana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 1 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 1 DAY), INTERVAL 10 MINUTE)),
    (@demo_user_id, @set_katakana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 9 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 9 DAY), INTERVAL 8 MINUTE)),
    (@demo_user_id, @set_katakana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 5 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 5 DAY), INTERVAL 10 MINUTE)),
    (@demo_user_id, @set_katakana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 2 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 2 DAY), INTERVAL 8 MINUTE)),
    (@demo_user_id, @set_katakana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 1 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 1 DAY), INTERVAL 9 MINUTE)),
    (@demo_user_id, @set_verbs, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 8 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 8 DAY), INTERVAL 12 MINUTE)),
    (@demo_user_id, @set_verbs, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 4 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 4 DAY), INTERVAL 11 MINUTE)),
    (@demo_user_id, @set_verbs, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 2 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 2 DAY), INTERVAL 10 MINUTE)),
    (@demo_user_id, @set_adjectives, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 7 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 7 DAY), INTERVAL 9 MINUTE)),
    (@demo_user_id, @set_adjectives, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 3 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 3 DAY), INTERVAL 10 MINUTE)),
    (@demo_user_id, @set_travel, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 1 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 1 DAY), INTERVAL 12 MINUTE));

COMMIT;

-- Ket qua mong doi: 5 dong bo va 40 dong the.
SELECT id, title, visibility, moderation_status,
       (SELECT COUNT(*) FROM flashcard_card card WHERE card.set_id = fs.id) AS total_cards
FROM flashcard_set fs
WHERE fs.owner_id = @demo_user_id
  AND fs.moderation_summary COLLATE utf8mb4_unicode_ci =
      'DEMO_FLASHCARD_5SETS' COLLATE utf8mb4_unicode_ci
ORDER BY fs.id;

-- Demo data for the Stitch learning analytics page.
-- Target account: huyphw2@gmail.com
-- MySQL 8.x. Run this after jlas_schema.sql and the user account exists.

START TRANSACTION;

SET @demo_email = CONVERT('huyphw2@gmail.com' USING utf8mb4) COLLATE utf8mb4_unicode_ci;
SET @demo_today = CURRENT_DATE();
SET @demo_user_id = (
    SELECT id
    FROM users
    WHERE email COLLATE utf8mb4_unicode_ci = @demo_email COLLATE utf8mb4_unicode_ci
    LIMIT 1
);

-- Không có tài khoản này (DB mới): dùng tài khoản demo thay thế.
SET @demo_user_id = COALESCE(@demo_user_id, (SELECT id FROM users WHERE email = 'demo@jlab.local'));
-- Stop instead of writing to an unexpected account when the email is missing.
SET @demo_user_exists = IF(@demo_user_id IS NULL, 0, 1);
SET @demo_user_id = IF(@demo_user_exists = 1, @demo_user_id, 0);

INSERT INTO user_learning_targets (
    user_id, daily_vocab_target, daily_quiz_target, daily_study_minutes
)
SELECT @demo_user_id, 15, 1, 20
WHERE @demo_user_exists = 1
ON DUPLICATE KEY UPDATE
    daily_vocab_target = VALUES(daily_vocab_target),
    daily_quiz_target = VALUES(daily_quiz_target),
    daily_study_minutes = VALUES(daily_study_minutes),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO user_streaks (
    user_id, current_streak, longest_streak, last_completed_date,
    freeze_count, selected_goal_days, onboarding_step, onboarding_completed,
    onboarding_reward_granted, reminder_time, reminder_enabled
)
    SELECT @demo_user_id, 12, 28, @demo_today, 1, 30, 'COMPLETED', TRUE, TRUE, '19:00:00', TRUE
WHERE @demo_user_exists = 1
ON DUPLICATE KEY UPDATE
    current_streak = VALUES(current_streak),
    longest_streak = VALUES(longest_streak),
    last_completed_date = VALUES(last_completed_date),
    freeze_count = VALUES(freeze_count),
    selected_goal_days = VALUES(selected_goal_days),
    onboarding_step = VALUES(onboarding_step),
    onboarding_completed = VALUES(onboarding_completed),
    onboarding_reward_granted = VALUES(onboarding_reward_granted),
    reminder_time = VALUES(reminder_time),
    reminder_enabled = VALUES(reminder_enabled),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO user_daily_progress (
    user_id, progress_date, vocabulary_learned, quizzes_completed,
    study_minutes, target_completed_at
)
VALUES
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 6 DAY), 18, 2, 24, TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 6 DAY), '20:10:00')),
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 5 DAY), 12, 1, 18, NULL),
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 4 DAY), 22, 3, 32, TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 4 DAY), '21:05:00')),
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 3 DAY), 15, 1, 20, TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 3 DAY), '19:30:00')),
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 2 DAY), 9, 1, 14, NULL),
    (@demo_user_id, DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY), 19, 2, 27, TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY), '20:20:00')),
    (@demo_user_id, CURRENT_DATE(), 14, 1, 20, TIMESTAMP(CURRENT_DATE(), '19:42:00'))
ON DUPLICATE KEY UPDATE
    vocabulary_learned = VALUES(vocabulary_learned),
    quizzes_completed = VALUES(quizzes_completed),
    study_minutes = VALUES(study_minutes),
    target_completed_at = VALUES(target_completed_at),
    updated_at = CURRENT_TIMESTAMP(6);

-- Remove only rows created by this demo seed, so the script is repeatable.
DELETE FROM user_credit_transaction
WHERE user_id = @demo_user_id
  AND note COLLATE utf8mb4_unicode_ci LIKE _utf8mb4'DEMO_STITCH_%' COLLATE utf8mb4_unicode_ci;

INSERT INTO user_credit_transaction (
    user_id, amount, reason, reference_type, reference_id, note, created_at
)
VALUES
    (@demo_user_id, 500, 'WELCOME_REWARD', 'STITCH_DEMO', 1, 'DEMO_STITCH_WELCOME', TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 21 DAY), '09:00:00')),
    (@demo_user_id, 680, 'WEEKLY_REWARD', 'STITCH_DEMO', 2, 'DEMO_STITCH_WEEKLY', TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 12 DAY), '20:00:00')),
    (@demo_user_id, 120, 'TARGET_COMPLETE', 'STITCH_DEMO', 3, 'DEMO_STITCH_TARGET', TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 7 DAY), '19:42:00')),
    (@demo_user_id, 30, 'QUIZ_COMPLETE', 'STITCH_DEMO', 4, 'DEMO_STITCH_QUIZ', TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 7 DAY), '08:15:00')),
    (@demo_user_id, -80, 'ITEM_PURCHASE', 'STITCH_DEMO', 5, 'DEMO_STITCH_FREEZE', TIMESTAMP(DATE_SUB(CURRENT_DATE(), INTERVAL 8 DAY), '21:00:00'));

UPDATE users
SET credit_balance = 1250,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = @demo_user_id;

COMMIT;

SELECT
    u.id,
    u.email,
    u.credit_balance,
    s.current_streak,
    s.longest_streak,
    COUNT(DISTINCT p.id) AS progress_days,
    COUNT(DISTINCT c.id) AS seeded_credit_rows
FROM users u
LEFT JOIN user_streaks s ON s.user_id = u.id
LEFT JOIN user_daily_progress p ON p.user_id = u.id
LEFT JOIN user_credit_transaction c
       ON c.user_id = u.id
      AND c.note COLLATE utf8mb4_unicode_ci LIKE _utf8mb4'DEMO_STITCH_%' COLLATE utf8mb4_unicode_ci
WHERE u.id = @demo_user_id
GROUP BY u.id, u.email, u.credit_balance, s.current_streak, s.longest_streak;

-- =============================================================================
-- 7) Seed kiểm duyệt JLAS-W0250: tài khoản test, hàng đợi, kháng nghị
-- =============================================================================
-- JLAS-W0250 test data for the admin workspace, notifications and sanctions.
-- Run after sql/jlas_migrations_all.sql (section 005). Safe to re-run: old test rows are removed first.
-- LOCAL DEVELOPMENT ONLY: every test account uses the password Test@12345.
-- Reports (Bao cao noi dung) cannot be seeded: that flow needs JLAS2-37.
--
-- Accounts (email / role / state):
--   huypcpsn@gmail.com       ADMIN         Google login, sees every admin page
--   admin@jlas.local         ADMIN         all moderation + sanctions
--   mod@jlas.local           MODERATOR     moderation, no sanctions page
--   learner.mute@jlas.local  LEARNER       muted 7 days, open appeal
--   learner.ban@jlas.local   LEARNER       banned 3 days, open appeal
--   learner.clean@jlas.local LEARNER       no sanction (target for sanction tests)

SET NAMES utf8mb4;
SET @pwd = '$2a$10$6/CFcfrBH7UYbXmZ/t9VUeRVmp1a56HTQH4C5d2J3L5hwDRqYeyaC';

-- ── 0) Tester account ──────────────────────────────────────────────────────
-- Literal email (not a @variable): session variables use a different collation than the column.
UPDATE users SET role_id = (SELECT id FROM roles WHERE name = 'ADMIN') WHERE email = 'huypcpsn@gmail.com';

-- ── 1) Clean previous test data (only rows this seed creates) ──────────────
-- Khác file gốc: KHÔNG xoá các tài khoản test. Xoá user sẽ xoá dây chuyền mọi thứ
-- của họ (bộ flashcard, nhiệm vụ, thông báo...) nên ở đây chỉ dọn những dòng do
-- chính seed này tạo: bình luận mẫu, case, án phạt mẫu và các từ đóng góp mẫu.
DELETE rc FROM review_case rc
  JOIN content_moderation_log l ON l.id = rc.moderation_log_id
 WHERE l.model_name = 'demo-seed';
DELETE FROM review_case
 WHERE target_type = 'WORD_CONTRIBUTION'
   AND target_id IN (SELECT id FROM vocabulary_entry
                      WHERE (normalized_expression, normalized_reading) IN
                            (('今日','こんにち'), ('積ん読','つんどく'), ('推し','おし')));
DELETE rc FROM review_case rc
  JOIN user_sanction s ON s.id = rc.target_id
  JOIN users a ON a.id = s.created_by
 WHERE rc.case_type = 'APPEAL' AND rc.target_type = 'USER_SANCTION'
   AND a.email = 'admin@jlas.local'
   AND s.reason IN ('Đăng link quảng cáo nhiều lần', 'Xúc phạm người học khác');
DELETE s FROM user_sanction s
  JOIN users a ON a.id = s.created_by
 WHERE a.email = 'admin@jlas.local'
   AND s.reason IN ('Đăng link quảng cáo nhiều lần', 'Xúc phạm người học khác');
DELETE c FROM content_comment c
  JOIN users u ON u.id = c.user_id
 WHERE u.email IN ('learner.mute@jlas.local', 'learner.ban@jlas.local')
   AND c.content IN ('Ai muốn học nhanh thì vào www.hoc-tieng-nhat-sieu-toc.com và www.khoa-hoc-mien-phi.net đăng ký nhé!',
                     'Bộ này khó quá, học mãi không nhớ, chắc tự tử mất thôi 😭',
                     'この動画、えっちな話じゃないよね？字幕がちょっと変です。');
DELETE FROM content_moderation_log WHERE model_name = 'demo-seed';
DELETE FROM vocabulary_entry
 WHERE (normalized_expression, normalized_reading) IN
       (('今日','きょう'), ('食べる','たべる'), ('今日','こんにち'), ('積ん読','つんどく'), ('推し','おし'));
-- Keyword added by test H2.
DELETE FROM moderation_policy WHERE policy_type = 'TERM' AND policy_key = 'hoc lau';
-- Audit rows whose case, sanction, user or keyword was removed above: they would still offer
-- "change decision" on something that no longer exists. Local test data only.
DELETE FROM moderation_audit_log
 WHERE (case_id IS NOT NULL AND case_id NOT IN (SELECT id FROM review_case))
    OR (target_type = 'USER_SANCTION' AND target_id NOT IN (SELECT id FROM user_sanction))
    OR (target_type = 'USER' AND target_id NOT IN (SELECT id FROM users))
    OR (target_type = 'DENYLIST_TERM' AND target_id NOT IN (SELECT id FROM moderation_policy));

-- ── 2) Test accounts (email + password login) ─────────────────────────────
-- Tạo nếu chưa có; nếu đã có thì chỉ đặt lại vai trò, mật khẩu và tên hiển thị.
INSERT INTO users (role_id, email, password_hash, login_type, full_name, is_verified) VALUES
  ((SELECT id FROM roles WHERE name = 'ADMIN'), 'admin@jlas.local',       @pwd, 'LOCAL', 'Quản trị hệ thống (test)', 1),
  ((SELECT id FROM roles WHERE name = 'MODERATOR'), 'mod@jlas.local',           @pwd, 'LOCAL', 'Kiểm duyệt viên (test)', 1),
  ((SELECT id FROM roles WHERE name = 'LEARNER'),   'learner.mute@jlas.local',  @pwd, 'LOCAL', 'Nguyễn Văn An (bị cấm bình luận)', 1),
  ((SELECT id FROM roles WHERE name = 'LEARNER'),   'learner.ban@jlas.local',   @pwd, 'LOCAL', 'Trần Thị Bình (bị khoá)', 1),
  ((SELECT id FROM roles WHERE name = 'LEARNER'),   'learner.clean@jlas.local', @pwd, 'LOCAL', 'Lê Minh Châu (bình thường)', 1)
ON DUPLICATE KEY UPDATE
  role_id = VALUES(role_id), password_hash = VALUES(password_hash), login_type = VALUES(login_type),
  full_name = VALUES(full_name), is_verified = VALUES(is_verified);

SET @admin   = (SELECT id FROM users WHERE email = 'admin@jlas.local');
SET @learner1 = (SELECT id FROM users WHERE email = 'learner.mute@jlas.local');
SET @learner2 = (SELECT id FROM users WHERE email = 'learner.ban@jlas.local');

-- ── 3) Hang doi AI: 3 visible comments flagged for a post-hoc check ───────
SET @set_a = (SELECT id FROM flashcard_set ORDER BY id LIMIT 1);
SET @set_b = COALESCE((SELECT id FROM flashcard_set ORDER BY id LIMIT 1 OFFSET 2), @set_a);
SET @video = COALESCE((SELECT id FROM video ORDER BY id LIMIT 1 OFFSET 1), (SELECT id FROM video ORDER BY id LIMIT 1));

INSERT INTO content_comment (target_type, target_id, user_id, content, is_hidden)
VALUES ('FLASHCARD_SET', @set_a, @learner1,
        'Ai muốn học nhanh thì vào www.hoc-tieng-nhat-sieu-toc.com và www.khoa-hoc-mien-phi.net đăng ký nhé!', 0);
SET @c1 = LAST_INSERT_ID();
INSERT INTO content_moderation_log (target_type, target_id, owner_user_id, source, verdict, primary_category,
                                    categories_json, confidence, risk_score, model_name, summary)
VALUES ('FLASHCARD_COMMENT', @c1, @learner1, 'COMMENT', 'REVIEW', 'SPAM', '["SPAM"]', 0.70, 60, 'demo-seed',
        'Phát hiện nhiều liên kết — cần duyệt thủ công.');
INSERT INTO review_case (case_type, target_type, target_id, moderation_log_id, owner_user_id, title_snapshot, reason, status)
VALUES ('CONTENT_REVIEW', 'FLASHCARD_COMMENT', @c1, LAST_INSERT_ID(), @learner1,
        'Bình luận bộ thẻ (test)', 'Phát hiện nhiều liên kết — cần duyệt thủ công.', 'OPEN');

INSERT INTO content_comment (target_type, target_id, user_id, content, is_hidden)
VALUES ('FLASHCARD_SET', @set_b, @learner2, 'Bộ này khó quá, học mãi không nhớ, chắc tự tử mất thôi 😭', 0);
SET @c2 = LAST_INSERT_ID();
INSERT INTO content_moderation_log (target_type, target_id, owner_user_id, source, verdict, primary_category,
                                    categories_json, confidence, risk_score, model_name, summary)
VALUES ('FLASHCARD_COMMENT', @c2, @learner2, 'COMMENT', 'REVIEW', 'SENSITIVE_TOPIC', '["SENSITIVE_TOPIC"]', 0.75, 55,
        'demo-seed', 'Nội dung nhạy cảm — cần xem lại.');
INSERT INTO review_case (case_type, target_type, target_id, moderation_log_id, owner_user_id, title_snapshot, reason, status)
VALUES ('CONTENT_REVIEW', 'FLASHCARD_COMMENT', @c2, LAST_INSERT_ID(), @learner2,
        'Bình luận bộ thẻ (test)', 'Nội dung nhạy cảm — cần xem lại.', 'OPEN');

INSERT INTO content_comment (target_type, target_id, user_id, content, is_hidden)
VALUES ('VIDEO', @video, @learner1, 'この動画、えっちな話じゃないよね？字幕がちょっと変です。', 0);
SET @c3 = LAST_INSERT_ID();
INSERT INTO content_moderation_log (target_type, target_id, owner_user_id, source, verdict, primary_category,
                                    categories_json, confidence, risk_score, model_name, summary)
VALUES ('VIDEO_COMMENT', @c3, @learner1, 'COMMENT', 'REVIEW', 'SENSITIVE_TOPIC', '["SENSITIVE_TOPIC"]', 0.62, 40,
        'demo-seed', 'Từ khoá nhạy cảm tiếng Nhật — cần xem lại.');
INSERT INTO review_case (case_type, target_type, target_id, moderation_log_id, owner_user_id, title_snapshot, reason, status)
VALUES ('CONTENT_REVIEW', 'VIDEO_COMMENT', @c3, LAST_INSERT_ID(), @learner1,
        'Bình luận video (test)', 'Từ khoá nhạy cảm tiếng Nhật — cần xem lại.', 'OPEN');

-- ── 4) Dong gop tu dien: 2 published words + 3 hidden contributions ──────
INSERT INTO vocabulary_entry (expression, normalized_expression, reading, normalized_reading, reading_romaji,
                              meaning, word_type, jlpt_level, example_ja, example_vi, usage_scope, dictionary_status)
VALUES ('今日', '今日', 'きょう', 'きょう', 'kyou', 'hôm nay', 'Danh từ', 'N5',
        '今日は暑いです。', 'Hôm nay trời nóng.', 'BOTH', 'PUBLISHED'),
       ('食べる', '食べる', 'たべる', 'たべる', 'taberu', 'ăn', 'Động từ nhóm 2', 'N5',
        '朝ご飯を食べる。', 'Ăn sáng.', 'BOTH', 'PUBLISHED');

-- Same spelling as the published 今日 -> the review screen flags it as a possible duplicate.
INSERT INTO vocabulary_entry (expression, normalized_expression, reading, normalized_reading, reading_romaji,
                              meaning, word_type, jlpt_level, example_ja, example_vi, usage_scope, dictionary_status)
VALUES ('今日', '今日', 'こんにち', 'こんにち', 'konnichi', 'ngày nay, thời nay', 'Danh từ', 'N3',
        '今日の社会では、スマホが欠かせない。', 'Trong xã hội ngày nay, điện thoại là thứ không thể thiếu.',
        'DICTIONARY', 'HIDDEN');
INSERT INTO review_case (case_type, target_type, target_id, owner_user_id, title_snapshot, reason, status)
VALUES ('CONTENT_REVIEW', 'WORD_CONTRIBUTION', LAST_INSERT_ID(), @learner1, 'Đóng góp: 今日 (こんにち)',
        'Người học đề xuất từ mới', 'OPEN');

INSERT INTO vocabulary_entry (expression, normalized_expression, reading, normalized_reading, reading_romaji,
                              meaning, word_type, jlpt_level, example_ja, example_vi, usage_scope, dictionary_status)
VALUES ('積ん読', '積ん読', 'つんどく', 'つんどく', 'tsundoku', 'mua sách về chất đống mà không đọc', 'Danh từ', NULL,
        '本を買いすぎて積ん読になっている。', 'Mua quá nhiều sách nên toàn để chất đống không đọc.', 'DICTIONARY', 'HIDDEN');
INSERT INTO review_case (case_type, target_type, target_id, owner_user_id, title_snapshot, reason, status)
VALUES ('CONTENT_REVIEW', 'WORD_CONTRIBUTION', LAST_INSERT_ID(), @learner2, 'Đóng góp: 積ん読 (つんどく)',
        'Người học đề xuất từ mới', 'OPEN');

INSERT INTO vocabulary_entry (expression, normalized_expression, reading, normalized_reading, reading_romaji,
                              meaning, word_type, jlpt_level, example_ja, example_vi, usage_scope, dictionary_status)
VALUES ('推し', '推し', 'おし', 'おし', 'oshi', 'thần tượng, người mình yêu thích nhất', 'Danh từ', NULL,
        '私の推しは声優です。', 'Thần tượng của tôi là một diễn viên lồng tiếng.', 'DICTIONARY', 'HIDDEN');
INSERT INTO review_case (case_type, target_type, target_id, owner_user_id, title_snapshot, reason, status)
VALUES ('CONTENT_REVIEW', 'WORD_CONTRIBUTION', LAST_INSERT_ID(), @learner1, 'Đóng góp: 推し (おし)',
        'Người học đề xuất từ mới', 'OPEN');

-- ── 5) Che tai + Khang nghi: mute + ban, each with an open appeal ────────
INSERT INTO user_sanction (user_id, created_by, sanction_type, reason, starts_at, ends_at)
VALUES (@learner1, @admin, 'MUTE', 'Đăng link quảng cáo nhiều lần', NOW(6), NOW(6) + INTERVAL 7 DAY);
SET @s1 = LAST_INSERT_ID();
INSERT INTO review_case (case_type, target_type, target_id, requester_user_id, owner_user_id, title_snapshot,
                         reason, evidence_url, status)
VALUES ('APPEAL', 'USER_SANCTION', @s1, @learner1, @learner1, 'MUTE sanction',
        'Link đó là trang học miễn phí của trường mình, không phải quảng cáo. Mong admin xem lại.',
        'https://example.com/bang-chung.png', 'OPEN');

INSERT INTO user_sanction (user_id, created_by, sanction_type, reason, starts_at, ends_at)
VALUES (@learner2, @admin, 'BAN', 'Xúc phạm người học khác', NOW(6), NOW(6) + INTERVAL 3 DAY);
SET @s2 = LAST_INSERT_ID();
INSERT INTO review_case (case_type, target_type, target_id, requester_user_id, owner_user_id, title_snapshot,
                         reason, status)
VALUES ('APPEAL', 'USER_SANCTION', @s2, @learner2, @learner2, 'BAN sanction',
        'Mình chỉ đùa với bạn thân, không có ý xúc phạm ai.', 'OPEN');

-- ── Summary ──────────────────────────────────────────────────────────────
SELECT u.email, r.name AS role,
       (SELECT GROUP_CONCAT(s.sanction_type) FROM user_sanction s
         WHERE s.user_id = u.id AND s.revoked_at IS NULL AND (s.ends_at IS NULL OR s.ends_at > NOW(6))) AS active_sanction
  FROM users u JOIN roles r ON r.id = u.role_id
 WHERE u.email IN ('huypcpsn@gmail.com', 'admin@jlas.local', 'mod@jlas.local', 'learner.mute@jlas.local',
                   'learner.ban@jlas.local', 'learner.clean@jlas.local')
 ORDER BY r.name, u.email;

-- Rollback tester role (back to learner):
-- UPDATE users SET role_id = (SELECT id FROM roles WHERE name = 'LEARNER') WHERE email = 'huypcpsn@gmail.com';

-- =============================================================================
-- Kết thúc: tóm tắt số dòng chính
-- =============================================================================
SET SQL_SAFE_UPDATES = @SEED_PREVIOUS_SQL_SAFE_UPDATES;

SELECT 'users' AS bang, COUNT(*) AS so_dong FROM users
UNION ALL SELECT 'scenario', COUNT(*) FROM scenario
UNION ALL SELECT 'textbook', COUNT(*) FROM textbook
UNION ALL SELECT 'lesson', COUNT(*) FROM lesson
UNION ALL SELECT 'kanji', COUNT(*) FROM kanji
UNION ALL SELECT 'assessment_question', COUNT(*) FROM assessment_question
UNION ALL SELECT 'vocabulary_entry', COUNT(*) FROM vocabulary_entry
UNION ALL SELECT 'flashcard_set', COUNT(*) FROM flashcard_set
UNION ALL SELECT 'video', COUNT(*) FROM video
UNION ALL SELECT 'review_case', COUNT(*) FROM review_case;
