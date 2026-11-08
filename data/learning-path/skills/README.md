# Danh mục dạng câu & Bài học chiến thuật Đọc · Nghe JLPT (N5–N3)

Thư mục này chứa dữ liệu danh mục dạng câu hỏi (読解 · 聴解) và bài học chiến thuật làm bài cho công cụ **Đọc · Nghe** thuộc lộ trình học JLPT (N5, N4, N3).

## 1. Nguồn tham chiếu cấu trúc đề thi công khai

Cấu trúc phần thi, tên dạng câu tiếng Nhật và số lượng câu hỏi thường gặp được tham chiếu từ tài liệu cấu trúc đề thi công khai chính thức của kỳ thi Năng lực Nhật ngữ (JLPT) do Japan Foundation và JEES công bố:

- Trang công khai cấu trúc phần thi & dạng câu hỏi JLPT (tiếng Nhật): `https://www.jlpt.jp/guideline/testsections.html` (Mục 「試験科目と問題の構成」 — Cấu trúc môn thi và câu hỏi).
- Trang công khai cấu trúc phần thi & dạng câu hỏi JLPT (tiếng Anh): `https://www.jlpt.jp/e/guideline/testsections.html` (Mục "Composition of test items").
- Toàn bộ đoạn văn mẫu (`passage_ja`), kịch bản hội thoại (`script_ja`), câu hỏi, lựa chọn và lời giải trong thư mục này đều được **biên soạn mới độc lập** theo đúng giới hạn từ vựng – ngữ pháp của từng cấp độ, không sao chép từ đề thi thật, sách luyện thi hay website bên ngoài.

## 2. Quy ước mã dạng câu (`code`)

Mỗi dạng câu có một mã định danh ổn định, viết HOA theo mẫu:

```text
<CẤP>-<KỸ_NĂNG>-<KHÓA_DẠNG_BÀI>
```

Trong đó:
- `<CẤP>`: `N5`, `N4`, `N3`.
- `<KỸ_NĂNG>`:
  - `R` = `READING` (読解 — Đọc hiểu)
  - `L` = `LISTENING` (聴解 — Nghe hiểu)
- `<KHÓA_DẠNG_BÀI>`:
  - Phần Đọc (`R`):
    - `SHORT`: 内容理解（短文） — Đọc hiểu đoạn văn ngắn
    - `MEDIUM`: 内容理解（中文） — Đọc hiểu đoạn văn trung bình
    - `LONG`: 内容理解（長文） — Đọc hiểu đoạn văn dài (từ cấp N3)
    - `INFO`: 情報検索 — Tìm kiếm thông tin (bảng biểu, tờ rơi, thông báo)
  - Phần Nghe (`L`):
    - `TASK`: 課題理解 — Nghe hiểu nhiệm vụ (hành động tiếp theo)
    - `POINT`: ポイント理解 — Nghe hiểu điểm chính (chi tiết trọng tâm)
    - `SUMMARY`: 概要理解 — Nghe hiểu khái quát (ý chính / chủ đề toàn bài, từ cấp N3)
    - `UTTERANCE`: 発話表現 — Biểu đạt phát ngôn (chọn câu nói phù hợp với tranh tình huống có mũi tên)
    - `RESPONSE`: 即時応答 — Phản xạ tức thời (đáp lại câu phát ngôn ngắn)

## 3. Cấu trúc và cách đọc 2 file JSON

Cả hai file JSON đều được mã hóa UTF-8, định dạng `JSON.stringify(obj, null, 1) + "\n"`.

### 3.1. `question-types.json`

Là một mảng JSON (`Array<QuestionType>`) gồm 23 phần tử (N5: 7, N4: 7, N3: 9), sắp xếp theo cấp (`N5` → `N4` → `N3`), kỹ năng (`READING` → `LISTENING`) và thứ tự xuất hiện trong đề (`order`).

Mỗi phần tử có cấu trúc:
- `code` (`string`): Mã dạng câu duy nhất (ví dụ: `"N5-R-SHORT"`, `"N3-L-SUMMARY"`).
- `level` (`"N5" | "N4" | "N3"`): Cấp độ JLPT.
- `skill` (`"READING" | "LISTENING"`): Kỹ năng Đọc hoặc Nghe.
- `name_ja` (`string`): Tên dạng câu tiếng Nhật theo cấu trúc đề JLPT.
- `name_vi` (`string`): Tên dạng câu tiếng Việt hiển thị trên giao diện.
- `order` (`number`): Thứ tự dạng câu trong phần thi Đọc hoặc Nghe của cấp đó (bắt đầu từ 1).
- `questions_in_exam` (`number`): Số câu hỏi thường gặp trong đề thi chuẩn.
- `passage` (`{ chars_min: number, chars_max: number }`, chỉ có ở `skill: "READING"`): Khoảng độ dài ký tự tiếng Nhật của đoạn văn.
- `audio` (`{ speakers: number, seconds_typical: number }`, chỉ có ở `skill: "LISTENING"`): Số người nói và thời lượng âm thanh điển hình (tính bằng giây).
- `signals` (`string[]`): Các dấu hiệu nhận diện để bộ phân loại tự động gắn dạng câu cho bài đọc/nghe.
- `needs_image` (`boolean`): Cho biết dạng bài đó có cần tranh minh họa/sơ đồ hay không.

### 3.2. `strategy-lessons.json`

Là một mảng JSON (`Array<StrategyLesson>`) gồm 23 bài học chiến thuật tương ứng 1–1 với 23 mã `code` trong `question-types.json` qua trường khóa ngoại `type_code`.

Mỗi phần tử có cấu trúc:
- `type_code` (`string`): Khớp với `code` trong `question-types.json`.
- `title_vi` (`string`): Tiêu đề bài học chiến thuật.
- `goal_vi` (`string`): Mục tiêu cần đạt của buổi học chiến thuật (1 câu).
- `steps_vi` (`string[]`): 3–6 bước làm bài cụ thể.
- `traps_vi` (`string[]`): 2–4 bẫy thường gặp trong đề.
- `tips_vi` (`string[]`): 1–3 mẹo phân bổ thời gian và xử lý nhanh.
- `worked_example` (`object`): Ví dụ giải mẫu tự biên soạn đúng trình độ:
  - `passage_ja` (`string`, với bài Đọc) hoặc `script_ja` (`string`, với bài Nghe).
  - `image_description_vi` (`string`, có mặt khi dạng Nghe có `needs_image: true`): Mô tả chi tiết hình vẽ minh họa bằng chữ.
  - `question_ja` (`string`): Câu hỏi tiếng Nhật.
  - `options` (`string[4]`): 4 phương án trả lời.
  - `answer_index` (`0..3`): Chỉ số đáp án đúng.
  - `walkthrough_vi` (`string[]`): Các bước áp dụng chiến thuật để tìm ra đáp án đúng.
  - `translation_vi` (`string`): Bản dịch tiếng Việt của đoạn văn / lời thoại.
- `check` (`object`): Câu hỏi trắc nghiệm kiểm hiểu chiến thuật (cần trả lời đúng để hoàn thành bước Lý thuyết của buổi kỹ năng):
  - `question_vi` (`string`), `options_vi` (`string[4]`), `answer_index` (`0..3`), `explanation_vi` (`string`).

### 3.3. Ví dụ đọc và nối 2 file trong Node.js / Java

```js
import fs from 'node:fs';

const questionTypes = JSON.parse(fs.readFileSync('data/learning-path/skills/question-types.json', 'utf8'));
const strategyLessons = JSON.parse(fs.readFileSync('data/learning-path/skills/strategy-lessons.json', 'utf8'));

const lessonByType = new Map(strategyLessons.map(lesson => [lesson.type_code, lesson]));
for (const qt of questionTypes) {
  const lesson = lessonByType.get(qt.code);
  // Kết hợp thông tin dạng câu (qt) và bài học chiến thuật (lesson)
}
```
