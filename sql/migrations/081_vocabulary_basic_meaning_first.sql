-- [JLAS-DICT] Nghĩa chính (vocabulary_entry.meaning) thiếu nghĩa cơ bản của từ.
-- Một số mục chỉ mang nghĩa bóng do bài học cấp cao ghi vào (軽い = "Hời hợt; không nghiêm trọng", 悪い = "khó gần",
-- 切る = "Ấn định giới hạn; chấm dứt"), còn sót tiếng Anh (すぺいん = "Spain") hoặc là bản dịch máy khó hiểu.
-- Danh sách dưới đây được chọn tay từ 96 mục có nghĩa từ điển không trùng chữ nào với nghĩa trong lộ trình JLPT
-- (jpath_vocabulary.meaning_vi, trạng thái AI_CHECKED); 36 mục còn lại được để nguyên vì nghĩa từ điển vốn ổn hoặc
-- lộ trình ghép nhầm từ đồng âm (に/二, いち/市, 誤認/五人).
-- Cách sửa: đặt nghĩa cơ bản lên đầu, GIỮ nghĩa cũ phía sau. Chỉ sửa mục PUBLISHED có đúng chữ, cách đọc và nghĩa cũ
-- như liệt kê, nên chạy lại hoặc chạy trên DB có nội dung khác thì không đổi gì. Giá trị cũ nằm trong backup_meaning_081.
-- Hoàn tác: UPDATE vocabulary_entry e JOIN backup_meaning_081 b ON b.id = e.id SET e.meaning = b.meaning;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS backup_meaning_081 (
    id       BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    meaning  TEXT            NULL,
    saved_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS tmp_meaning_081;
CREATE TABLE tmp_meaning_081 (
    n           INT UNSIGNED NOT NULL PRIMARY KEY,
    expression  VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    reading     VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    old_meaning TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    basic       VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
);

INSERT INTO tmp_meaning_081 (n, expression, reading, old_meaning, basic) VALUES
(1, 'すぺいん', 'すぺいん', 'Spain', 'nước Tây Ban Nha (châu Âu)'),
(2, 'けいさつかん', 'けいさつかん', 'policemen', 'cảnh sát'),
(3, 'いんど', 'いんど', 'Hindustan', 'Ấn Độ'),
(4, '一度も', 'いちども', 'never (in sentence with neg. verb)', 'chưa lần nào'),
(5, 'わあ', 'わあ', 'wow! (surprise), oh! (startled), eek!, gee!; yeah! (joy), alright!, hurray!; waah! (crying), boohoo!', 'ôi; oa (khi ngạc nhiên)'),
(6, '止める', 'とめる', 'cấm chỉ; chú ý; cố định', 'dừng xe; đỗ xe; dừng vật'),
(7, '連れて来る', 'つれてくる', 'Dắt về…', 'dẫn đến; đưa người khác đến'),
(8, 'なる', 'なる', 'Ra quả, chín quả', 'trở thành; chuyển sang'),
(9, '軽い', 'かるい', 'Hời hợt; không nghiêm trọng; hay tiết lộ', 'nhẹ'),
(10, '渡る', 'わたる', 'Kéo dài hoặc trải rộng trên phạm vi', 'băng qua; đi qua'),
(11, '切る', 'きる', 'Ấn định giới hạn; chấm dứt', 'cắt; tắt (máy hoặc điện)'),
(12, '悪い', 'わるい', 'khó gần', 'xấu; tệ; không tốt'),
(13, '複雑な', 'ふくざつな', 'đa đoan; gai góc; hóc búa', 'phức tạp; rắc rối'),
(14, '丁寧な', 'ていねいな', 'bặt thiệp; hòa nhã; lễ phép', 'lịch sự; chu đáo; cẩn thận'),
(15, '片づく', 'かたづく', '(1) để được đặt trong thứ tự; để được đặt tới quyền lợi;(2) để (thì) sẵn lòng (của); để được giải quyết;(3) để (thì) đã kết thúc;(4) để được kết hôn (bên ngoài)', 'trở nên gọn ghẽ; xong xuôi đâu vào đấy'),
(16, '細い', 'ほそい', 'gầy', 'mảnh; thon; nhỏ (dây hay đường)'),
(17, '昼間', 'ひるま', 'Buổi trưa', 'ban ngày; lúc trời còn sáng'),
(18, 'どんどん', 'どんどん', 'dần dần', 'liên tục; nhanh chóng; ào ào'),
(19, '消える', 'きえる', 'Bị xóa, bị dập (tự động từ)', '(đèn hay lửa) tắt; biến mất'),
(20, '休館日', 'きゅうかんび', 'day on which a museum, etc. is regularly closed, closed day', 'ngày nghỉ định kỳ'),
(21, '内緒', 'ないしょ', 'riêng tư; cuộc sống gia đình', 'bí mật; chuyện giữ kín'),
(22, 'ばらばらな', 'ばらばらな', 'chia rẻ, phân tán', 'rời rạc; lộn xộn; mỗi người một nơi'),
(23, '肩書き', 'かたがき', 'Địa chỉ người nhận trên phong bì, tiêu đề', 'chức danh; học vị; tước hiệu'),
(24, '温厚な', 'おんこうな', 'dễ dãi', 'hiền hậu; ôn hòa; điềm đạm'),
(25, '明確な', 'めいかくな', 'rành mạch', 'rõ ràng; minh bạch; xác định'),
(26, '広大な', 'こうだいな', 'đồ sộ; khuếch đại; uyên bác', 'rộng lớn; bao la; mênh mông'),
(27, '同様な', 'どうような', 'hệt', 'tương tự; giống như vậy'),
(28, '口が悪い', 'くちがわるい', 'chế nhạo, mỉa mai, chân biếm', 'ăn nói khó nghe; độc mồm; ăn nói cay nghiệt'),
(29, '終了する', 'しゅうりょうする', 'tắt máy tính', 'kết thúc tự nhiên; kết thúc theo lịch; quy trình'),
(30, '鮮明な', 'せんめいな', 'tươi sáng', 'rõ nét; sắc nét'),
(31, '早急な', 'さっきゅうな', 'lanh chanh', 'khẩn cấp; gấp'),
(32, '息が長い', 'いきがながい', 'Có kinh nghiệm, kỳ cựu', 'kéo dài lâu; bền bỉ'),
(33, '健全な', 'けんぜんな', 'kiện toàn', 'lành mạnh; khỏe mạnh'),
(34, 'ぼける', 'ぼける', 'đình trệ (sự buôn bán); làm chậm lại; chậm (bước) lại; giảm bớt, trở nên trì trệ', 'lú lẫn; lẩm cẩm'),
(35, 'とやかく', 'とやかく', 'thế nào cũng được, cách nào cũng được, dầu sao chăng nữa, dù thế nào đi nữa, đại khái, qua loa, tuỳ tiện, được chăng hay chớ; cẩu thả, lộn xộn, lung tung', 'nói này nói nọ; soi xét; bàn tán (thường dùng ở dạng phủ định)'),
(36, 'つきましては', 'つきましては', 'về việc', 'vì vậy; do đó'),
(37, 'ぞんざいな', 'ぞんざいな', 'lỏng chỏng', 'cẩu thả; thô lỗ'),
(38, 'しぐさ', 'しぐさ', 'cách xử lý, chế độ; hành vi, trạng thái; hành động, nghệ thuật đóng kịch (kịch, phim, chèo, tuồng); sự thủ vai, sự đóng kịch, thay quyền, quyền', 'điệu bộ; cử chỉ của tay chân hoặc nét mặt'),
(39, 'こっけいな', 'こっけいな', 'lố lăng', 'hài hước; buồn cười'),
(40, '息が詰まる', 'いきがつまる', 'bạt hơi', 'nghẹt thở; ngột ngạt'),
(41, '気が向かない', 'きがむかない', 'gắng gượng', 'không có hứng; không muốn làm'),
(42, 'こみ上げる', 'こみあげる', 'cảm thấy tanh tưởi; thấy đáng ghê; xoa dịu trái tim; khỏa lấp tình cảm', 'trào dâng'),
(43, '矢先', 'やさき', 'đầu mũi tên; mũi nhọn; mục đích', 'đúng lúc sắp; ngay khi vừa định'),
(44, '相反する', 'あいはんする', 'bất đồng', 'trái ngược; mâu thuẫn nhau'),
(45, '正気', 'しょうき', 'chính khí; nghĩa khí', 'tỉnh táo; tỉnh trí'),
(46, '見栄', 'みえ', 'hiện ra; màn hình; sự xuất hiện; tính kiêu căng; mê hoặc; sự hấp dẫn', 'sĩ diện; thể diện bề ngoài'),
(47, '無我夢中', 'むがむちゅう', 'bị thu hút; mất tự chủ', 'say mê quên mình; hết mình'),
(48, '念じる', 'ねんじる', 'niệm', 'cầu mong; thầm cầu nguyện'),
(49, '遅らせる', 'おくらせる', 'để thoái thác cái gì đó', 'làm chậm lại; hoãn'),
(50, '打ち上げ', 'うちあげ', 'nâng cao, hoàn thành một dự án hoặc giải trí', 'tiệc mừng xong việc; phóng lên'),
(51, '千差万別', 'せんさばんべつ', 'một sự đa dạng vô hạn (của)', 'muôn hình vạn trạng'),
(52, '至れり尽くせり', 'いたれりつくせり', 'chính lịch sự; đa số các nhã nhặn', 'chu đáo hết mức; tận tình'),
(53, '下火になる', 'したびになる', 'đươc kiểm soát; đốt cháy', 'giảm dần; nguội đi'),
(54, 'まんまと', 'まんまと', 'thành công, công bằng, nghệ thuật, độc đáo, kỹ lưỡng', 'trót lọt; mắc bẫy hoàn toàn'),
(55, 'のこのこ', 'のこのこ', 'lãnh đạm; hững hờ; vô tư lự', 'trơ tráo xuất hiện; thản nhiên đến'),
(56, '重い', 'おもい', 'Trách nhiệm hoặc hậu quả nặng nề', 'nặng');

START TRANSACTION;

INSERT IGNORE INTO backup_meaning_081 (id, meaning)
SELECT e.id, e.meaning FROM vocabulary_entry e
JOIN tmp_meaning_081 t ON t.expression = e.expression AND t.reading = e.reading AND t.old_meaning = e.meaning
WHERE e.dictionary_status = 'PUBLISHED';

UPDATE vocabulary_entry e
JOIN tmp_meaning_081 t ON t.expression = e.expression AND t.reading = e.reading AND t.old_meaning = e.meaning
SET e.meaning = CONCAT(t.basic, '; ', e.meaning)
WHERE e.dictionary_status = 'PUBLISHED';

COMMIT;

DROP TABLE tmp_meaning_081;
