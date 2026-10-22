-- =====================================================================================================
-- [JLAS2-17] patch_lesson_descriptions.sql — mô tả cho học viên của 50 bài + 2 giáo trình Sekai (N5, N4)
-- =====================================================================================================
-- Vì sao có file này: mô tả mới đã viết vào nguồn seed (meta.mo_ta trong data/seed_jlas/sekai/*/json,
-- TEXTBOOK_DESCRIPTIONS trong tools/generate_seed.py) nhưng sql/seeds/seed_data_jlas.sql CHƯA sinh lại
-- (generate_seed.py chưa có id ổn định — xem ticket trong JLAS2-17_huong_dan_team.txt). File này đưa mô tả
-- mới vào DB mà không phải sinh lại seed.
--
-- Thứ tự chạy: NGAY SAU sql/seeds/seed_data_jlas.sql (trước các seed khác).
-- Phạm vi: CHỈ cột description của textbook id 1, 2 và lesson id 1–50; không đụng cột nào khác
--          (updated_at giữ nguyên). Mỗi câu còn so khớp tiêu đề nên không ghi nhầm hàng nếu id đã khác.
-- Idempotent: chạy lại bao nhiêu lần cũng ra cùng kết quả.
-- Khi seed_data_jlas.sql đã được sinh lại có mô tả mới thì file này thành vô hại (UPDATE cùng giá trị) và có thể xoá.
-- =====================================================================================================
SET NAMES utf8mb4;
START TRANSACTION;

-- Giáo trình
UPDATE textbook SET description = 'Giáo trình N5 gồm 25 bài: chào hỏi, giới thiệu bản thân và các mẫu câu cơ bản dùng hằng ngày.', updated_at = updated_at WHERE id = 1 AND title = 'Sekai N5 — Giáo trình tiếng Nhật';
UPDATE textbook SET description = 'Giáo trình N4 gồm 25 bài: thể khả năng, bị động, sai khiến, kính ngữ và các mẫu câu giao tiếp mở rộng.', updated_at = updated_at WHERE id = 2 AND title = 'Sekai N4 — Giáo trình tiếng Nhật';

-- Bài học (Sekai N5: id 1–25, Sekai N4: id 26–50)
UPDATE lesson SET description = 'Học cách giới thiệu tên, nghề nghiệp và quốc tịch bằng mẫu câu N1 は N2 です.', updated_at = updated_at WHERE id = 1 AND title = 'Bài 1: Giới thiệu bản thân — tên, nghề nghiệp, quốc gia';
UPDATE lesson SET description = 'Học cách chỉ đồ vật và hỏi "cái gì, của ai" với これ・それ・あれ và この・その・あの.', updated_at = updated_at WHERE id = 2 AND title = 'Bài 2: Đồ vật quanh mình: cái này, cái kia, của ai';
UPDATE lesson SET description = 'Học cách chỉ nơi chốn, hỏi đường và hỏi giá với ここ・そこ・あそこ, どこ và いくら.', updated_at = updated_at WHERE id = 3 AND title = 'Bài 3: Chỗ này chỗ kia: hỏi đường và hỏi giá';
UPDATE lesson SET description = 'Học cách nói giờ giấc và việc làm trong ngày với thể ます và mẫu ～から～まで.', updated_at = updated_at WHERE id = 4 AND title = 'Bài 4: Mấy giờ làm gì: giờ giấc và việc trong ngày';
UPDATE lesson SET description = 'Học cách nói đi đâu, bằng phương tiện gì và với ai qua các mẫu へ いきます, で, と.', updated_at = updated_at WHERE id = 5 AND title = 'Bài 5: Đi đâu, bằng gì, với ai';
UPDATE lesson SET description = 'Học cách nói ăn uống, làm gì ở đâu với を V ます, で và lời rủ いっしょに ～ませんか.', updated_at = updated_at WHERE id = 6 AND title = 'Bài 6: Ăn gì uống gì: việc làm mỗi ngày';
UPDATE lesson SET description = 'Học cách nói cho, tặng, nhận và mượn đồ với あげます, もらいます, かします, かります.', updated_at = updated_at WHERE id = 7 AND title = 'Bài 7: Cho và nhận: quà, thư, lời cảm ơn';
UPDATE lesson SET description = 'Học cách khen chê, miêu tả người và vật bằng tính từ đuôi い và tính từ đuôi な.', updated_at = updated_at WHERE id = 8 AND title = 'Bài 8: Khen chê: đẹp, ngon, ồn ào';
UPDATE lesson SET description = 'Học cách nói sở thích, việc mình giỏi hay kém và nêu lý do với すき, じょうず, から.', updated_at = updated_at WHERE id = 9 AND title = 'Bài 9: Thích và giỏi: nói sở thích, nói lý do';
UPDATE lesson SET description = 'Học cách nói ở đâu có đồ vật, có người với あります・います và các từ chỉ vị trí.', updated_at = updated_at WHERE id = 10 AND title = 'Bài 10: Cái gì ở đâu: chỉ chỗ đồ vật và người';
UPDATE lesson SET description = 'Học cách đếm đồ vật, đếm người khi mua hàng với ～つ, ～まい, ～ほん, ～にん.', updated_at = updated_at WHERE id = 11 AND title = 'Bài 11: Đếm bao nhiêu: mua hàng và nói số lượng';
UPDATE lesson SET description = 'Học cách so sánh hơn, nhất với より, ほうが, いちばん và nói tính từ ở thì quá khứ.', updated_at = updated_at WHERE id = 12 AND title = 'Bài 12: So sánh: cái nào hơn, cái nào nhất';
UPDATE lesson SET description = 'Học cách nói muốn có gì, muốn làm gì với ほしい, ～たい và nói đi đâu để làm gì.', updated_at = updated_at WHERE id = 13 AND title = 'Bài 13: Muốn gì, đi đâu làm gì';
UPDATE lesson SET description = 'Học cách nhờ người khác giúp với ～てください và nói việc đang làm với ～ています.', updated_at = updated_at WHERE id = 14 AND title = 'Bài 14: Nhờ và chờ: xin làm giúp, đang làm dở';
UPDATE lesson SET description = 'Học cách xin phép và nói điều bị cấm với ～てもいいです, ～てはいけません.', updated_at = updated_at WHERE id = 15 AND title = 'Bài 15: Được phép hay không: xin phép và điều cấm';
UPDATE lesson SET description = 'Học cách kể các việc theo thứ tự và chỉ đường bằng thể て và mẫu ～てから.', updated_at = updated_at WHERE id = 16 AND title = 'Bài 16: Việc này rồi việc kia: kể thứ tự trong ngày';
UPDATE lesson SET description = 'Học cách nói phải làm, đừng làm, không cần làm với ～なければなりません, ～ないでください.', updated_at = updated_at WHERE id = 17 AND title = 'Bài 17: Đừng làm, phải làm, không cần làm';
UPDATE lesson SET description = 'Học cách nói khả năng và sở thích với ～ことができます và しゅみは～ことです.', updated_at = updated_at WHERE id = 18 AND title = 'Bài 18: Làm được và sở thích: nói khả năng của mình';
UPDATE lesson SET description = 'Học cách kể trải nghiệm với ～たことがあります, ～たり～たり và nói sự thay đổi với なります.', updated_at = updated_at WHERE id = 19 AND title = 'Bài 19: Đã từng và đổi khác: kể trải nghiệm';
UPDATE lesson SET description = 'Học cách nói chuyện thân mật bằng thể thường, bỏ ます và です.', updated_at = updated_at WHERE id = 20 AND title = 'Bài 20: Nói thân mật: bỏ ます và です';
UPDATE lesson SET description = 'Học cách nêu ý kiến và thuật lại lời người khác với ～とおもいます, ～といいました.', updated_at = updated_at WHERE id = 21 AND title = 'Bài 21: Tôi nghĩ là, anh ấy bảo là';
UPDATE lesson SET description = 'Học cách đặt cả một mệnh đề trước danh từ để miêu tả, như "người đang đứng kia".', updated_at = updated_at WHERE id = 22 AND title = 'Bài 22: Người đang đứng kia, chỗ tôi hay tới';
UPDATE lesson SET description = 'Học cách nói lúc nào làm gì với ～とき và điều kiện "hễ… thì" với ～と.', updated_at = updated_at WHERE id = 23 AND title = 'Bài 23: Lúc nào thì làm gì: khi và hễ';
UPDATE lesson SET description = 'Học cách nói làm giúp và được giúp với ～てあげます, ～てくれます, ～てもらいます.', updated_at = updated_at WHERE id = 24 AND title = 'Bài 24: Ai làm giúp ai: ơn qua ơn lại';
UPDATE lesson SET description = 'Học cách nói giả định "nếu" với ～たら và "dù" với ～ても.', updated_at = updated_at WHERE id = 25 AND title = 'Bài 25: Nếu và dù: chuyện chưa xảy ra';
UPDATE lesson SET description = 'Học cách hỏi, giải thích lý do và mở lời nhờ vả với ～んです, ～んですが.', updated_at = updated_at WHERE id = 26 AND title = 'Bài 26: Vì sao vậy: nói lý do và nhờ việc';
UPDATE lesson SET description = 'Học cách nói khả năng bằng thể khả năng và phân biệt みえます, きこえます, できます.', updated_at = updated_at WHERE id = 27 AND title = 'Bài 27: Làm được, nhìn thấy, nghe thấy';
UPDATE lesson SET description = 'Học cách nói làm hai việc cùng lúc với ～ながら và kể thói quen với ～ています.', updated_at = updated_at WHERE id = 28 AND title = 'Bài 28: Vừa làm vừa làm: thói quen mỗi ngày';
UPDATE lesson SET description = 'Học cách tả trạng thái đồ vật với tự động từ + ています và nói lỡ làm với ～てしまいました.', updated_at = updated_at WHERE id = 29 AND title = 'Bài 29: Cửa đang mở, đèn đã tắt';
UPDATE lesson SET description = 'Học cách nói việc đã chuẩn bị sẵn với ～てあります và làm trước với ～ておきます.', updated_at = updated_at WHERE id = 30 AND title = 'Bài 30: Chuẩn bị sẵn, để nguyên đấy';
UPDATE lesson SET description = 'Học cách nói dự định và kế hoạch với ～ようとおもいます, ～つもりです, ～よていです.', updated_at = updated_at WHERE id = 31 AND title = 'Bài 31: Định làm gì: dự tính và kế hoạch';
UPDATE lesson SET description = 'Học cách khuyên với ～たほうがいいです và phỏng đoán với ～でしょう, ～かもしれません.', updated_at = updated_at WHERE id = 32 AND title = 'Bài 32: Nên làm thế, chắc là thế';
UPDATE lesson SET description = 'Học cách hiểu biển báo, câu mệnh lệnh, lời cấm và hỏi "ghi gì, nghĩa là gì".', updated_at = updated_at WHERE id = 33 AND title = 'Bài 33: Bảng cấm, biển báo, lời dặn gắt';
UPDATE lesson SET description = 'Học cách làm theo mẫu và theo thứ tự với ～とおりに, ～あとで, ～ないで.', updated_at = updated_at WHERE id = 34 AND title = 'Bài 34: Làm theo mẫu, làm theo thứ tự';
UPDATE lesson SET description = 'Học cách nói điều kiện với thể ば, ～なら và "càng… càng" với ～ば～ほど.', updated_at = updated_at WHERE id = 35 AND title = 'Bài 35: Hễ mà, nếu là: điều kiện';
UPDATE lesson SET description = 'Học cách nói mục tiêu và sự tiến bộ với ～ように, ～ようになりました, ～ようにしています.', updated_at = updated_at WHERE id = 36 AND title = 'Bài 36: Cố cho được: tập tới khi thành';
UPDATE lesson SET description = 'Học cách dùng thể bị động để kể chuyện xảy đến với mình và những việc chung.', updated_at = updated_at WHERE id = 37 AND title = 'Bài 37: Bị và được: chuyện xảy tới với mình';
UPDATE lesson SET description = 'Học cách danh từ hoá động từ với の, こと để nói việc mình thích, giỏi hay quên.', updated_at = updated_at WHERE id = 38 AND title = 'Bài 38: Việc mình thích, việc mình quên';
UPDATE lesson SET description = 'Học cách nói nguyên nhân dẫn tới cảm xúc, kết quả với thể て, ～くて và ～で.', updated_at = updated_at WHERE id = 39 AND title = 'Bài 39: Vì thế nên: nói nguyên do';
UPDATE lesson SET description = 'Học cách nói điều chưa chắc với ～かどうか, câu hỏi lồng và làm thử với ～てみます.', updated_at = updated_at WHERE id = 40 AND title = 'Bài 40: Chưa chắc đâu: thử xem sao';
UPDATE lesson SET description = 'Học cách nói cho và nhận với người trên, người dưới: いただきます, くださいます, やります.', updated_at = updated_at WHERE id = 41 AND title = 'Bài 41: Người trên cho, mình xin nhận';
UPDATE lesson SET description = 'Học cách nói mục đích với ～ために và công dụng với ～のにつかいます, ～のにべんりです.', updated_at = updated_at WHERE id = 42 AND title = 'Bài 42: Để làm gì, dùng vào việc gì';
UPDATE lesson SET description = 'Học cách phỏng đoán qua vẻ bề ngoài với ～そうです và nói thay đổi với ～てきます, ～ていきます.', updated_at = updated_at WHERE id = 43 AND title = 'Bài 43: Trông có vẻ sắp đổ';
UPDATE lesson SET description = 'Học cách nói quá mức với ～すぎます và dễ, khó làm với ～やすい, ～にくい.', updated_at = updated_at WHERE id = 44 AND title = 'Bài 44: Quá tay, dễ dùng, khó đọc';
UPDATE lesson SET description = 'Học cách nói trường hợp với ～ばあいは và bày tỏ trái ý, tiếc nuối với ～のに.', updated_at = updated_at WHERE id = 45 AND title = 'Bài 45: Trường hợp này thì làm sao';
UPDATE lesson SET description = 'Học cách nói thời điểm của hành động với ～ところです, ～たばかりです và suy luận với ～はずです.', updated_at = updated_at WHERE id = 46 AND title = 'Bài 46: Vừa mới xong, sắp bắt đầu';
UPDATE lesson SET description = 'Học cách truyền đạt tin nghe được với ～そうです và phỏng đoán với ～ようです, ～らしいです.', updated_at = updated_at WHERE id = 47 AND title = 'Bài 47: Nghe nói là, hình như là';
UPDATE lesson SET description = 'Học cách dùng thể sai khiến để bắt làm, cho phép làm và xin phép một cách lịch sự.', updated_at = updated_at WHERE id = 48 AND title = 'Bài 48: Bắt làm, cho phép làm';
UPDATE lesson SET description = 'Học cách dùng kính ngữ để nói về hành động của người trên một cách lịch sự.', updated_at = updated_at WHERE id = 49 AND title = 'Bài 49: Nói kính trọng người trên';
UPDATE lesson SET description = 'Học cách dùng khiêm nhường ngữ khi nói về mình và tự giới thiệu trang trọng.', updated_at = updated_at WHERE id = 50 AND title = 'Bài 50: Nói khiêm nhường về mình';

COMMIT;
