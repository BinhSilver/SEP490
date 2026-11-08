# Rules & Business Logic — Smart Test + Learning Path

Cập nhật: **2026-11-07**. Đây là đặc tả nghiệp vụ chuẩn cho Assessment/Learning.
Tài liệu mô tả hành vi phải đạt; sự hiện diện của một quy tắc không chứng minh code/data/UAT đã hoàn tất.
Bằng chứng chạy và tồn đọng nằm trong execution ledger, không dùng số liệu audit cũ làm điều kiện nghiệp vụ.

## 0. Phạm vi, thuật ngữ và thứ tự áp dụng

- **Survey** lưu riêng trình độ tự khai, kinh nghiệm, nhu cầu, target và thời gian học.
- **Smart Test** lưu riêng cấp được đánh giá, mức **Thành thạo / Căn bản / Nhập môn**, điểm từng kỹ năng và bằng chứng lỗi sai. Không đồng nhất tự khai với kết quả đánh giá hoặc gộp cấp với mức thành thạo.
- **Khởi động** là học nền hoặc ôn điểm yếu trước nội dung mục tiêu; không phải mọi path đều có phần này.
- **Tình huống giao tiếp** là đơn vị học của lộ trình giao tiếp (§2.3).
- `BA` là mã lưu của mục tiêu "Công việc" (khảo sát chung); đầu vào `BRSE` chuẩn hóa thành `BA`.
- Quyết định owner về vào lộ trình được tích hợp tại §1.1 và §2.1. Quyết định bỏ **0→N3** đã được xác nhận; không khôi phục yêu cầu demo cũ này.
- Quyết định owner ngày 2026-10-19: mở JLPT N5–N1 (§2.2); Hội thoại giao tiếp hoàn thành khi luyện đủ từng lượt thoại và tới cuối, không ngưỡng thời gian (§§2.14, 2.24); giữ mô hình chủ đề từ vựng riêng + tình huống hội thoại (§2.24); link sách BrSE trỏ giáo trình trong J-LAS (§2.24). Vẫn giữ bỏ 0→N3.
- Các quy tắc hiện hành dưới đây thay thế ghi chú cũ trái nghĩa. Code chưa khớp là implementation gap; không sửa nghiệp vụ để hợp thức hóa code.
- Chỉ thay đổi mục tiêu/điểm bắt đầu khi người học đồng ý; mọi thay đổi phải bảo toàn path/progress/history/CP/streak đã có.

## 1. Smart Test

### 1.1 Goal, đầu vào và entry policy

| Goal | Đầu vào | Đánh giá |
|---|---|---|
| `JLPT` | Survey có target theo danh sách hỗ trợ | Chưa từng học (`FROM_ZERO`): Kana trước, không ép Smart Test; đã học/`UNKNOWN`: đủ 5 skill |
| `BA` | Survey nhu cầu và trình độ tự khai khi có | Survey-only theo quyết định 2026-10-14; không Japanese Foundation Assessment/Smart Test |
| `TRAVEL`, `DAILY_LIFE` | Survey chọn nhu cầu | Survey-only, không Smart Test |

- `USAGE_GOALS={BA,TRAVEL,DAILY_LIFE}`, `GOALS_WITH_TARGET={JLPT}`.
- `CURRENT_LEVELS` có `FROM_ZERO`, `UNKNOWN`, `N5`…`N1` để lưu dữ liệu; khả năng lưu level không đồng nghĩa hỗ trợ tạo mọi cặp path.
- JLPT chạy `KANJI → VOCABULARY → GRAMMAR → READING → LISTENING`; không cho tự bỏ skill để tạo kết quả đủ điều kiện.
- Survey giao tiếp thể hiện nhu cầu, không chứng minh competency/weakness/mastery. Quy tắc tình huống xem §2.3.
- Owner 2026-10-21 (OD-34): `UNKNOWN` + target N2/N1 được lưu survey và làm Smart Test; path chỉ tạo khi dữ liệu cấp đó qua cổng phát hành.
- Owner 2026-10-21 (OD-38): câu thời gian 10/20/30 phút của BA/Travel/Daily Life quyết định `minutes_per_day` của path.

### 1.2 Chọn câu và tính hợp lệ ngân hàng

- Chọn `assessment_question` active đúng skill/level; câu JLPT khớp tag chính hoặc `context_json.additional_tags` có `JLPT`. Không lấy câu goal khác chỉ để lấp thiếu.
- Randomize trong từng tier ưu tiên; loại ID đã phát. Kanji khử trùng theo lexeme/word canonical, không chỉ question ID.
- Mỗi batch cần 5 câu Kanji khác từ; cần đủ dữ liệu cho batch tiếp theo trước khi gọi readiness đạt. Thiếu câu báo coverage rõ, không lặp câu để đủ số.
- Đáp án/distractors/source phải hợp lệ. Kiểm trùng lựa chọn bằng đối chiếu Unicode thích hợp; collation MySQL có thể coi kana hữu thanh/vô thanh bằng nhau, không được tự sửa đáp án vì false positive collation.

### 1.3 Adaptive policy — giữ hằng số hiện có

- `BATCH_SIZE=5`, `MAX_QUESTIONS_PER_SKILL=25`; thang đánh giá `N5→N4→N3→N2→N1`.
- Batch đúng ≤2: hạ; đúng 3: giữ và xác nhận; đúng ≥4: tăng.
- Kết thúc theo trạng thái policy: `CONFIRMED_SAME_LEVEL`, `POST_CONFIRMATION_PROBE_PASSED`, `POST_CONFIRMATION_PROBE_FELL_BACK`, `POST_CONFIRMATION_DOWNWARD_PROBE_FINISHED`, `ABILITY_RANGE_STABLE`, `LEVEL_BOUNDARY_REACHED`, `MAX_QUESTIONS_REACHED`.
- Không trao cấp chưa test. Không tự thêm ngưỡng điểm phân loại mức thành thạo vì đặc tả mới không đặt ngưỡng mới.

### 1.4 Chấm điểm, kết quả và quyền lựa chọn

- Per-skill giữ `estimatedLevel`, `masteryScore`, `confidence`; overall giữ cấp, mức thành thạo và tỷ lệ đúng riêng.
- Partial/chưa đủ mẫu phải nói rõ confidence thấp; không biến thiếu evidence thành 0% hoặc đã vững.
- ~~Owner 2026-10-21 (OD-36): band tạm tính "Chưa đủ dữ liệu" được xếp như Căn bản khi tạo path.~~ Thay bởi owner 2026-10-25:
  bài chưa hoàn thành không tạo khởi động ôn lỗi (câu bỏ trống không chứng minh lỗi).
- Owner 2026-10-21 (OD-07): câu chưa được owner rà (100 câu N5, 12 câu Kanji N4 DRAFT) tạm gỡ tag JLPT khỏi pool; duyệt xong
  mới gắn lại. Gỡ/gắn qua generator trên bản LP-OWNED, không sửa ngân hàng Assessment.
- Backend chấm theo chỉ số option của snapshot đã phát. Client/AI không cung cấp answer key hoặc tự quyết điểm.
- Kết quả và lựa chọn đồng ý/từ chối đề xuất phải gắn với đúng session/survey snapshot. Chỉnh survey sau không thay nghĩa attempt cũ.
- Target/điểm bắt đầu được chọn theo §§2.1–2.3; không dùng nhãn Thành thạo ở cấp thấp để nói người học đã thành thạo target.

### 1.5 Bằng chứng lỗi sai và weakness

- Giữ mọi câu sai/bỏ trống của đủ 5 skill; `NO_RESPONSE` tính sai, `TECHNICAL_FAILURE` loại khỏi mẫu số.
- Denominator lấy câu đã trình bày trong snapshot; legacy thiếu snapshot chỉ dùng câu trả lời đã lưu, không tự dựng mẫu số.
- Mỗi lỗi phải map tới nội dung hoặc gap rõ. Không có lỗi không đồng nghĩa có bằng chứng mastery.
- Mapping Vocab theo mặt chữ chỉ dùng khi exact normalized expression trả đúng một entry eligible; 0 hoặc nhiều entry giữ `CONTENT_MAPPING_PENDING`, không `LIMIT 1` chọn tùy ý.
- `weakEvidence` và kế hoạch ôn là hai khái niệm riêng. Hằng số `WeakTagPolicy` hiện có (Grammar ratio mặc định 0.5, Reading/Listening mẫu 5 sai ≥4) không được làm mất lỗi sai cần bao phủ theo đặc tả Căn bản. Không tự thay các hằng số để né thiếu mapping.

## 2. Learning Path — hợp đồng hiện hành (owner 2026-11-07)

Owner 2026-11-07: chỉ còn **hai lộ trình mới**, lộ trình JLPT (`jlptpath`) và lộ trình giao tiếp (`cpath`), bật mặc định
bằng `learning.jlpt-path.enabled` và `learning.comm-path.enabled`. Lộ trình cá nhân hoá cũ, lộ trình tương tác V2 và khảo
sát mục tiêu cũ đã gỡ khỏi sản phẩm; đường dẫn cũ (`/learning/path`, `/learning/path/v2/**`, `/learning/v2/**`,
`/learning/goals/{goal}/survey`, `/learning/stages/**`, `/learning/warm-up/**`, `/learning/certificate/{id}`) chỉ còn chuyển
về `/learning`. Bảng DB cũ giữ nguyên để không mất dữ liệu người học, không còn màn hình đọc chúng.

### 2.1 Vào lộ trình và khảo sát (mỗi thông tin hỏi một lần)

- `/learning` quyết định theo mục tiêu khảo sát: JLPT → lộ trình JLPT (đang có thì vào bản đồ, chưa có thì trang bắt đầu);
  BA ("Công việc"), TRAVEL, DAILY_LIFE → `/learning/comm`; chưa khảo sát → `/survey`.
- Khảo sát chung hỏi mục tiêu trước. Mục tiêu giao tiếp chỉ hỏi đúng câu mục tiêu rồi sang thẳng khảo sát giao tiếp, không
  hỏi lại mục tiêu ở đó. Mã lưu `BA` hiển thị là "Công việc"; bước lĩnh vực của khảo sát giao tiếp chọn IT / BrSE (mục tiêu
  dữ liệu `BA`) hoặc Văn phòng, Nhà máy, Dịch vụ (mục tiêu dữ liệu `WORK`, Văn phòng chung luôn có, thêm tối đa 2 lĩnh vực).
- JLPT, ba nhánh theo kana: chưa học → không Smart Test, bắt đầu từ kana; đã học nhưng không nhớ kana và chưa có bài Smart
  Test → không Smart Test (không đọc được đề), bắt đầu N5 kèm ôn kana; nhớ kana → Smart Test, màn kết quả không hỏi lại kana.
  Bài Smart Test đã có luôn được dùng.
- Một lộ trình chính theo mục tiêu khảo sát; thêm được một lộ trình phụ học tự do trong Cài đặt. Đổi mục tiêu chính: lộ
  trình cũ tạm dừng, giữ tiến độ; chỉ hỏi phần của mục tiêu mới.

### 2.2 Lộ trình JLPT

- Nội dung theo cấp N5–N1, cắt theo số mục chuẩn của từng cấp; phần dư là mục "Mở rộng". Mục DRAFT không hiện với người học.
- Bản đồ chia tuần và ngày theo số phút học; học thẻ từ, Kanji, ngữ pháp theo chủ đề; kiểm tra nhỏ, kiểm tra tuần; Đọc ·
  Nghe ở trang luyện riêng, tiến độ theo phần.
- Bỏ qua tuần đã biết (`WEEK_KNOWN`): mở cho người vào từ Smart Test mức Căn bản trở lên, bài tối đa 20 câu, đạt khi đúng
  từ 90%.
- Đáp án nhiễu không lấy từ gần nghĩa cùng nhóm (lời chào, cảm ơn, xin lỗi…).
- Phân biệt chữ dễ nhầm: cặp từ vựng, Kanji và ngữ pháp (`jpath_confusable_pair`); cặp ngữ pháp có giải thích khác nhau ở
  đâu, ví dụ đối chiếu và một câu hỏi chọn. Nguồn soạn: `data/learning-path/confusables/<CẤP>.json`.

### 2.3 Lộ trình giao tiếp

- Mục tiêu → chủ đề → tình huống. Mỗi tình huống 4 bước: Từ & mẫu câu, Nghe từng câu, Nhập vai chọn câu, Nói có chấm
  phát âm (Azure, tối đa 30 lần/ngày, câu dưới 70 nói lại). Không có micro: tình huống vẫn xong, chủ đề chưa đạt.
- 1 hoặc 2 tình huống mỗi ngày, đổi được sau khảo sát; tuần sau mờ và khoá; xong ngày có "Học thêm 1 tình huống".
- CP: 2 mỗi tình huống, 2 khi xong ngày, 5 khi đạt chủ đề, 2 khi đạt kiểm tra tuần, 20 khi đạt bài test cuối; mỗi mốc
  cộng một lần.
- Kiểm tra tuần tối đa 10 câu, đạt từ 80%; câu sai vào Sổ tay, câu đúng ra khỏi Sổ tay.
- Ôn tập giãn cách: từ và mẫu câu quay lại sau 1, 3, 7, 14, 30 ngày, tối đa 10 câu mỗi lượt, không cộng CP.
- Bài test cuối mục tiêu bằng cuộc gọi AI, đạt từ 70, thi lại sau 24 giờ; đạt thì có chứng nhận. Không gọi được AI thì làm
  bài nhập vai tổng hợp có chấm phát âm.
- Kịch bản cuộc gọi AI là dữ liệu của luồng: `data/learning-path/comm-aicall/<GOAL>.json`.

### 2.4 Daily Goal / Streak

- Event receipts xác định và transaction giữ progress/reward idempotent; review/practice không sinh completion event.
- Learner-day từ event instant và IANA ZoneId user, không timezone JVM; kiểm day-boundary/DST/retry/concurrency.
- Freeze/outbox/credit dùng ledger và locking hiện có.

### 2.5 Inline Dictionary / Favorites

- Lookup trong `[data-inline-dictionary]`, loại input/textarea/contenteditable/`[data-inline-private]`; chuỗi Nhật trim, tối đa 100 ký tự.
- Trong bài kiểm tra có chấm điểm khóa trợ giúp tra đáp án; khi học/ôn dùng lookup theo quyền bình thường.
- Server trả detail URL và vocabulary identity; không suy ID/private URL từ selection. History/favorites owner-scoped; save
  retry idempotent theo `(user_id, vocabulary_id)`, anonymous không ghi.

### 2.6 Nguồn và xuất bản nội dung

- Dữ liệu của module/đội khác cần dùng thì sao một bản Learning-owned rồi sửa bản đó, không sửa bản của họ.
- Không ghi tên sách, tên web, tên công cụ AI vào giao diện, dữ liệu hiển thị hay commit. Câu ví dụ là câu tự viết.
- SQL của luồng Learning (owner 2026-11-01, 2026-11-07): `sql/learning/learning_schema.sql` và sáu file data
  `learning_data_N5.sql` (dữ liệu chung ở đầu) → `_N4` → `_N3` → `_N2` → `_N1` → `_comm`, lưu Git LFS, chạy lại được.
  Cách dựng lại: `sql/learning/README.md`.

## 3. AI hỗ trợ

- Tái sử dụng `AiJsonGenerator`, `AiProviderConfig`, `DeepSeekClient` và provider hiện có. Key từ YAML/env, không hardcode/in/log/commit.
- Backend quyết định level/goal/stages/eligibility/quota/unlock/scoring. AI chỉ hỗ trợ narrative, hướng dẫn, giải thích hoặc đề xuất đã được backend kiểm.
- Câu AI mới là draft có ID/key/distractors/explanation/translation/level/goal/provenance/mapping; chưa được phép scored/publish thì chỉ practice riêng. Không runtime AI tự tạo câu scored để bù ngân hàng thiếu.
- Gemini advisory không đồng nghĩa source/teacher approval; cho phép dùng DRAFT của nguồn (§2.6) không xóa trách nhiệm kiểm câu/mapping.
- Narrative lộ trình (E4, 2026-10-27): sinh **sau khi** lộ trình đã commit, ngoài luồng request, ghi riêng cột `ai_plan_json`;
  tạo lộ trình không chờ provider và không giữ khoá trong lúc gọi.
- Timeout, retry hữu hạn và fallback; provider lỗi không làm mất tiến độ. Báo cấu hình SET/UNSET và actual-call riêng, không suy tích hợp đầy đủ từ smoke 200.

## 4. Dữ liệu, schema và phạm vi sở hữu

- Schema chung theo AGENTS và ticket comment; thay đổi DB riêng Learning tập trung trong SQL task-owned, không tự gộp vào file chung khi owner yêu cầu tách.
- Seed giáo trình `seed_data_jlas.sql` do generator sở hữu, không sửa tay; nguồn đội số hóa là read-only cho task Learning.
- `ddl-auto=validate`; startup không tự DDL. Test H2 create-drop không kiểm được raw MySQL CHECK/collation/concurrency; phải có MySQL scratch verification.
- `normalized_expression/normalized_reading = katakana→hiragana(NFKC(text))`, giữ `ー`/`・`; không áp cho `kanji_reading.reading_text`.
  Owner 2026-10-21 (OD-39): ký tự lặp `ヽ`/`ヾ` cũng giữ nguyên như bản JS (không đổi sang `ゝ`/`ゞ`).
- Trước DB write có backup/restore rehearsal theo phạm vi. Không reset tiến độ hoặc chạy destructive seed chỉ để kiểm thử. Quyền reset local trước đây không phải lệnh reset mọi lần.
- Không commit YAML local, token, DB dump tài khoản; không in key/password. Không reset/clean/revert file dirty không rõ ownership.
- Chỉ xóa bản tạm sau khi đầu vào/tham chiếu/kiểm thử đã chuyển sang bản chuẩn; giữ nguồn của nhóm khác.

## 5. Bản cũ đã gỡ

Lộ trình cá nhân hoá V1, lộ trình tương tác V2 và khảo sát mục tiêu cũ đã gỡ khỏi sản phẩm ngày 2026-11-07 (§2). Hằng số
và quy tắc của chúng không còn hiệu lực; bảng DB cũ giữ để không mất dữ liệu người học.

## 6. Điểm chưa được đặc tả và phạm vi thực thi

- Chưa có ngưỡng phân loại mức Smart Test mới; giữ policy hiện có, không tự đặt.
- Chưa có cách xác định trình độ BrSE ngoài thông tin đã có; survey-only không tạo bằng chứng assessment. Chưa có đề xuất sách cho N2+.
- Assessment cao hơn tự khai: chỉ mở route ôn thấp hơn theo lựa chọn tường minh tại §2.3; bảo toàn kết quả assessment và dùng nội dung/stage thực có. Trường hợp ngoài danh sách path vẫn không được mở.
- Replan do mock chưa đạt để phát triển tiếp. Mock không chặn chứng nhận hoàn thành đã chốt.
- Nghiệm thu dựa trên evidence từng case/code/data/runtime, không đánh “đã xong” vì tài liệu đã cập nhật hoặc unit test xanh.
