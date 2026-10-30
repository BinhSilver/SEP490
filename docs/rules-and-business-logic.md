# Rules & Business Logic — Smart Test + Learning Path

Cập nhật: **2026-10-15**. Đây là đặc tả nghiệp vụ chuẩn cho Assessment/Learning.
Tài liệu mô tả hành vi phải đạt; sự hiện diện của một quy tắc không chứng minh code/data/UAT đã hoàn tất.
Bằng chứng chạy và tồn đọng nằm trong execution ledger, không dùng số liệu audit cũ làm điều kiện nghiệp vụ.

## 0. Phạm vi, thuật ngữ và thứ tự áp dụng

- **Survey** lưu riêng trình độ tự khai, kinh nghiệm, nhu cầu, target và thời gian học.
- **Smart Test** lưu riêng cấp được đánh giá, mức **Thành thạo / Căn bản / Nhập môn**, điểm từng kỹ năng và bằng chứng lỗi sai. Không đồng nhất tự khai với kết quả đánh giá hoặc gộp cấp với mức thành thạo.
- **Khởi động** là học nền hoặc ôn điểm yếu trước nội dung mục tiêu; không phải mọi path đều có phần này.
- **Chương JLPT** là lesson nguồn; package là phần của chương theo ngân sách học. **Tình huống giao tiếp** là đơn vị độc lập, không phải chương có khóa tuần tự.
- `BA` là mã canonical của BrSE; UI hiển thị BrSE, đầu vào `BRSE` chuẩn hóa thành `BA`.
- Quyết định owner ngày 2026-10-15 về tạo path/tình huống được tích hợp tại §1.1 và §§2.1–2.3, 2.24. Quyết định bỏ **0→N3** đã được xác nhận; không khôi phục yêu cầu demo cũ này.
- Quyết định owner ngày 2026-10-19: mở ma trận JLPT N5–N1 theo cặp liền kề (§2.1); đồng ý/từ chối khi Smart Test thấp hơn tự khai (§2.3); dùng học liệu DRAFT và câu HOLD của N3–N1 cho lộ trình, chấm bình thường (§2.28); Hội thoại giao tiếp hoàn thành khi luyện đủ từng lượt thoại và tới cuối, không ngưỡng thời gian (§§2.14, 2.24); giữ mô hình chủ đề từ vựng riêng + tình huống hội thoại (§2.24); link sách BrSE trỏ giáo trình trong J-LAS (§2.24). Vẫn giữ bỏ 0→N3.
- Các quy tắc hiện hành dưới đây thay thế ghi chú cũ trái nghĩa. Code chưa khớp là implementation gap; không sửa nghiệp vụ để hợp thức hóa code.
- Hằng số legacy chỉ áp dụng `LEGACY_V1` (§5). Không dùng ngưỡng quiz V1, stage-final hay mock gate cũ cho `INTERACTIVE_V2`.
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
- Survey giao tiếp thể hiện nhu cầu, không chứng minh competency/weakness/mastery. Quy tắc tình huống và tài liệu BrSE xem §2.24.
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
  bài chưa hoàn thành không tạo khởi động ôn lỗi (câu bỏ trống không chứng minh lỗi); người học trả lời câu hỏi đầu vào (§2.3a).
- Owner 2026-10-21 (OD-07): câu chưa được owner rà (100 câu Sekai N5, 12 câu Kanji N4 DRAFT) tạm gỡ tag JLPT khỏi pool; duyệt xong
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

## 2. Learning Path — hợp đồng hiện hành

### 2.1 Danh sách JLPT hỗ trợ và người học từ đầu

| Nhóm | Cặp hỗ trợ | Nội dung cần có |
|---|---|---|
| Từ đầu | 0→N5 | Kana → N5 |
| Từ đầu | 0→N4 | Kana → N5 → N4 |
| Nâng cấp | N5→N4; N4→N3; N3→N2; N2→N1 | Khởi động khi áp dụng → target |
| Củng cố | N5→N5; N4→N4; N3→N3; N2→N2; N1→N1 | Đầy đủ nội dung cùng cấp |

- Không bổ sung loại path ngoài danh sách; **người học từ đầu chỉ có 0→N5 và 0→N4** (0→N3 trở lên không hỗ trợ). Lộ trình nhiều chặng (vd N5→N4→N3) chỉ tạo khi người học xác nhận rõ theo §2.3; mỗi chặng là một cặp liền kề trong bảng, không bỏ cấp (owner 2026-10-19).
- Chỉ tạo path khi mọi chặng có nội dung Learning cho các kỹ năng bắt buộc; cấp thiếu hẳn một kỹ năng phải báo rõ cấp/kỹ năng và không tạo path một phần. Path target N1 dẫn sang Mock N1 của module sở hữu.
  - Triển khai 2026-10-20: kỹ năng bắt buộc của chặng JLPT = Từ vựng, Kanji, Ngữ pháp, Đọc, Nghe. Cổng kiểm tra ở bước dựng gói
    (`learning.interactive-v2.readiness-gate`): cấu hình mặc định `ENFORCE` (OD-48; mã nguồn mặc định `REPORT` chỉ để test tự
    chọn). `REPORT` ghi thiếu sót thành ghi chú không chặn; `ENFORCE`
    từ chối tạo path và nêu "cấp … còn thiếu …". Owner 2026-10-20 (OD-44) giữ REPORT tới khi N3 đủ 5 kỹ năng; sau khi xác minh
    N5, N5→N4, N3 đều đủ trên `jlas`, **owner bật ENFORCE (OD-48)**: `application.yaml` đặt
    `learning.interactive-v2.readiness-gate: ${LEARNING_READINESS_GATE:ENFORCE}` (mã nguồn vẫn mặc định REPORT để test tự chọn).
    Chỉ áp cho path JLPT tạo mới; path đã có và goal giao tiếp không đổi. Trang lộ trình hiện đúng câu "cấp … còn thiếu …".
    DB nào chưa áp `learning_owned.sql` mới (khối Speed N3) sẽ bị chặn tạo path N3. Ma trận N5–N1 đã có trong code nhưng N2/N1
    chỉ mở cùng dữ liệu đủ kỹ năng.
  - Triển khai 2026-10-20 (OD-44, §2.28): chặng N3 dùng thêm giáo trình J-LAS "Tiếng Nhật N3" (sách nguồn lộ trình, vẫn DRAFT)
    qua `DraftStudySourcePolicy` — không đổi trạng thái sách/bài; chương hiện "Giáo trình chưa duyệt — nội dung đang được rà soát.";
    Kanji của sách này chỉ lấy chữ có cấp ≤ cấp chặng (chữ N2/N1 vẫn nằm trong từ); hội thoại chưa có audio thu âm dùng giọng đọc
    tổng hợp có nhãn. N5/N4 giữ quy tắc chỉ dùng sách PUBLISHED.
  - Đọc/Nghe N3 (owner duyệt 2026-10-20): chỉ Speed Master N3 (nguồn VNJPClub, quyền dùng nội bộ như Minna), mỗi chương 2 bài đọc
    + 2 bài nghe, dễ → khó (đọc: ngắn → trung bình / tìm thông tin → trung bình → dài; nghe: nhiệm vụ → điểm chính / diễn đạt → khái
    quát). Bản sao Learning `LP-OWNED-SPEED-N3-Cnn-READ|LISTEN-Pp-Qqqq` (mục `jlpt_speed_n3_comprehension` trong JSON chuẩn), chỉ dùng
    cho lộ trình, không vào Smart Test/quiz chấm điểm; nhãn "Nguồn VNJPClub · Speed Master N3 · chưa duyệt". Chỉ chấm câu một đáp án,
    3–4 phương án in sẵn, đáp án khớp nguồn độc lập (nghe: 【正解】 trong lời thoại; đọc: cờ phương án đúng); 8 bài bị giữ sau rà soát
    (thiếu bảng/biểu đồ/hội thoại, đáp án tranh chấp). Câu VNJPClub gốc giữ nguyên `is_active=0`. Có hiệu lực khi khối seed đã áp vào DB.
  - Đọc thêm N5/N4 (OD-08, owner 2026-10-24): Speed Master N5 vào chương Sekai N5 13–25, Speed Master N4 vào chương Sekai N4
    26–50 (số chương như bộ Minna), chia đều theo thứ tự dễ → khó của sách; xếp sau bài đọc Minna của chương. Bản sao Learning
    `LP-OWNED-SPEED-{N5|N4}-Lnn-READ-Pp-Qqqq` (mục `jlpt_speed_n5_n4_reading`), chỉ dùng cho lộ trình, không vào Smart Test/quiz;
    nhãn "Nguồn VNJPClub · Speed Master · chưa duyệt"; bản dịch từng dòng của nguồn ở `context_json.passage_translation` (như Minna).
    Giữ lại (không chấm) bài phương án là tranh/nhãn tranh, bài tìm thông tin và bảng bị phẳng khi xuất, câu đáp án chưa đối chiếu.
    Phương án bị cắt số đầu khi xuất ("10月…" → "0月…") lấy lại từ dòng trên trang nguồn khi khối phương án không đánh số.
    Chỉ lộ trình tạo sau khi áp seed mới có bài này (package dựng một lần lúc tạo path). 12 bài N4 còn thiếu so với khung 48 bài:
    làm sau, duyệt riêng.
  - Hỗ trợ tự sinh có nhãn (Ngày 2–3, 2026-10-24, theo OD-06; không ghi vào dữ liệu dùng chung, không đổi revision):
    - Loại từ: khi `vocabulary_entry.word_type` trống, thẻ Từ vựng hiện loại từ Learning suy từ IPADIC (kuromoji) của cách viết
      (nhóm động từ theo kiểu chia, tính từ い/な, danh từ, phó từ…; chuỗi nhiều từ là "Cụm từ"; dấu ～ đầu là "Hậu tố"); không
      hiện ở mặt trước thẻ. Owner 2026-10-24 (D3-09): Claude rà toàn bộ, owner coi như duyệt → **bỏ nhãn "· tự gán"**. Bản rà
      `learning/word-type-reviewed.json` (`build_word_type_reviewed.mjs`): 79 từ kana bị tách sai/đọc sai lớp được sửa theo
      cách viết; mã thô của Dictionary (`v`, `n`, `a-na`, `a-i`, `suf`, `adj`, `PHRASE`, "Động từ nhóm 2"…) hiện bằng nhãn
      Việt (mã `v` lấy nhóm động từ tự suy). `vocabulary_entry.word_type` không bị sửa; thẻ Flashcard khởi động giữ nguyên.
    - Furigana hội thoại JLPT: dòng có chữ Hán mà không có furigana đã rà soát (chương sách "Tiếng Nhật N3") nhận furigana tự
      sinh cùng định dạng `{漢字|かな}` (owner duyệt nội dung 24/10, D3-02: không nhãn); công tắc "Furigana" mặc định tắt, lưu lựa chọn riêng. Số,
      lượng từ sau số và tiếp đầu ngữ một chữ Hán để trống (thà không có còn hơn đọc sai). Lời thoại N5/N4 toàn kana nên không đổi.
  - Lời giải Đọc N3: 8 câu tìm thông tin mà nguồn không có lời giải dùng lời giải J-LAS soạn bổ sung (đáp án nguồn được
    tính lại trước, cả 8 khớp), nhãn "Lời giải J-LAS soạn bổ sung (chưa duyệt)".
  - Audio Nghe Minna: catalog phát bản trong repo (`data/content-audio/vnjpclub/<cấp>/minna-so-cap-<n>/<thư mục nguồn>_<tệp>`,
    Git LFS) khi bản đó có trong `content.audio.path`, nếu không thì `minna/…` của gói audio cài riêng (cùng nội dung byte);
    đường phát không vào fingerprint. Manifest SHA-256: `data/learning-owned/learning-audio-manifest.json`.
  - Danh mục hợp nhất N5–N3 (`canonical_catalog_n5_n3`, `CANDIDATE_NOT_APPLIED`): mọi sách trong repo ánh xạ về ID canonical
    (từ = cặp chuẩn hoá §4, Kanji = code point, ngữ pháp = chuỗi mẫu chuẩn hoá; gộp theo chức năng cần người duyệt). Bộ tạo
    lộ trình vẫn đọc sách chương; chuyển sang đọc danh mục là quyết định của owner.
- Người chưa từng học phải học Kana trước; người đã học nhưng mức Nhập môn ở N4 không tự bị đưa về Kana.
- Người `UNKNOWN` dùng kết quả thực tế để đề xuất điểm bắt đầu trong các chặng hỗ trợ; không suy diễn level từ survey preference.

### 2.2 JLPT thông thường và chọn nội dung

Áp dụng sau khi xử lý chênh lệch cấp và ngoại lệ củng cố tại §2.3.

| Mức tổng | Hành vi |
|---|---|
| Thành thạo | Không khởi động; học target đã đồng ý. Không ghi giả hoàn thành cấp bỏ qua |
| Căn bản | Khởi động theo lỗi sai của tất cả skill; xong chuyển target. Không bắt học lại cả cấp chỉ vì sai một số kiến thức |
| Nhập môn | Học lại từ đầu phần nhập môn tương ứng rồi tới target; dùng cấp cụ thể đã được đánh giá, không tự quy về 0 |

- Nội dung eligible phải đúng level/goal, đủ member/mapping/source. Bài đã xuất bản trong kho Learning riêng mới được runtime chọn; trạng thái DRAFT ở sách nguồn không cấm sử dụng bản sao Learning đã được owner cho phép (§4).
- Reading/Listening khởi động lấy từ nguồn luyện tập hiện có đúng điểm yếu/cấp. Không fallback sang cấp gần nhất hoặc bài không liên quan để che thiếu dữ liệu.
- Khử trùng theo canonical target. Nếu bài ôn trùng đầy đủ với bài target, dùng một bài đầy đủ và gắn lý do ôn; không tạo hai bản bắt buộc giống nhau.
  - OD-D2-05 (owner 2026-10-23), path JLPT tạo mới: một từ vựng/Kanji chỉ là member bắt buộc (học + kiểm tra) ở chương đầu tiên
    dạy nó; chương sau liệt kê nó ở mục "Ôn — đã học ở bài …" (không thẻ, không tính quiz). Cùng từ nhưng nghĩa theo bài khác
    (`learning_meaning_vi`) vẫn bắt buộc ở bài sau. Chương còn đúng 1 mục mới giữ thêm 1 mục đã học để câu chọn đáp án có phương
    án nhiễu thật. Path đã tạo và goal giao tiếp không đổi. Mẫu ngữ pháp lặp (ôn xoắn ốc của sách) chưa khử trùng — chờ rà soát.
- Gap như `CONTENT_MAPPING_PENDING`, `MISSING_WARM_UP_RESOURCE`, `INSUFFICIENT_ASSESSMENT` phải hiển thị rõ; quota/ngân sách chỉ dời nội dung sang ngày khác, không làm rơi lỗi sai.

### 2.3 Củng cố cùng cấp và chênh lệch trình độ

**Nếu assessed phù hợp cấp củng cố đã chọn:**

- Thành thạo: đề xuất lên cấp kế tiếp (N5→N4, N4→N3, N3→N2, N2→N1). Đồng ý mới đổi; từ chối vẫn tạo full cùng cấp. N1 Thành thạo không có cấp kế tiếp.
- Căn bản: full cùng cấp, không có warm-up riêng; popup bên dưới bảng mục tiêu nêu lỗi sai, chương chứa kiến thức và link chương thật.
- Nhập môn: full cùng cấp từ đầu cấp đó, không quay về Kana.

**Nếu assessed thấp hơn tự khai:** xử lý chênh lệch trước nhãn thành thạo.

- Giải thích chênh lệch và cho đồng ý/từ chối đề xuất củng cố nền.
- Đồng ý: bắt đầu theo assessed, củng cố theo mức thực tế rồi đi từng chặng hỗ trợ đến target. Ví dụ N5→N4→N3 hoặc ôn lỗi N4 rồi N4→N3. Không đổi N4 Căn bản thành N5 và không tạo ôn trùng không cần thiết.
- Từ chối: học như path thường theo survey (tự khai → target, ví dụ N4→N3), kèm cảnh báo nền tảng/đầu ra; không tự đổi target hoặc chặn tiếp tục (owner 2026-10-19).
- Kết quả cao hơn tự khai: giữ nguyên kết quả Smart Test. Người học có thể tự chọn và xác nhận ôn từ một cấp đã phát hành không cao hơn target; hệ thống tạo đủ từng chặng liên tiếp từ cấp đã chọn đến target. Ví dụ kết quả N1, target N3: người học chọn N5 thì học N5→N4→N3; chọn N4 thì N4→N3; chọn N3 thì học N3. Không auto-skip/đổi target và không suy ra người học đã hoàn thành cấp nào. Không có xác nhận rõ ràng thì không tạo path; cặp target vốn không hỗ trợ vẫn bị chặn.
- Các chặng sinh ra đều phải là cặp liền kề của bảng §2.1; 0→N3 trở lên vẫn không được phát hành.
- Owner 2026-10-21 (OD-35): người tự khai `UNKNOWN` mà Smart Test thấp hơn target từ 2 cấp trở lên dùng cùng luồng Đồng ý/Từ chối ở
  trên (đề xuất học từ cấp assessed lên).
- Owner 2026-10-21 (OD-37): Smart Test xếp Nhập môn trong cặp N5→N4 tạo path [N5 > N4] không cần xác nhận; màn kết quả ghi rõ vì
  sao có chặng N5. Owner 2026-10-25 đổi thành câu hỏi xác nhận (§2.3a).

### 2.3a Câu hỏi đầu vào trước khi tạo path JLPT (owner 2026-10-25)

Server (`LearningEntryPolicy`, API `GET /api/learning/paths/entry-questions`) quyết định câu hỏi; màn kết quả Smart Test hỏi đúng
danh sách đó; tạo path thiếu câu trả lời bắt buộc hoặc trả lời câu không được hỏi đều bị từ chối. Mỗi bộ câu trả lời là một path
riêng (mã ngắn trong `policy_version`). Các luồng có sẵn (kết quả cao hơn target → chọn cấp ôn; thấp hơn tự khai → đồng ý/từ
chối; UNKNOWN xa target → OD-35) giữ nguyên và không hỏi thêm.

| Kết quả | Câu hỏi | Trả lời → path |
|---|---|---|
| Chưa hoàn thành (mọi cấp) | Tự khai N5/UNKNOWN: "đã đọc được bảng chữ cái chưa?" | Chưa → Kana rồi N5 … target |
| | Rồi hỏi "chưa xác định được bạn đã vững [cấp tự khai] chưa" (khi cấp đó < target); có nút "Làm lại Smart Test" | Vững → từ cấp kế tiếp; Chưa vững → học thêm cấp đó. Không khởi động ôn lỗi |
| Hoàn thành, dưới N5 | "đã đọc được bảng chữ cái chưa?" | Chưa → Kana + N5 …; Rồi → N5 từ chương đầu, không Kana |
| Hoàn thành, Nhập môn dưới target | "Học lại [cấp] trước?" | Học lại (khuyến nghị) → cấp đó … target; Bỏ qua → từ cấp kế tiếp |
| Hoàn thành, Căn bản dưới target | Nêu số câu sai theo kỹ năng, "Ôn lỗi trước?" | Ôn → khởi động ôn lỗi rồi target; Bỏ qua → vào thẳng, không khởi động |
| Thành thạo / cùng cấp target | Không hỏi | Như §2.2–2.3 |

### 2.4 Ngân sách và xếp lịch JLPT

- Phút/ngày là trần workload ước lượng, không phải giới hạn người dùng được truy cập web.
- Chia nội dung theo thứ tự nguồn, giữ đầy đủ member; chuyển phase sang ngày khác. Reading/Listening/Dialogue là bài atomic, không cắt ghép tùy ý.
- Hằng số ước lượng V2 hiện có: Vocabulary/Foundation/Dialogue 30 giây/member; Kanji 45; Grammar 60; mỗi batch check ≤10 member dự trù 3 phút; comprehension atomic dự trù 15 phút. Đây là heuristic, không phải thời gian đo thực tế.
- Owner 2026-10-24 (D3-05): ngày học JLPT chỉ tính theo phút (khảo sát và đổi nhịp: 30/45/60/90; lộ trình cũ 15/20/120/180
  giữ nguyên). Bỏ trần đếm theo ngày (trước: Grammar ≤3 point, Reading ≤1, Listening ≤1/ngày); một part Grammar vẫn ≤3 point.
  Chương sách tổng hợp sau học tiếp phần phút còn lại của ngày chương trước kết thúc (nhiều chương/ngày), nên số ngày ≈
  khối lượng ÷ phút/ngày; mỗi chặng mới bắt đầu ngày mới, không chung ngày với warm-up; sách khác giữ mỗi gói một ngày.
  `learning_path_package.day_number` vì vậy không còn duy nhất theo path (migration 052, áp cùng lúc với khối sách tổng
  hợp). Người học xong sớm thì mở gói kế tiếp ngay, số ngày còn lại tự rút ngắn.
  Nội dung atomic không vừa budget phải báo gap trước khi ghi path, không tự vượt trần. Không tạo part singleton nếu quiz cần ≥2 lựa chọn thật.
- Quy tắc thứ tự/khóa JLPT không áp dụng tình huống giao tiếp (§2.24).

### 2.5 Ownership, idempotency và version

- Path/attempt/member thuộc chính learner đăng nhập; không nhận evidence hoặc answer key từ request JSON.
- Tạo lại cùng user/survey hoặc attempt/revision/policy version trả cùng path theo identity của loại path; không tạo bản trùng do refresh/retry.
- Profile/goal-survey phải được nộp hợp lệ; attempt mới hoặc lựa chọn mới chỉ thay active path qua nghiệp vụ đã xác nhận. Lưu path/history cũ, không reset tiến độ.
- Composition và apply thay đổi có transaction/locking; thiếu nội dung phải dừng rõ trước partial writes.

### 2.6 Quiz JLPT và draft bài làm

- Interactive V2 dùng check của activity; không áp lesson/gate/stage-final V1 vào V2 (§5).
- Snapshot đề/thứ tự/options/answer key một lần trên server; option 1-based. Reopen attempt `ISSUED` của đúng user/activity/batch trả cùng attempt và câu cũ.
- Autosave/restore phải giữ đáp án, matching và vị trí đang làm; retry/out-of-order/two-tab không được ghi đè trạng thái mới bằng bản cũ. Không chấm từ draft hoặc tự nộp chỉ do lưu draft.
- Submit hợp lệ mới tính lượt; retry cùng submit trả kết quả cũ; lỗi kỹ thuật/validation không mất lượt.
- V2 mặc định check ≥70%, tối đa 3 lần hợp lệ/cycle; lần trượt thứ ba cooldown 60 phút + ôn mục sai trước cycle mới. Học liệu/practice vẫn truy cập được khi cooldown.
- Quiz học `INTERACTIVE_V2` **không giới hạn thời gian**, không hiển thị đồng hồ đếm ngược, không tự nộp hoặc chấm sai vì hết giờ. Thời gian chờ 60 phút sau lần trượt thứ ba là quy tắc retry riêng, vẫn áp dụng. Trong quiz chấm điểm phải ngăn hỗ trợ tra đáp án theo yêu cầu fairness đã chốt; không biến khóa lookup thành cơ chế chống gian lận tuyệt đối. Quy tắc thời gian riêng của Smart Test, Mock Test và trò chơi không bị thay đổi bởi quyết định này.
- Nút quiz phải thấy ngay ở tab nội dung và tab Flashcard. Chưa lướt hết thẻ thì hỏi bằng dialog J-LAS: vẫn kiểm tra hay tiếp tục ôn; không khóa cứng quiz chỉ vì chưa lướt thẻ.
- **Không có quiz trong bài tình huống BA/Travel/Daily.**

### 2.7 Replan theo kết quả học

- Đề xuất → preview lý do/thay đổi → learner xác nhận → apply. Preview không ghi path.
- Gợi ý hiện có: >90% tăng tốc, <60% ôn thêm; không tự đổi ngưỡng, target hoặc ghi mastery.
- Bảo toàn completed prefix, attempts, CP, streak và member identity; retry không tạo version/thưởng trùng.
- Replan theo mock chưa đạt là hướng phát triển tiếp; mock không tự bắt replan hoặc hủy chứng nhận.

### 2.8 Flashcard và remediation ít mục

- Thẻ chỉ lật/lướt/nghe và chủ động kiểm tra; không tự thêm self-grade “Đã nhớ”/“Chưa thuộc” hoặc speed-recall vào Learning.
- Chưa xem hết: dialog J-LAS có `Vẫn kiểm tra` / `Ôn hết thẻ`; không native `window.confirm`.
- Lật thẻ lưu tiến độ xem, không tự cấp mastery/pass/completion bài.
- Riêng lỗi Smart Test: từng skill Vocabulary/Kanji có 0–4 canonical target sai hợp lệ thì chỉ danh sách read-only, không thẻ/quiz/quota/mastery. ≥5 mới remediation full flow. Gap mapping đếm riêng.
- Ngưỡng này không loại 2–4 từ của bài giáo trình; quiz vẫn cần ít nhất hai lựa chọn thật. Không sửa module Flashcard của nhóm khác để thực hiện flow Learning.

### 2.9 Bài Reading/Listening

- Phải có nguồn đúng level, nội dung và câu hỏi/đáp án liên quan; nghe có audio khớp chính xác script/bài. Không ghép track theo tên gần giống.
- Lộ trình đầy đủ phải có Reading/Listening trong giai đoạn học chính, không dùng riêng warm-up hoặc vài bài vocab để đại diện đủ kỹ năng.
- Bài đọc giữ đoạn/ngắt dòng. Bài nghe theo flow §2.31; không cần một quiz rời lặp lại sau câu hỏi thuộc bài.
- Câu hỏi/source phải được snapshot cùng attempt; review không lấy nguồn hiện tại thay cho snapshot thiếu.
- OD-D2-04 (owner 2026-10-23) — nguồn đổi sau khi path đã tạo: khi mở activity có revision cũ, nếu mọi câu đã được trả lời
  không đổi đề/phương án/đáp án (bỏ qua khoảng trắng) thì activity chuyển sang revision hiện tại; attempt cũ giữ snapshot, điểm
  không đổi. Câu đã trả lời mà đổi thật thì activity vẫn bị chặn (không tự chấm lại). Câu bị HOLD trong activity chưa mở được gỡ
  khỏi membership; activity rỗng rút khỏi phần bắt buộc.
- OD-D2-03 (owner 2026-10-23): câu Nghe điền từ không có lời giải dùng lời giải trích chính lời thoại nguồn
  ("Lời giải trích lời thoại (m:ss–m:ss): 「…」 — … Đáp án: …"); không sinh nội dung ngoài trích đoạn.

### 2.10 Chuyển chặng, hoàn thành và chứng nhận

- JLPT đi theo các chặng bắt buộc của path đã xác nhận; chỉ chuyển khi yêu cầu học/chấm của chặng hoàn tất. Không thêm stage-final V1 vào V2 nếu chưa được duyệt.
- Hoàn thành tất cả nội dung bắt buộc → server ghi path `COMPLETED` và `completed_at` một lần. Một pilot/fixture đánh dấu sẵn không phải bằng chứng learner học hết giáo trình.
- Hoàn thành path ghi nhận trạng thái hoàn thành thật. Với path JLPT, giấy chứng nhận lộ trình chỉ mở sau khi learner có lượt mock đúng target mới nhất được server chấm là đạt; trước đó summary chỉ hiện lời chúc hoàn thành, không gọi đó là chứng nhận. Path giao tiếp không có mock JLPT và vẫn theo điều kiện hoàn thành path.
- Mock JLPT là tự đánh giá đúng target sau học; đạt/trượt không chặn completion. Giao tiếp không có mock JLPT.
- Khi mock đạt theo lượt đã được server chấm và lưu, người học có thể xem lại báo cáo lượt thi và nhận riêng **Chứng nhận đạt bài thi thử JLPT của J-LAS**. Đây là chứng nhận tự đánh giá nội bộ, không phải chứng chỉ JLPT chính thức; không thay thế hoặc gộp với chứng nhận hoàn thành lộ trình.

### 2.11 Vocabulary study (V2)

- Nội dung và flashcard dùng cùng ordered membership/context; part tối đa 15 mục, ưu tiên 10–15 khi có thể.
- Check theo batch tối đa 10 target; đuôi singleton phải cân bằng mà không làm mất hoặc lặp member.
- Hỗ trợ MCQ hai chiều/reading/meaning và matching. Matching có chọn, bỏ chọn, chọn lại bằng chuột/bàn phím/touch; hai cột trộn độc lập nhưng snapshot/chấm nhất quán.
- first/latest/best và số correct/question lưu theo batch, không average các phần không cùng mẫu số.

### 2.12 Kanji study (V2)

- Cùng hợp đồng attempt/quota/snapshot với §2.6, dùng membership/quiz type Kanji riêng.
- Hiển thị glyph lớn, On/Kun/meaning/strokes theo dữ liệu thật. Thiếu reading không bịa; chỉ dùng dạng câu hỏi có dữ kiện hợp lệ hoặc báo gap.
- Điểm Kanji không bù/ghi đè Vocabulary. Không suy diễn từ chữ đơn sang từ ghép không có mapping.
- ~~Owner 2026-10-21 (OD-13): "nghĩa" Kanji = âm Hán Việt + nghĩa EN KANJIDIC2.~~ Thay bằng owner 2026-10-24 (D3-03): thẻ Kanji
  hiện Hán Việt + nghĩa tiếng Việt VNJPClub (bản Learning `learning/kanji-meaning-vi.json`, sinh bằng
  `build_kanji_meaning_vi.mjs` từ sách tổng hợp; chữ VNJPClub thiếu nghĩa do J-LAS soạn, liệt kê ở `written`), ghi nguồn tại chỗ;
  không hiện tiếng Anh. Nghĩa này cũng dùng cho câu hỏi "Chữ X có nghĩa là gì?" và nối cặp; chữ ngoài sách dùng
  `kanji.meaning_vi` của Dictionary. Danh sách cấp Waller (OpenJLPT) dùng tạm, ghi `NOT_VERIFIED`.

### 2.13 Grammar study (V2)

- Mỗi point có pattern, nghĩa, ví dụ/dịch và practice xếp câu + điền từ; feedback nằm dưới đúng bài.
- Grammar practice gate, khi áp dụng cho JLPT, dựa trên progress/revision thật, không dựa vào DOM.
- Quiz không lặp nguyên văn ví dụ vừa học; phải có question/answer/distractors/explanation/source hợp lệ. Không dùng câu ví dụ làm đáp án mới để lách yêu cầu này.
  - Khi chưa có câu áp dụng đã duyệt, micro-check chỉ gồm câu nghĩa (MEANING_MATCH). OD-D2-01 (owner 2026-10-23): câu áp dụng
    do AI soạn chỉ vào quiz sau khi owner duyệt (`DUYET`) trong bảng duyệt; runtime đọc bản `learning/grammar-application-reviewed.json`,
    mỗi mẫu tối đa 1 câu/attempt, kèm lời giải và câu đúng. Câu xếp thứ tự (ORDER) chưa vào quiz chọn đáp án.
  - Ví dụ lệch mẫu được ẩn ở Learning (ghi chú "đang được rà soát") và không đưa vào prompt AI cho tới khi đội nội dung sửa.
- Bài xếp câu/điền từ dùng mảnh theo cụm (kuromoji); không mảnh nào bắt đầu bằng dấu câu; mảnh ngoài mẫu dài từ 9 chữ được
  cắt lại theo từ. Câu dài hơn 36 chữ chỉ luyện các mệnh đề (tách tại 、) quanh mẫu, ưu tiên giữ phần cuối câu (owner 2026-10-23).
- Chấm xác định từ key đã snapshot; normalization không làm nhiều phương án cùng đúng.
- Người học JLPT có thể vào quiz ngữ pháp trước khi luyện xong mọi mẫu, sau dialog J-LAS giải thích và cho chọn tiếp tục luyện. Tiến độ luyện xếp câu/điền từ vẫn lưu riêng; bỏ qua luyện tập không tự tính hoàn thành hay đạt điểm.
- Bài ngữ pháp bên trong tình huống giao tiếp là nội dung học, không phát sinh quiz/gate.

### 2.14 Dialogue và bằng chứng học

- Dialogue có script, dịch, vai và audio nguồn; thiếu audio/script phải báo nội dung chưa sẵn sàng.
- Hợp đồng V2 JLPT hiện có dùng active-time do server đo + reached-bottom; client không tự gửi completed/duration. Mốc hiện có 180 giây, heartbeat credit giới hạn 20 giây, gap >30 giây không credit; retry token không cộng trùng.
- Dialogue không có điểm quiz; đóng góp completion theo evidence hợp lệ. Review/practice không ghi heartbeat/reward.
- Hội thoại trong tình huống giao tiếp hoàn thành khi người học đã luyện đủ từng lượt thoại (thao tác luyện theo từng lượt) và đã tới cuối bài; không dùng ngưỡng thời gian 180 giây (owner 2026-10-19). Mở trang, cuộn đến cuối hoặc chỉ bấm phát audio không tính hoàn thành.
- Owner 2026-10-23: hội thoại JLPT (và màn xem lại) có cùng công cụ hiển thị như giao tiếp — bật/tắt bản dịch, luyện đóng vai
  (ẩn câu của vai đã chọn tới khi bấm "Xem câu"), công tắc furigana khi dòng có furigana. Quy tắc hoàn thành JLPT không đổi.
  Lời thoại Sekai N5/N4 viết bằng kana nên không cần furigana; furigana cho N3 chờ owner quyết.

### 2.15 Package completion, score và phần kế tiếp

- Package hoàn tất khi mọi activity **thực sự bắt buộc có trong package** đạt terminal state. Không yêu cầu cứng đủ bốn activity cho bài chỉ có một loại nội dung hợp lệ.
- JLPT mở tiếp theo thứ tự đã compose; Dialogue terminal `COMPLETED`, các check tương ứng `PASSED`. Read lại package dưới lock nhất quán để tránh race khi completion song song.
  - Mọi segment/package thuộc cùng một chương (cùng `source_lesson_id`) được mở đồng thời khi chương đó đến lượt; người học tự chọn kỹ năng và phần học trong chương, không phải hoàn thành lần lượt từng package. Chương kế tiếp vẫn khóa đến khi tất cả hoạt động bắt buộc trong mọi segment của chương hiện tại hoàn thành. Mở trang hoặc chọn kỹ năng không tự ghi pass. Package không có source lesson vẫn là một chương độc lập.
- Score lấy từ submitted snapshots; pool correct/question qua các part của cùng skill rồi mới tổng hợp theo policy. Không tính Dialogue hoặc skill thiếu evidence là 0; không dùng tổng điểm để che activity chưa đạt.
- Reward xem §2.20b; package completion không tự thưởng 5 CP mỗi phần.
- `justCompleted` là tín hiệu của request thực hiện transition, không tự biến thành completion mới mỗi lần reload.

### 2.16 Warm-up personalization (JLPT)

- Chỉ tạo warm-up ở nhánh quy tắc cho phép (§§2.2–2.3); cùng cấp Căn bản không thêm warm-up riêng.
- Mỗi item sai phải resolve canonical anchor/revision hoặc gap; không biến survey preference thành weakness.
- Reading/Listening dùng bài luyện hiện có để phủ điểm yếu. Hàm band hiện có `>70→0`, `(50,70]→4`, `(40,50]→6`, `[0,40]→8`, thiếu mẫu→needs verification là chi tiết planner, không cho phép bỏ lỗi sai hoặc tự áp khác đặc tả Căn bản.
- Warm-up đứng trước target, không tạo trùng target hoặc làm target composer idempotency tưởng đã có GOAL_LEARNING.

### 2.17 Summary và certificate UI

- Summary truy cập được với path mới nhất đã hoàn thành, không chỉ ACTIVE. Link từ bài cuối phải mở summary/roadmap completed thật; không đẩy về survey để che path đã hoàn thành.
- Hiển thị first/latest/best theo skill, unresolved batches, Dialogue hoàn tất/tổng; dữ liệu thiếu nói rõ, không tạo evidence giả.
- Trang chứng nhận đọc completion server và, với path JLPT, xác nhận learner đã đạt lượt mock đúng target; mở lại không sinh điểm/thưởng/attempt. Path giao tiếp không áp điều kiện mock.

### 2.18 Comprehension evidence và readiness

- Tách baseline Smart Test khỏi Reading/Listening đã học trong path; không trộn hai nguồn như một lần đánh giá.
- Batch in-path chưa đạt là gap phải hiện; không bị che bởi trung bình cao.
- Ước tính readiness nội bộ hiện giữ confidence tối đa MEDIUM; không coi đó là xác nhận đỗ JLPT hoặc tự nâng HIGH.
- Chưa đủ evidence phải ghi “còn thiếu bằng chứng”; không bịa evidence để tài khoản demo trông hoàn chỉnh.

### 2.19 Mock Test handoff

- Dùng `MockTestDestinationProvider` nhận target server-owned; UI không tự ghép URL từ input người dùng.
- Route nội bộ hoặc HTTPS origin allowlisted; cấm protocol-relative URL, scheme nguy hiểm và lộ JWT/path-private IDs.
- `/jlpt` có module thực tế; nếu provider trả route này phải truyền đúng filter target. Không còn dùng nhận định lịch sử “mock không tồn tại” làm sự thật hiện tại.
- Chỉ hiển thị available khi tích hợp có thể dùng; BLOCKED_INTEGRATION phải nói rõ. Thi → nộp → chấm → xem/lưu kết quả là gate kiểm chứng riêng, không suy ra từ link mở được.
- Owner 2026-10-26 (lỗi #3): **một điều kiện mở Thi thử cho cả lộ trình và trang tổng kết** — mọi chặng trước 100% và chặng mục
  tiêu ≥ 50% (`LearningFinalEligibilityService`). Chưa đạt thì summary trả `mockTest.status = LOCKED_PROGRESS` kèm `reasonCode`
  (`PRIOR_STAGE_INCOMPLETE` / `TARGET_STAGE_BELOW_THRESHOLD` / `BLOCKED_CONTENT`), không có link; trang hiện nút "Thi thử JLPT
  (chưa mở)" cùng câu lý do như lộ trình.

### 2.20 Legacy attempt-gated completion — giới hạn tương thích

- Endpoint/adapter legacy yêu cầu attempt JLPT có thể giữ fail-closed để tương thích.
- Không áp điều kiện target progress 50%, policy-version hoặc result-store legacy vào completion V2. Path JLPT vẫn chỉ phát hành trang chứng nhận sau khi mock đúng target mới nhất đạt (§2.10); điều kiện này không chặn path chuyển sang `COMPLETED`.
- Hợp đồng nghiệp vụ mới ở §§2.10, 2.19 và 2.20a; kết quả mock không thay trạng thái đã hoàn thành path.

### 2.20a Mock phương án A

- Mock tự đánh giá; path completion do Learning quyết định từ yêu cầu học thực tế.
- Summary dẫn sang mock đúng target khi sẵn sàng; không có mock cho BA/Travel/Daily.
- Summary có thể chúc mừng hoàn thành path mà không tuyên bố đó là chứng nhận. Chứng nhận path JLPT cần mock đúng target đạt; chứng nhận mock là tự đánh giá nội bộ, không tuyên bố đỗ JLPT chính thức. Path giao tiếp không cần mock.

### 2.20b Chương và CP

- Một source lesson JLPT là chương; các segment/package là phần của chương, không phải chương mới.
- Hoàn tất toàn bộ phần bắt buộc của chương mới thưởng **5 CP một lần**. Phần đầu chưa đủ chương thưởng 0; retry/concurrency/replan không cấp lại.
- Ledger dedup bằng `SOURCE_LESSON:<id>` theo identity người học hiện có. Streak/activity receipts không đồng nghĩa mỗi package được CP.
- Giao tiếp không có thưởng theo chương; chỉ có 2 CP một lần cho mỗi tình huống hội thoại khi vượt ải thử thách lần đầu (§2.24, owner 2026-10-19).

### 2.20c Grammar sai nhưng thiếu anchor

- Giữ question/lexeme/pattern/source evidence để hiển thị vấn đề, không tạo grammarPointId giả hoặc chọn mẫu có chuỗi gần giống.
- Không tìm được mapping duy nhất giữ `CONTENT_MAPPING_PENDING`; danh sách read-only không tính lesson/quiz/quota/mastery.

### 2.21 LEARN / REVIEW / PRACTICE

- LEARN mới ghi tiến độ theo đúng loại activity. REVIEW read-only không tạo session/card/attempt/reward và có thể xem khi batch khác còn dở.
  - Owner 2026-10-23, mọi goal: "Xem lại bài giảng" của phần **đã hoàn thành** mở lại như lần đầu (thẻ chưa lật, bài luyện
    Ngữ pháp mở lại và chấm trên trang, Đọc/Nghe mở đoạn + câu hỏi trống); quiz đi qua PRACTICE_REPLAY nên mỗi lần làm được lưu ở
    "Kết quả kiểm tra", không đổi state/score/unlock/CP. Phần chưa hoàn thành vẫn dùng REVIEW read-only như trên.
- Kết quả/history nằm ở quiz/result, lesson review không trộn điểm/feedback vào nội dung bài giảng.
- PRACTICE_REPLAY lưu riêng, không dùng quota scored, không đổi state/score chính/unlock/CP; retry submit idempotent.
- Reading/Listening replay dùng frozen passage/audio/script/translation đúng batch; snapshot legacy thiếu báo `SOURCE_SNAPSHOT_UNAVAILABLE`, không lấy nguồn hiện tại thế chỗ.

### 2.22 Đổi nhịp

- Preset 15/20/30/45/60/90 phút (D3-05 thêm 90; khảo sát chỉ mở 30/45/60/90, 15/20 là nấc nhẹ khi đổi nhịp);
  LIGHTER/FASTER từng nấc, SAME giữ nguyên; đầu/cuối no-op (nhịp cũ ngoài dải, vd 120, không bị đẩy ngược chiều).
  Gói sách tổng hợp kế tiếp cùng chặng/phase dùng tiếp phút còn lại của ngày, như composer.
- Owner 2026-10-25: chương mục tiêu chưa ai bắt đầu được chia lại phần theo phút mới (mỗi phần một ngày; nhanh hơn thì ít phần
  hơn, chậm hơn thì nhiều phần hơn), dùng lại hàng package theo thứ tự, thêm hàng khi cần, bỏ hàng thừa (không có lịch sử). Hoạt
  động/member giữ nguyên dòng (tách phần thì thêm dòng như cũ). Chương có phần đã bắt đầu và khởi động chỉ dời ngày. Lộ trình vẫn
  hiển thị theo chương → phần, không nhãn ngày.
- Preview không ghi, apply xác nhận token/fingerprint và khóa path→package→activity; dữ liệu thay sau preview làm token stale/CONFLICT.
- Chỉ xếp lại phần chưa bắt đầu, giữ ordered completed/started prefix, attempts và member identity. Logical package vẫn là đơn vị progress, không tạo thêm thưởng bằng chia part.
- Không split bài atomic/đổi nội dung đã có history; thiếu ngân sách/member hợp lệ báo trước khi ghi. Lượt apply lặp idempotent.
- Ngoại lệ giao tiếp không bị biến thành lịch khóa tuần tự qua thao tác đổi nhịp.

### 2.23 UI/UX và điều hướng

- Survey options/reasons từ policy server; không hardcode fallback cho option mà API từ chối.
- Roadmap JLPT thể hiện stage/chương/phần, completed/total/% và tiếp tục/xem lại; giao tiếp thể hiện tình huống tự chọn.
  - Owner 2026-10-23: tiêu đề chương gọn ("CHƯƠNG n · MỤC TIÊU · Chọn kỹ năng muốn học" + %); thẻ kỹ năng không hiện
    "x/y hoạt động" và không có nút xổ — bấm thẻ mở trang kỹ năng (`#chuong=…&ky-nang=…`) chứa các phần với bắt đầu/tiếp
    tục/xem lại; Back và tải lại giữ đúng trang.
- Cùng cấp Căn bản có popup dưới bảng mục tiêu, link chương từ evidence thật (§2.3), không gọi AI bịa chương.
- Dialog J-LAS có focus trap, Escape và nhãn nút rõ; toast `role=status`, thông báo accessible; tôn trọng reduced motion, không overflow mobile.
- Giữ Back/Top/Bottom; breadcrumb dễ đọc, bỏ `// SYSTEM`. Card nghĩa dài tự wrap; Kanji lớn/rõ. Sidebar/tour hướng dẫn không chặn việc học hoặc lộ đáp án quiz.
- Từ lesson review giữa hai batch phải có CTA tiếp tục bài kiểm tra còn lại đúng trạng thái server.

### 2.24 BrSE/BA, Travel, Daily Life — tình huống độc lập

- Survey-only, tạo từ survey đã nộp và nội dung/mapping hợp lệ. Retry cùng user/survey không tạo path trùng.
- Mỗi nhu cầu có một bộ tình huống đầy đủ; survey không tự bớt/thêm tình huống hoặc tạo biến thể theo level.
- Mọi tình huống được chọn tự do: nhóm theo chủ đề chỉ để dễ tìm, không phải chương khóa tuần tự; không khóa thứ tự, không prerequisite HARD_SEQUENCE/JIT warm-up bắt buộc.
- Cấu trúc (owner 2026-10-19): mỗi goal gồm các **chủ đề**; mỗi chủ đề có phần Từ vựng chủ đề và các **tình huống hội thoại** thuộc chủ đề theo bảng ghép đã duyệt (vd Du lịch: Sân bay & xuất nhập cảnh ← Làm thủ tục ở sân bay). Chủ đề không có hội thoại chỉ có Từ vựng.
- Mỗi tình huống hội thoại gồm, theo một luồng ngắn không gây ngợp: Từ & cụm từ trong hội thoại (thẻ lật) → Mẫu câu (luyện xếp câu/điền) → Hội thoại (nghe/đọc từng lượt, luyện đóng vai) → Tóm tắt cuối bài; cùng một **thử thách “vượt ải” tùy chọn**: hội thoại chạy từng lượt, tới lượt người học thì chọn 1 trong 3 câu đáp đúng.
- Hoàn thành tình huống = Từ & cụm từ + Mẫu câu + Hội thoại (luyện đủ từng lượt, tới cuối). Thử thách không bắt buộc, không chặn completion/unlock, làm sai không mất gì; vượt ải lần đầu thưởng **2 CP một lần** cho tình huống đó (retry/hai tab không cộng thêm, chống trùng bằng ledger CP).
- Thứ tự hiển thị (không khóa): chủ đề người học chọn trong survey lên trước với nhãn “Ưu tiên của bạn”, sau đó dễ → khó theo cấp của hội thoại (N4 → N3 → N2).
- Hỗ trợ trình độ thấp: không yêu cầu đủ cấp mới được học; furigana và bản dịch bật/tắt (mặc định bật, nhớ lựa chọn); tình huống cao hơn trình độ tự khai có nhãn cảnh báo kèm gợi ý học chủ đề từ vựng/sách trước nhưng không khóa; có nghe chậm cho từng lượt thoại.
- **Không quiz chấm điểm trong bài**; thử thách vượt ải là trò chơi tùy chọn, không phải quiz completion/unlock.
- BrSE giữ bộ tình huống đã thiết kế; survey learning mode/competency chỉ là nhu cầu, không auto-skip mastery.
- BrSE dưới N4: đề xuất link sách N4 và N3 tự nguyện; N4/N3: đề xuất sách N3; N2 trở lên chưa có đề xuất cụ thể, không tự suy diễn. Link trỏ giáo trình trong J-LAS (N4: bản Learning N4; N3: giáo trình N3), owner 2026-10-19.
- Link sách ngay trang BrSE; không bắt đọc/xong giáo trình mới học tình huống; không chặn người dưới N4.
- Chi tiết triển khai 2026-10-20 (agent; hai mục ★ do agent đề xuất, **owner đã xác nhận 2026-10-20 (OD-46)**):
  - Từ & cụm từ là thẻ lật ngay trong phần Hội thoại (mặt trước tiếng Nhật + cách đọc, mặt sau nghĩa); lật được lưu ở server;
    phần Hội thoại hoàn thành khi lật đủ thẻ + luyện đủ lượt + tới cuối. Activity đã COMPLETED giữ nguyên; activity đang học cần lật thẻ.
  - Furigana và bản dịch mặc định bật, nhớ lựa chọn trên thiết bị; ruby chỉ hiện khi văn bản gốc khớp đúng câu thoại.
  - ★ Vượt ải = chọn đúng mọi lượt của người học trong một lượt chơi; chọn sai thì hiện câu đúng + giải thích và cho chơi lại từ đầu,
    không mất gì; đáp án chỉ ở server, thứ tự phương án cố định theo bài/lượt.
  - ★ BrSE chưa rõ trình độ (UNKNOWN) được gợi ý như dưới N4 (N4 + N3), vẫn tự nguyện. Owner 2026-10-20 (OD-45): link N3 trỏ
    giáo trình JLPT "Tiếng Nhật N3" thông thường của J-LAS; người học đã đăng nhập mở và học được dù sách còn DRAFT, link kèm nhãn
    "Chưa duyệt", trang giáo trình hiện huy hiệu "Chưa duyệt"; không chuyển sách sang PUBLISHED. Sách DRAFT khác vẫn hiện
    "đang được biên soạn" thay vì link hỏng.
  - Nội dung Từ & cụm từ, furigana, câu sai của thử thách là **Nội dung bổ sung J-LAS · cần rà soát** (review_status NEEDS_REVIEW).
- Backend lưu tiến độ từng phần tương tác **từ vựng, ngữ pháp, luyện hội thoại** theo user và đơn vị học; rời trang/quay lại tiếp tục phần dở. Chỉ khi mọi phần của đơn vị hoàn thành mới đánh dấu `COMPLETED`. Người học có thể học các tình huống theo bất kỳ thứ tự nào; hoàn thành toàn bộ bộ tình huống của goal thì path `COMPLETED` và cấp chứng nhận nội bộ. Bài luyện cho phản hồi học tập, không phát đề quiz chấm điểm, không có ngưỡng điểm hoặc timer làm gate.
- Không mock JLPT cho ba goal. Thưởng CP duy nhất là 2 CP một lần/tình huống hội thoại khi vượt ải lần đầu; không suy 5 CP/chương JLPT cho giao tiếp.

### 2.25 Inline Dictionary / Favorites

- Lookup trong `[data-inline-dictionary]`, loại input/textarea/contenteditable/`[data-inline-private]`; chuỗi Nhật trim, tối đa 100 ký tự.
- Trong scored quiz khóa trợ giúp tra đáp án; khi học/review dùng lookup theo quyền bình thường.
- Server trả detail URL và vocabulary identity; không suy ID/private URL từ selection. History/favorites owner-scoped; save retry idempotent theo `(user_id,vocabulary_id)`, anonymous không ghi lịch sử tài khoản.

### 2.26 Daily Goal / Streak

- Nguồn Daily Goal ưu tiên package JLPT actionable của path; fallback user targets rồi defaults có `source/fallbackReason` rõ. Không gán “một package quiz” cho tình huống không quiz.
- Event receipts xác định và transaction giữ progress/reward idempotent; review/practice không sinh completion event.
- Learner-day từ event instant và IANA ZoneId user, không timezone JVM; kiểm day-boundary/DST/retry/concurrency.
- PATH_COMPLETED không tự chế vocab/quiz/minutes/mission progress. Freeze/outbox/credit dùng ledger và locking hiện có.

### 2.27 Evidence và nghiệm thu

- Mỗi case PASS/FAIL/NOT_RUN/BLOCKED có revision/checksum, môi trường, lệnh/log và phạm vi rõ. H2 không thay MySQL; fixture không thay production content; vài package không đại diện full level.
- File/audio tồn tại hoặc hash khớp không chứng minh script/translation/answer/playback đúng.
- Dữ liệu seed mô phỏng completion chỉ demo trạng thái, không được ghi là browser learner học hết.
- Không đặt số liệu audit tạm thời thành quy tắc. Ledger: `docs/superpowers/handoffs/2026-10-08-full-spec-execution-ledger.md`.

### 2.28 Nguồn, quyền sử dụng và xuất bản Learning

- Copy task-owned với stable IDs, source path/URL, checksum/provenance và mapping độc lập; không sửa/xóa trạng thái, seed hoặc hierarchy của đội nguồn.
- Owner chấp nhận học liệu DRAFT của sách nguồn để làm nội dung chính trong bản sao Learning (2026-10-15); điều này không tự biến mọi câu AI mới thành câu đã kiểm chứng.
- Owner 2026-10-19: với N3, N2, N1 được dùng giáo trình DRAFT và câu Đọc/Nghe HOLD cho lộ trình, chấm điểm bình thường. Giữ danh sách HOLD để sửa đáp án sau; không gọi chúng là đã duyệt hay câu JLPT chính thức.
  - Owner 2026-10-20 (OD-44/45): giữ nguyên DRAFT/HOLD, không đổi hàng loạt thành PUBLISHED. Code mở theo cấp
    (`DraftStudySourcePolicy.OPEN_DRAFT_LEVELS` = N3; N2/N1 mở cùng đợt phát hành cấp) cho sách có cờ nguồn lộ trình; danh sách
    HOLD giữ trong `data/learning-owned/learning-content.json` (`answer_check=HOLD_ANSWER_MAPPING`). Speed Master N3 không có câu HOLD;
    bài bị giữ sau rà soát ghi ở `jlpt_speed_n3_comprehension.held` (kèm lý do), bài dư hợp lệ ở `.reserve`.
- Tách reviewed/advisory, owner-approved, imported và open-for-study. Owner đã xác nhận quyền nguồn Mazii/Migii; giữ attribution, không giả teacher review.
- JLCT giữ nhãn JLCT/PRACTICE_ONLY; không đổi thành JLPT placement/mock chính thức.
- Owner 2026-10-21: dữ liệu của module/đội khác cần dùng thì sao một bản Learning-owned rồi sửa bản đó; không dùng chung rồi sửa
  bản của họ. Các sách VNJPClub được dùng nội bộ như Minna (OD-01); Đọc/Nghe Minna mở cho người học (OD-02); owner là người rà
  soát (OD-21). Ghi nguồn bằng trang nguồn + dòng nguồn tại chỗ (OD-40). Phần cần soạn thêm do AI tạo, phải khớp canonical trong DB
  (OD-06); Đọc N5/N4 dùng Speed Master N5/N4 kèm bài đọc AI bổ sung có nhãn (OD-08). Audio Sekai gắn nhãn "giọng đọc tổng hợp"
  khi chưa chứng minh là ghi âm (OD-10). Bài quy tắc Kana vào FOUNDATION với nhãn bổ sung (OD-33).
- File data chuẩn: `data/learning-owned/learning-content.json`; SQL task-owned được áp theo thứ tự `sql/migrations/022_learning_owned_consolidated.sql` rồi `sql/seeds/learning_owned.sql`. Snapshot nguồn có trong file không chứng minh đã map và mở học runtime.
- Sách J-LAS tổng hợp (owner 2026-10-24, D3-04/05/10): mỗi cấp một sách Learning-owned `LP-OWNED-JLAS-{L}-COMPILED` gộp mọi
  sách của cấp, chống trùng theo kỹ năng, dễ → khó, sách nguồn chỉ là provenance (không hiện nhãn). Dữ liệu: mục
  `jlas_compiled_book_{l}`; SQL: `build_jlas_compiled_books_sql.mjs` (N5/N4/N3; N2/N1 sau). Id theo dải riêng mỗi cấp
  (N5 5·10⁹, N4 6·10⁹, N3 7·10⁹) + slot cố định của mục, nên áp lại là cập nhật tại chỗ; mục rời sách được đỗ vào bài lưu
  trữ ARCHIVED, không xoá. Từ chỉ liên kết mục từ điển theo cặp chuẩn hoá §4 (thiếu thì thêm DRAFT TEXTBOOK); ngữ pháp,
  Kanji của bài là bản riêng của sách; hội thoại chép từ sách chương cùng số; câu Đọc/Nghe chép từ nhóm Minna/Speed
  (`context_json.compiled_from`), giữ thẻ `LP-OWNED-*` và `quiz_eligible=false` nên không vào Smart Test/gate quiz.
  Code chọn nguồn phải đổi sang sách tổng hợp trước khi khối này có trong DB đang chạy.

### 2.29 Detail activity và chia phần

- Activity mở trang detail riêng. Kana/Vocab/Kanji có Nội dung và Flashcard, cùng identity/phân đoạn nguồn; không mất/lặp member khi chuyển part.
- Quy tắc thẻ/remediation ở §2.8; kết quả và review ở §§2.21, 2.30; không có bản hợp đồng thứ hai tại đây.

### 2.30 Quiz result và giải thích AI

- Kết quả từng câu hiển thị prompt, đúng/sai, đáp án chuẩn, lựa chọn learner, giải thích/dịch có nguồn; không chỉ mã Câu n hoặc số option.
- Lấy từ attempt snapshot đã lưu, không tra câu hiện tại thay đáp án cũ. Thiếu snapshot legacy nói rõ.
- AI explanation chỉ gọi khi bấm, server kiểm ownership và cung cấp snapshot; có thể thêm ví dụ/dịch nhưng không sửa điểm/key/gate.
- AI lỗi có thông báo/fallback; không mất bài làm hoặc chặn nội dung curated.

### 2.31 Listening journey

- Thứ tự: hướng dẫn dạng nghe/mục tiêu → chuẩn bị từ/ngữ pháp/câu hỏi không lộ đáp án → audio thật → trả lời câu thuộc bài → kết quả kèm script/dịch/giải thích → completion theo điều kiện câu hỏi.
- Không đánh dấu “đã nghe” chỉ do play. Audio hỏng phải báo rõ và chặn completion yêu cầu nghe.
- Script/translation/audio cùng source snapshot. Không thêm một micro-check rời trùng lặp sau câu hỏi của bài.
- Bài nghe JLPT có câu hỏi; tình huống giao tiếp không quiz vẫn theo §2.24, không áp flow scored listening một cách máy móc.

### 2.32 Kho tài nguyên và chọn bài theo người học (owner duyệt 2026-10-26)

Chi tiết và lý do: `docs/learning/de-xuat-kho-tai-nguyen-2026-10-26.md` (v2, đã qua duyệt). Chỉ áp cho lộ trình **JLPT N5–N3**;
lộ trình giao tiếp (§2.24) không áp dụng. Đợt đầu: **Đọc + Nghe**; Từ vựng/Kanji/Ngữ pháp giữ cơ chế hiện có.

- **Ba lớp.** Kho = mọi bài Learning-owned (`LP-OWNED-*`, chép từ nguồn của đội khác, không sửa bản gốc) kèm nhãn; bài trong kho
  chưa vào chương nằm ở danh sách `pool` riêng, không gắn vào bài/chương nào. Phần bắt buộc = bài trong chương của lộ trình,
  chấm điểm/mở khóa/CP như hiện nay. Luyện thêm = gợi ý riêng từng người, không tính điểm chương, mở khóa, CP, hoàn thành.
- **Nhãn mỗi bài:** `level`, `chapter_hint`, `skill`, `format`, `grammar_refs`, `vocab_refs`, `difficulty` (1–5), `minutes`,
  `has_translation`, `has_audio`, `origin` (`SOURCE` / `JLAS_SUPPLEMENT`), `review` (`ACCEPTED` / `DRAFT` / `REVIEWED` / `HOLD`).
  Generator gắn; AI chỉ đề xuất nhãn offline, Claude soát; AI không tự nâng `REVIEWED`.
- **Phần bắt buộc:** giữ đúng số bài mỗi chương như lộ trình hiện tại (Đọc N5/N4 2–4, N3 2; Nghe N5 4, N4 4–5, N3 2); bài cũ giữ
  chỗ và thứ tự (`dealStable`). Chỉ `ACCEPTED`/`REVIEWED`; `DRAFT` không bao giờ vào; `HOLD` không ra người học. Chương thiếu thì
  lấp từ kho theo `grammar_refs/vocab_refs` và độ khó; hết bài phù hợp thì báo gap. Chỉ lộ trình tạo mới.
- **Luyện thêm:** tối đa **3 bài / kỹ năng / chương**, xếp: (0) hết bài → danh sách rỗng + thông báo; (1) trúng điểm yếu `OPEN`
  trong `user_knowledge_weakness` hoặc câu sai gần nhất của chương (không có thì bỏ qua); (2) độ khó theo điểm tham chiếu
  (trung bình 2 lần kiểm tra gần nhất → 1 lần → điểm kỹ năng Smart Test → bậc 3; ≥ 90% lên 1 bậc, < 60% xuống 1 bậc);
  (3) gần chương; (4) chưa làm trong 14 ngày; bằng nhau theo thứ tự nguồn — kết quả tất định. `DRAFT` được vào luyện thêm với nhãn
  "Nội dung bổ sung J-LAS · cần rà soát".
- **Phiên luyện thêm:** `learning_quiz_session.quiz_type = EXTRA_PRACTICE`, gắn `activity_id` của bài Đọc/Nghe bắt buộc của
  chương, đóng băng đề/đáp án/nguồn trong `questions_json`/`answer_key_json`/`source_snapshot_json`; lịch sử, kết quả, chữa bài và
  đếm lượt của bài chính lọc theo loại phiên. Cần migration mở rộng `chk_quiz_session_type` (diễn tập + owner duyệt trước khi ghi).
- **AI:** gắn nhãn/soạn bài bổ sung/đề xuất đáp án offline (đều `DRAFT`); giải thích khi dùng. AI không chấm điểm, không chọn phần
  bắt buộc, không mở khóa. AI lỗi thì gợi ý vẫn chạy theo nhãn đã lưu.

## 3. AI hỗ trợ

- Tái sử dụng `AiJsonGenerator`, `AiProviderConfig`, `DeepSeekClient` và provider hiện có. Key từ YAML/env, không hardcode/in/log/commit.
- Backend quyết định level/goal/stages/eligibility/quota/unlock/scoring. AI chỉ hỗ trợ narrative, hướng dẫn, giải thích hoặc đề xuất đã được backend kiểm.
- Câu AI mới là draft có ID/key/distractors/explanation/translation/level/goal/provenance/mapping; chưa được phép scored/publish thì chỉ practice riêng. Không runtime AI tự tạo câu scored để bù ngân hàng thiếu.
- Gemini advisory không đồng nghĩa source/teacher approval; cho phép dùng DRAFT sách nguồn (§2.28) không xóa trách nhiệm kiểm câu/mapping.
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

## 5. LEGACY_V1 — tham chiếu tương thích, không áp V2 mới

| Hợp đồng cũ | Giá trị/hành vi |
|---|---|
| Lesson/gate quiz | 5 câu, pass 60%, 5 submit hợp lệ/ngày; gate fail 2 lần yêu cầu ôn |
| Stage-final legacy | 10 câu/skill, đủ 5 skill; overall ≥80% và từng skill ≥70% |
| Multi-tier review cũ | overall >60% bỏ warm-up; skill <40% full review; không ghi đè quy tắc mới §§2.2–2.3 |
| Flashcard self-grade cũ | Không dùng trong Learning V2; V2 lật/lướt theo §2.8 |
| Mock attempt gate cũ | Giữ endpoint fail-closed khi cần tương thích; không gate path completion V2. Giấy chứng nhận path JLPT dùng điều kiện mock mới tại §2.10. |

Không tự xóa endpoint/schema legacy đang được module khác dùng. Chuyển đổi cần kiểm tương thích và giữ history; phần này không yêu cầu phát triển thêm cơ chế cũ.

## 6. Điểm chưa được đặc tả và phạm vi thực thi

- Chưa có ngưỡng phân loại mức Smart Test mới; giữ policy hiện có, không tự đặt.
- Chưa có cách xác định trình độ BrSE ngoài thông tin đã có; survey-only không tạo bằng chứng assessment. Chưa có đề xuất sách cho N2+.
- Completion và thưởng của tình huống giao tiếp đã chốt tại §2.24 (owner 2026-10-19): hoàn thành theo các phần học; thử thách vượt ải tùy chọn thưởng 2 CP một lần.
- Quiz học `INTERACTIVE_V2` không giới hạn thời gian (§2.6); không thêm timer hoặc tự nộp khi hết giờ.
- Assessment cao hơn tự khai: chỉ mở route ôn thấp hơn theo lựa chọn tường minh tại §2.3; bảo toàn kết quả assessment và dùng nội dung/stage thực có. Trường hợp ngoài danh sách path vẫn không được mở.
- Replan do mock chưa đạt để phát triển tiếp. Mock không chặn chứng nhận hoàn thành đã chốt.
- Nghiệm thu dựa trên evidence từng case/code/data/runtime, không đánh “đã xong” vì tài liệu đã cập nhật hoặc unit test xanh.
