import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const outputDir = "D:/FALL26_FPT/SEP490/outputs/01a0ed4f-adc7-75e0-bf3a-f82644d49c20";
const previewDir = "D:/FALL26_FPT/SEP490/tools/artifact-build/previews-n5-n4";
const outputPath = path.join(outputDir, "jlas-learning-path-n5-n4-data-template.xlsx");
const fontName = "Arial";
const dark = "#24364B";
const blue = "#2F6690";
const lightBlue = "#EAF2F8";
const inputYellow = "#FFF4CC";
const lightGray = "#F3F5F7";
const green = "#E7F4EC";
const red = "#FCE8E6";

const wb = Workbook.create();

function colName(index) {
  let n = index + 1;
  let out = "";
  while (n > 0) {
    const r = (n - 1) % 26;
    out = String.fromCharCode(65 + r) + out;
    n = Math.floor((n - 1) / 26);
  }
  return out;
}

function addInputSheet({ name, title, subtitle, headers, widths, rows, tableName, validations = [], wrapColumns = [] }) {
  const sheet = wb.worksheets.add(name);
  sheet.showGridLines = false;
  sheet.tabColor = blue;
  sheet.getRange("A1").values = [[title]];
  sheet.getRange("A1").format.font = { name: fontName, size: 16, bold: true, color: dark };
  const lastCol = colName(headers.length - 1);
  sheet.getRange(`A2:${lastCol}2`).values = [[subtitle, ...Array(headers.length - 1).fill(null)]];
  sheet.getRange(`A2:${lastCol}2`).format.font = { name: fontName, size: 10, italic: true, color: "#52616B" };
  sheet.getRange(`A2:${lastCol}2`).format.wrapText = false;
  sheet.getRange(`A2:${lastCol}2`).format.rowHeight = 22;
  sheet.getRange(`A4:${lastCol}4`).values = [headers];
  sheet.getRange(`A4:${lastCol}4`).format = {
    fill: dark,
    font: { name: fontName, size: 10, bold: true, color: "#FFFFFF" },
    horizontalAlignment: "center",
    verticalAlignment: "center",
    wrapText: true,
    borders: { preset: "inside", style: "thin", color: "#FFFFFF" },
  };
  sheet.getRange(`A4:${lastCol}4`).format.rowHeight = 38;
  const bodyRows = rows.length || 1;
  const matrix = rows.length ? rows : [Array(headers.length).fill(null)];
  sheet.getRange(`A5:${lastCol}${4 + bodyRows}`).values = matrix;
  sheet.getRange(`A5:${lastCol}${4 + bodyRows}`).format.font = { name: fontName, size: 10, color: "#202124" };
  sheet.getRange(`A5:${lastCol}${4 + bodyRows}`).format.verticalAlignment = "top";
  sheet.getRange(`A5:${lastCol}${4 + bodyRows}`).format.fill = inputYellow;
  sheet.getRange(`A5:${lastCol}${4 + bodyRows}`).format.rowHeight = 34;
  sheet.tables.add(`A4:${lastCol}${4 + bodyRows}`, true, tableName).style = "TableStyleMedium2";
  widths.forEach((width, i) => {
    sheet.getRange(`${colName(i)}:${colName(i)}`).format.columnWidth = width;
  });
  wrapColumns.forEach((i) => {
    sheet.getRange(`${colName(i)}5:${colName(i)}504`).format.wrapText = true;
  });
  validations.forEach(({ col, values, type = "list", min, max }) => {
    const range = `${colName(col)}5:${colName(col)}504`;
    const rule = type === "whole"
      ? { type: "whole", operator: "between", formula1: min, formula2: max }
      : { type: "list", values };
    sheet.dataValidations.add({ range, rule });
  });
  sheet.freezePanes.freezeRows(4);
  return sheet;
}

const readme = wb.worksheets.add("README");
readme.showGridLines = false;
readme.tabColor = dark;
readme.getRange("A1").values = [["J-LAS Learning Path N5–N4 data template"]];
readme.getRange("A1").format.font = { name: fontName, size: 18, bold: true, color: dark };
readme.getRange("A2").values = [["Form nhập liệu được thiết kế theo 6 tài liệu N5 và 6 tài liệu N4 đã cung cấp."]];
readme.getRange("A2").format.font = { name: fontName, size: 10, italic: true, color: "#52616B" };
readme.getRange("A4:B18").values = [
  ["Nguyên tắc", "Cách dùng"],
  ["Tách nguồn", "LP_TEXTBOOK, LP_UNIT và LP_LESSON chỉ chứa nguồn dành riêng cho Learning Path. Không đưa sách của module Textbook thành viên vào các sheet này."],
  ["Canonical dùng chung", "LESSON_VOCAB, LESSON_KANJI và LESSON_GRAMMAR liên kết tới vocabulary_entry, kanji, grammar_point hiện có bằng canonical_id. Chỉ nhập dữ liệu mới khi tra cứu không thấy bản canonical."],
  ["Cờ nguồn", "Mọi textbook được import từ LP_TEXTBOOK phải có is_learning_path_source=1. Chỉ chuyển status sang PUBLISHED sau khi VALIDATION_REPORT không còn lỗi."],
  ["Chuẩn hóa từ vựng", "Không tự nhập normalized_expression/normalized_reading. Importer áp NFKC, chuyển Katakana sang Hiragana và giữ nguyên ー, ・."],
  ["Dòng ví dụ", "Các dòng màu vàng là vùng nhập. Dòng SAMPLE minh họa khóa liên kết; thay nội dung mẫu bằng dữ liệu thật trước khi import."],
  ["Khóa liên kết", "Dùng *_code ổn định, không dùng tiêu đề làm khóa. order_index bắt đầu từ 1 trong workbook; importer chuyển đổi nếu schema yêu cầu."],
  ["N5 lõi", "25 bài của SÁCH TỔNG HỢP N5 là trục lesson. Sách nghe và Mondaishuu bổ sung activity/question theo cùng source_lesson_ref."],
  ["N4 lõi", "22 bài của SÁCH TỔNG HỢP N4 là trục lesson. Kanji 29 buổi và Choukai 25 bài map qua lesson_code hoặc topic_code, không ép số buổi phải bằng 22."],
  ["Hội thoại", "100 bài Kaiwa và 48 bài NHK có thể map vào lesson JLPT hoặc topic goal bằng destination_scope. Mỗi lời thoại là một dòng DIALOGUE."],
  ["Bài tập", "COMPREHENSION/EXERCISE giữ question_type của sách và import_question_type của DB riêng. TRUE_FALSE → MULTIPLE_CHOICE; ORDERING → MATCHING hoặc LESSON_ONLY; FREE_RESPONSE → LESSON_ONLY."],
  ["Foundation", "FOUNDATION_CHAR dành cho bảng chữ cái/warm-up. Bộ tài liệu hiện tại chưa nêu nguồn Foundation nên để status NEEDS_SOURCE cho tới khi có tài liệu."],
  ["Dữ liệu dài", "Passage, script, giải thích và ví dụ được phép xuống dòng trong một ô. Không gộp nhiều câu hỏi hoặc nhiều lời thoại vào cùng một dòng."],
  ["Kiểm tra", "Mở VALIDATION_REPORT để kiểm tra thiếu trường bắt buộc. Importer vẫn phải kiểm tra duplicate, orphan, level mismatch và canonical lookup."],
  ["Thứ tự nhập", "BOOK_CATALOG → LP_TEXTBOOK → LP_UNIT → LP_LESSON → canonical mappings → DIALOGUE/COMPREHENSION/EXERCISE → validation → import."],
];
readme.getRange("A4:B4").format = { fill: dark, font: { name: fontName, bold: true, color: "#FFFFFF" } };
readme.getRange("A5:A18").format = { fill: lightBlue, font: { name: fontName, bold: true, color: dark }, verticalAlignment: "top" };
readme.getRange("B5:B18").format = { font: { name: fontName, size: 10, color: "#202124" }, wrapText: true, verticalAlignment: "top" };
readme.getRange("A4:B18").format.borders = { preset: "outside", style: "thin", color: "#CBD5E1" };
readme.getRange("A:A").format.columnWidth = 25;
readme.getRange("B:B").format.columnWidth = 105;
readme.getRange("A5:B18").format.rowHeight = 48;

const bookRows = [
  ["N5-CORE", "SÁCH TỔNG HỢP N5 (170425).pdf", "N5", 442, 25, "CORE_CURRICULUM", "LP_TEXTBOOK; LP_UNIT; LP_LESSON; LESSON_VOCAB; LESSON_KANJI; LESSON_GRAMMAR; DIALOGUE; EXERCISE", "YES", "Bài 1–25; Kanji từ bài 6"],
  ["N5-KAIWA-100", "100 BÀI KAIWA THEO 100 CHỦ ĐỀ.pdf", "N5", 58, 100, "DIALOGUE_SUPPLEMENT", "DIALOGUE", "YES", "Skit, Romaji, dịch Việt, key phrase, register"],
  ["N5-NHK-48", "48_Hoi Thoai_cung nhau hoc tieng Nhat NHK.pdf", "N5", 163, 48, "DIALOGUE_SUPPLEMENT", "DIALOGUE; COMPREHENSION", "YES", "Can-do A1–A2, hội thoại, văn hóa, luyện phản xạ"],
  ["N5-LISTEN-25", "Sách Nghe chokai tasuku N5 (BẢN CŨ).pdf", "N5", 66, 25, "LISTENING_SUPPLEMENT", "COMPREHENSION; EXERCISE", "YES", "Bài nghe 1–25, script và đáp án"],
  ["N5-WORKBOOK", "Mondaishuu N5.pdf", "N5", 69, 29, "PRACTICE_SOURCE", "EXERCISE", "NO", "25 bài + 4 bài tổng ôn"],
  ["N5-ANSWER", "Đáp án Mondaishuu N5.pdf", "N5", 14, 29, "ANSWER_KEY", "EXERCISE.answer_*", "NO", "Nguồn đối soát đáp án"],
  ["N4-CORE", "SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.pdf", "N4", 399, 22, "CORE_CURRICULUM", "LP_TEXTBOOK; LP_UNIT; LP_LESSON; LESSON_VOCAB; LESSON_GRAMMAR; DIALOGUE; COMPREHENSION; EXERCISE", "YES", "Bài 26–50 được tổ chức thành 22 bài cấp tốc"],
  ["N4-KANJI-29", "Chữ Hán N4 tài liệu học viên (Cấp tốc).pdf", "N4", 88, 29, "KANJI_SUPPLEMENT", "LESSON_KANJI; EXERCISE", "YES", "29 buổi, hơn 180 Kanji, từ ghép, stroke order"],
  ["N4-LISTEN-25", "N4_Choukai Tasuku 25 new.pdf", "N4", 153, 25, "LISTENING_SUPPLEMENT", "COMPREHENSION; EXERCISE", "YES", "Task 1–4, script có furigana, đáp án"],
  ["N4-WORKBOOK-GRAMMAR", "Bài tập mondaishuu N4 - CẤP TỐC.pdf", "N4", 68, 26, "PRACTICE_SOURCE", "EXERCISE", "NO", "22 bài + 4 bài tổng ôn"],
  ["N4-WORKBOOK-JLPT", "Bài tập SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.pdf", "N4", 69, 22, "PRACTICE_SOURCE", "EXERCISE", "NO", "4 phần mỗi bài: từ vựng, ngữ pháp, sao, đọc hiểu"],
  ["N4-ANSWER", "Đáp án SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC (211124).pdf", "N4", 9, 22, "ANSWER_KEY", "EXERCISE.answer_*", "NO", "Nguồn đối soát đáp án cuốn trắc nghiệm N4"],
];
addInputSheet({
  name: "BOOK_CATALOG", title: "Danh mục 12 nguồn N5–N4",
  subtitle: "Phân loại nguồn theo vai trò. Cột import_as_lp_source chỉ đánh dấu nguồn có thể tạo activity/curriculum Learning Path, không phải sách module thành viên.",
  headers: ["source_book_code*", "source_file_name*", "jlpt_level*", "page_count", "lesson_or_session_count", "source_role*", "recommended_destination", "import_as_lp_source*", "structure_note"],
  widths: [20, 58, 12, 12, 18, 24, 55, 20, 58], rows: bookRows, tableName: "BookCatalogTable",
  validations: [{ col: 2, values: ["N5", "N4"] }, { col: 5, values: ["CORE_CURRICULUM", "DIALOGUE_SUPPLEMENT", "LISTENING_SUPPLEMENT", "KANJI_SUPPLEMENT", "PRACTICE_SOURCE", "ANSWER_KEY"] }, { col: 7, values: ["YES", "NO"] }],
  wrapColumns: [1, 6, 8],
});

addInputSheet({
  name: "LP_TEXTBOOK", title: "Learning Path textbooks",
  subtitle: "Một dòng cho mỗi textbook nguồn Learning Path. Không nhập sách của module thành viên. is_learning_path_source luôn là 1.",
  headers: ["textbook_code*", "source_book_code*", "title*", "description", "jlpt_level*", "status*", "version*", "is_learning_path_source*", "source_page_from", "source_page_to", "notes"],
  widths: [20, 20, 42, 55, 12, 14, 10, 24, 16, 16, 45],
  rows: [["SAMPLE-N5-CORE", "N5-CORE", "Giáo trình Learning Path N5", "Trục 25 bài N5", "N5", "DRAFT", 1, 1, 1, 442, "Thay dòng SAMPLE bằng dữ liệu đã rà soát"]],
  tableName: "LpTextbookTable",
  validations: [{ col: 4, values: ["N5", "N4"] }, { col: 5, values: ["DRAFT", "PUBLISHED", "ARCHIVED"] }, { col: 6, type: "whole", min: 1, max: 999 }, { col: 7, values: [0, 1] }], wrapColumns: [2, 3, 10],
});

addInputSheet({
  name: "LP_UNIT", title: "Learning Path units",
  subtitle: "Nhóm bài theo textbook. Có thể dùng unit theo giai đoạn 1–8, 9–17, 18–25 hoặc theo chương sách thật.",
  headers: ["unit_code*", "textbook_code*", "title*", "description", "order_index*", "source_book_code*", "source_section_ref", "notes"],
  widths: [20, 20, 38, 55, 14, 20, 24, 45],
  rows: [["SAMPLE-N5-U01", "SAMPLE-N5-CORE", "N5 Bài 1–8", "Nhóm bài mở đầu", 1, "N5-CORE", "Bài 1–8", "Có thể thay bằng cấu trúc chương chính xác"]],
  tableName: "LpUnitTable", validations: [{ col: 4, type: "whole", min: 1, max: 999 }], wrapColumns: [2, 3, 7],
});

addInputSheet({
  name: "LP_LESSON", title: "Learning Path lessons",
  subtitle: "Một lesson là đơn vị học có thể tạo package. skill ghi kỹ năng chính; activity chi tiết được khai báo ở các sheet membership.",
  headers: ["lesson_code*", "unit_code*", "source_book_code*", "source_lesson_ref*", "title*", "description", "order_index*", "estimated_minutes*", "lesson_type*", "primary_skill*", "knowledge_tag_code*", "situation_tag", "is_review*", "status*", "can_do_outcome", "notes"],
  widths: [22, 20, 20, 22, 38, 52, 14, 18, 16, 18, 24, 18, 12, 14, 50, 45],
  rows: [["SAMPLE-N5-L01", "SAMPLE-N5-U01", "N5-CORE", "Bài 1", "N5 Bài 1", "Lesson mẫu", 1, 45, "CURRICULUM", "VOCABULARY", "JLPT_N5_LESSON_01", "", 0, "DRAFT", "Hoàn thành nội dung trọng tâm của bài", "Một source lesson có thể có nhiều activity"]],
  tableName: "LpLessonTable",
  validations: [{ col: 6, type: "whole", min: 1, max: 999 }, { col: 7, type: "whole", min: 5, max: 240 }, { col: 8, values: ["CURRICULUM", "AMON", "REVIEW"] }, { col: 9, values: ["VOCABULARY", "GRAMMAR", "READING", "LISTENING", "SPEAKING", "ONYOMI", "KUNYOMI"] }, { col: 11, values: ["", "TRAVEL", "BA", "FOUNDATION"] }, { col: 12, values: [0, 1] }, { col: 13, values: ["DRAFT", "PUBLISHED", "ARCHIVED"] }], wrapColumns: [4, 5, 14, 15],
});

addInputSheet({
  name: "LESSON_VOCAB", title: "Lesson vocabulary membership",
  subtitle: "Một dòng cho mỗi từ trong lesson. canonical_vocab_id ưu tiên dùng bản vocabulary_entry đã có; để trống ID khi cần importer tra theo expression + reading.",
  headers: ["membership_code*", "lesson_code*", "source_book_code*", "source_lesson_ref*", "canonical_vocab_id", "expression*", "reading*", "romaji", "word_type", "han_viet", "meaning_vi*", "example_ja", "example_vi", "order_index*", "is_core*", "canonical_lookup_status*", "notes"],
  widths: [24, 22, 20, 22, 20, 22, 22, 20, 18, 18, 38, 45, 45, 14, 12, 24, 40],
  rows: [["SAMPLE-N5-L01-V001", "SAMPLE-N5-L01", "N5-CORE", "Bài 1", null, "日本語", "にほんご", "nihongo", "NOUN", "NHẬT BẢN NGỮ", "tiếng Nhật", "日本語を勉強します。", "Tôi học tiếng Nhật.", 1, 1, "NEEDS_LOOKUP", "Dữ liệu ví dụ; thay bằng nội dung sách"]],
  tableName: "LessonVocabTable",
  validations: [{ col: 13, type: "whole", min: 1, max: 999 }, { col: 14, values: [0, 1] }, { col: 15, values: ["MATCHED", "NEEDS_LOOKUP", "CREATE_CANONICAL", "CONFLICT"] }], wrapColumns: [10, 11, 12, 16],
});

addInputSheet({
  name: "LESSON_KANJI", title: "Lesson Kanji membership",
  subtitle: "N5 bắt đầu từ bài 6; N4 Kanji có 29 buổi và hơn 180 chữ. Map source_session_ref vào lesson_code/topic_code mà không ép 29 buổi thành 22 lesson.",
  headers: ["membership_code*", "lesson_code", "topic_code", "source_book_code*", "source_session_ref*", "canonical_kanji_id", "character*", "unicode_code", "han_viet", "meaning_vi", "stroke_count", "stroke_svg_uri", "on_readings", "kun_readings", "memory_hint", "order_index*", "canonical_lookup_status*", "notes"],
  widths: [24, 22, 22, 20, 22, 20, 12, 16, 18, 35, 14, 40, 30, 30, 45, 14, 24, 40],
  rows: [["SAMPLE-N5-KANJI-001", "SAMPLE-N5-L01", "", "N5-CORE", "Bài 6", null, "日", "65E5", "NHẬT", "ngày, mặt trời", 4, "", "ニチ, ジツ", "ひ, か", "Mẹo nhớ theo tài liệu", 1, "NEEDS_LOOKUP", "Dữ liệu minh họa; thay bằng nội dung sách"]],
  tableName: "LessonKanjiTable",
  validations: [{ col: 10, type: "whole", min: 1, max: 99 }, { col: 15, type: "whole", min: 1, max: 999 }, { col: 16, values: ["MATCHED", "NEEDS_LOOKUP", "CREATE_CANONICAL", "CONFLICT"] }], wrapColumns: [9, 11, 12, 13, 14, 17],
});

addInputSheet({
  name: "KANJI_COMPOUND", title: "Kanji to lesson-vocabulary evidence",
  subtitle: "Mỗi Kanji của lesson phải có ít nhất một từ vựng cùng lesson chứa chữ đó. character_position bắt đầu từ 1 theo Unicode code point.",
  headers: ["link_code*", "kanji_membership_code*", "vocabulary_membership_code*", "character*", "vocabulary_expression*", "vocabulary_reading*", "character_position*", "notes"],
  widths: [26, 28, 32, 14, 30, 28, 22, 52],
  rows: [],
  tableName: "KanjiCompoundTable",
  validations: [{ col: 6, type: "whole", min: 1, max: 100 }],
  wrapColumns: [7],
});

addInputSheet({
  name: "LESSON_GRAMMAR", title: "Lesson grammar points",
  subtitle: "Một dòng cho mỗi mẫu ngữ pháp. Công thức kết hợp, ý nghĩa, lưu ý và ví dụ được tách cột để importer/audit xử lý rõ ràng.",
  headers: ["grammar_code*", "lesson_code*", "source_book_code*", "source_lesson_ref*", "canonical_grammar_id", "knowledge_tag_code", "pattern*", "connection_formula", "meaning_vi*", "context_note", "example_ja", "example_vi", "practice_prompt", "order_index*", "canonical_lookup_status*", "notes"],
  widths: [24, 22, 20, 22, 22, 24, 42, 42, 45, 50, 50, 50, 50, 14, 24, 40],
  rows: [["SAMPLE-N5-L01-G001", "SAMPLE-N5-L01", "N5-CORE", "Bài 1", null, "JLPT_N5_GRAMMAR_01", "N1 は N2 です", "Danh từ + は + Danh từ + です", "N1 là N2", "Mẫu câu lịch sự", "わたしは学生です。", "Tôi là học sinh.", "Tự giới thiệu bản thân", 1, "NEEDS_LOOKUP", "Dữ liệu minh họa"]],
  tableName: "LessonGrammarTable",
  validations: [{ col: 13, type: "whole", min: 1, max: 999 }, { col: 14, values: ["MATCHED", "NEEDS_LOOKUP", "CREATE_CANONICAL", "CONFLICT"] }], wrapColumns: [6, 7, 8, 9, 10, 11, 12, 15],
});

addInputSheet({
  name: "DIALOGUE", title: "Dialogue lines",
  subtitle: "Một lời thoại một dòng. Hỗ trợ lesson JLPT và topic goal; giữ register lịch sự/thân mật, key phrase và Can-do từ nguồn Kaiwa/NHK.",
  headers: ["dialogue_code*", "lesson_code", "topic_code", "destination_scope*", "source_book_code*", "source_lesson_ref*", "title*", "turn_index*", "speaker*", "text_ja*", "reading_or_romaji", "translation_vi*", "register*", "is_key_phrase*", "can_do_outcome", "audio_url", "source_page", "notes"],
  widths: [24, 22, 22, 22, 20, 22, 40, 14, 20, 55, 45, 55, 18, 18, 50, 42, 14, 40],
  rows: [["SAMPLE-KAIWA-001", "SAMPLE-N5-L01", "", "JLPT_LESSON", "N5-KAIWA-100", "Bài 1", "Tình huống mẫu", 1, "A", "これはいくらですか。", "kore wa ikura desu ka", "Cái này bao nhiêu tiền?", "POLITE", 1, "Hỏi giá", "", 1, "Dữ liệu minh họa"]],
  tableName: "DialogueTable",
  validations: [{ col: 3, values: ["JLPT_LESSON", "TRAVEL_TOPIC", "BA_TOPIC", "DAILY_LIFE_TOPIC"] }, { col: 7, type: "whole", min: 1, max: 999 }, { col: 12, values: ["POLITE", "CASUAL", "FORMAL", "HONORIFIC", "HUMBLE"] }, { col: 13, values: [0, 1] }], wrapColumns: [6, 9, 10, 11, 14, 17],
});

addInputSheet({
  name: "COMPREHENSION", title: "Reading and listening content",
  subtitle: "Một câu hỏi một dòng; passage/script lặp theo content_code để nhập thuận tiện. source_kind phân biệt đọc, nghe, bản đồ, biểu đồ và tình huống.",
  headers: ["content_code*", "lesson_code", "topic_code", "source_book_code*", "source_lesson_ref*", "skill*", "source_kind*", "title*", "passage_or_script*", "translation_vi", "audio_url", "question_code*", "question_text*", "question_type*", "import_question_type*", "option_a", "option_b", "option_c", "option_d", "correct_answer*", "explanation_vi", "order_index*", "source_page", "notes"],
  widths: [24, 22, 22, 20, 22, 14, 24, 40, 75, 65, 42, 24, 58, 20, 24, 35, 35, 35, 35, 24, 55, 14, 14, 40],
  rows: [["SAMPLE-N5-LISTEN-001", "SAMPLE-N5-L01", "", "N5-LISTEN-25", "Bài 1", "LISTENING", "AUDIO_ONLY", "Nghe phân biệt âm", "Script mẫu", "Bản dịch mẫu", "", "SAMPLE-Q001", "Chọn đáp án đúng", "MCQ", "MULTIPLE_CHOICE", "A", "B", "C", "D", "A", "Giải thích đáp án", 1, 1, "Dữ liệu minh họa"]],
  tableName: "ComprehensionTable",
  validations: [{ col: 5, values: ["READING", "LISTENING"] }, { col: 6, values: ["TEXT", "AUDIO_ONLY", "IMAGE", "MAP", "CHART", "TABLE", "DIALOGUE"] }, { col: 13, values: ["MCQ", "TRUE_FALSE", "FILL_BLANK", "ORDERING", "MATCHING", "FREE_RESPONSE"] }, { col: 14, values: ["MULTIPLE_CHOICE", "FILL_BLANK", "MATCHING", "LESSON_ONLY"] }, { col: 21, type: "whole", min: 1, max: 999 }], wrapColumns: [7, 8, 9, 12, 15, 16, 17, 18, 20, 23],
});

addInputSheet({
  name: "EXERCISE", title: "Practice and assessment questions",
  subtitle: "Nguồn Mondaishuu/JLPT practice. Một câu hỏi một dòng; answer_source_* trỏ tới sách đáp án để audit, không tạo lesson cho sách giải.",
  headers: ["exercise_code*", "lesson_code", "review_group", "source_book_code*", "source_lesson_ref*", "section_type*", "skill*", "question_type*", "import_question_type*", "prompt*", "option_a", "option_b", "option_c", "option_d", "correct_answer*", "answer_explanation", "answer_source_book_code", "answer_source_page", "difficulty", "order_index*", "source_page", "notes"],
  widths: [24, 22, 22, 22, 22, 25, 18, 20, 24, 65, 35, 35, 35, 35, 28, 55, 24, 18, 14, 14, 14, 40],
  rows: [["SAMPLE-N5-EX-001", "SAMPLE-N5-L01", "", "N5-WORKBOOK", "Bài 1", "PARTICLE_FILL", "GRAMMAR", "FILL_BLANK", "FILL_BLANK", "Điền trợ từ phù hợp", "", "", "", "", "は", "Đáp án đã đối soát", "N5-ANSWER", 1, "EASY", 1, 1, "Dữ liệu minh họa"]],
  tableName: "ExerciseTable",
  validations: [{ col: 5, values: ["VOCAB_IMAGE", "PARTICLE_FILL", "CONJUGATION", "SENTENCE_COMPLETION", "MATCHING", "SENTENCE_ORDER", "STAR_ORDER", "READING", "LISTENING", "FREE_RESPONSE", "REVIEW_TEST"] }, { col: 6, values: ["VOCABULARY", "KANJI", "GRAMMAR", "READING", "LISTENING", "SPEAKING"] }, { col: 7, values: ["MCQ", "TRUE_FALSE", "FILL_BLANK", "ORDERING", "MATCHING", "FREE_RESPONSE"] }, { col: 8, values: ["MULTIPLE_CHOICE", "FILL_BLANK", "MATCHING", "LESSON_ONLY"] }, { col: 18, values: ["EASY", "MEDIUM", "HARD"] }, { col: 19, type: "whole", min: 1, max: 999 }], wrapColumns: [9, 10, 11, 12, 13, 15, 21],
});

addInputSheet({
  name: "FOUNDATION_CHAR", title: "Foundation characters",
  subtitle: "Form cho warm-up chữ cái. Bộ nguồn hiện tại chưa cung cấp tài liệu Hiragana/Katakana riêng nên dòng mẫu giữ NEEDS_SOURCE.",
  headers: ["character_code*", "script_type*", "character*", "romaji*", "stroke_order_uri", "audio_url", "example_expression", "example_reading", "example_meaning_vi", "order_index*", "source_book_code", "source_page", "source_status*", "notes"],
  widths: [24, 18, 14, 16, 45, 42, 25, 25, 35, 14, 22, 14, 20, 45],
  rows: [["SAMPLE-HIRA-A", "HIRAGANA", "あ", "a", "", "", "あさ", "あさ", "buổi sáng", 1, "", null, "NEEDS_SOURCE", "Chưa lấy từ bộ 12 cuốn N5/N4"]],
  tableName: "FoundationCharTable",
  validations: [{ col: 1, values: ["HIRAGANA", "KATAKANA"] }, { col: 9, type: "whole", min: 1, max: 999 }, { col: 12, values: ["NEEDS_SOURCE", "READY", "REVIEW_REQUIRED"] }], wrapColumns: [13],
});

const report = wb.worksheets.add("VALIDATION_REPORT");
report.showGridLines = false;
report.tabColor = "#C84B31";
report.getRange("A1").values = [["Validation report"]];
report.getRange("A1").format.font = { name: fontName, size: 16, bold: true, color: dark };
report.getRange("A2").values = [["Các công thức kiểm tra thiếu trường bắt buộc trên vùng 500 dòng. Duplicate/orphan/level mismatch tiếp tục được importer kiểm tra theo khóa code."]];
report.getRange("A2:D2").format.font = { name: fontName, size: 10, italic: true, color: "#52616B" };
report.getRange("A4:D4").values = [["Sheet", "Rule", "Issue count", "Status"]];
report.getRange("A4:D4").format = { fill: dark, font: { name: fontName, bold: true, color: "#FFFFFF" }, horizontalAlignment: "center" };
const checks = [
  ["LP_TEXTBOOK", "Có textbook_code nhưng thiếu source_book_code", "=COUNTIFS('LP_TEXTBOOK'!$A$5:$A$504,\"?*\",'LP_TEXTBOOK'!$B$5:$B$504,\"\")"],
  ["LP_UNIT", "Có unit_code nhưng thiếu textbook_code", "=COUNTIFS('LP_UNIT'!$A$5:$A$504,\"?*\",'LP_UNIT'!$B$5:$B$504,\"\")"],
  ["LP_LESSON", "Có lesson_code nhưng thiếu unit_code", "=COUNTIFS('LP_LESSON'!$A$5:$A$504,\"?*\",'LP_LESSON'!$B$5:$B$504,\"\")"],
  ["LESSON_VOCAB", "Có membership_code nhưng thiếu expression", "=COUNTIFS('LESSON_VOCAB'!$A$5:$A$504,\"?*\",'LESSON_VOCAB'!$F$5:$F$504,\"\")"],
  ["LESSON_KANJI", "Có membership_code nhưng thiếu character", "=COUNTIFS('LESSON_KANJI'!$A$5:$A$504,\"?*\",'LESSON_KANJI'!$G$5:$G$504,\"\")"],
  ["LESSON_GRAMMAR", "Có grammar_code nhưng thiếu pattern", "=COUNTIFS('LESSON_GRAMMAR'!$A$5:$A$504,\"?*\",'LESSON_GRAMMAR'!$G$5:$G$504,\"\")"],
  ["KANJI_COMPOUND", "Link tham chiếu membership Kanji/từ vựng không tồn tại", "=SUMPRODUCT(--('KANJI_COMPOUND'!$A$5:$A$504<>\"\"),--(COUNTIF('LESSON_KANJI'!$A$5:$A$504,'KANJI_COMPOUND'!$B$5:$B$504)=0))+SUMPRODUCT(--('KANJI_COMPOUND'!$A$5:$A$504<>\"\"),--(COUNTIF('LESSON_VOCAB'!$A$5:$A$504,'KANJI_COMPOUND'!$C$5:$C$504)=0))"],
  ["DIALOGUE", "Có dialogue_code nhưng thiếu text_ja", "=COUNTIFS('DIALOGUE'!$A$5:$A$504,\"?*\",'DIALOGUE'!$J$5:$J$504,\"\")"],
  ["COMPREHENSION", "Có question_code nhưng thiếu correct_answer", "=COUNTIFS('COMPREHENSION'!$L$5:$L$504,\"?*\",'COMPREHENSION'!$T$5:$T$504,\"\")"],
  ["COMPREHENSION", "Question type không map an toàn sang DB", "=COUNTIFS('COMPREHENSION'!$N$5:$N$504,\"TRUE_FALSE\",'COMPREHENSION'!$O$5:$O$504,\"<>MULTIPLE_CHOICE\")+COUNTIFS('COMPREHENSION'!$N$5:$N$504,\"FREE_RESPONSE\",'COMPREHENSION'!$O$5:$O$504,\"<>LESSON_ONLY\")+COUNTIFS('COMPREHENSION'!$N$5:$N$504,\"ORDERING\",'COMPREHENSION'!$O$5:$O$504,\"<>MATCHING\",'COMPREHENSION'!$O$5:$O$504,\"<>LESSON_ONLY\")"],
  ["EXERCISE", "Có exercise_code nhưng thiếu correct_answer", "=COUNTIFS('EXERCISE'!$A$5:$A$504,\"?*\",'EXERCISE'!$O$5:$O$504,\"\")"],
  ["EXERCISE", "Question type không map an toàn sang DB", "=COUNTIFS('EXERCISE'!$H$5:$H$504,\"TRUE_FALSE\",'EXERCISE'!$I$5:$I$504,\"<>MULTIPLE_CHOICE\")+COUNTIFS('EXERCISE'!$H$5:$H$504,\"FREE_RESPONSE\",'EXERCISE'!$I$5:$I$504,\"<>LESSON_ONLY\")+COUNTIFS('EXERCISE'!$H$5:$H$504,\"ORDERING\",'EXERCISE'!$I$5:$I$504,\"<>MATCHING\",'EXERCISE'!$I$5:$I$504,\"<>LESSON_ONLY\")"],
  ["FOUNDATION_CHAR", "Có character_code nhưng source_status chưa READY", "=COUNTIF('FOUNDATION_CHAR'!$M$5:$M$504,\"NEEDS_SOURCE\")+COUNTIF('FOUNDATION_CHAR'!$M$5:$M$504,\"REVIEW_REQUIRED\")"],
];
report.getRange(`A5:B${4 + checks.length}`).values = checks.map(([sheet, rule]) => [sheet, rule]);
report.getRange(`C5:C${4 + checks.length}`).formulas = checks.map(([, , formula]) => [formula]);
report.getRange("D5").formulas = [["=IF(C5=0,\"OK\",\"CHECK\")"]];
report.getRange(`D5:D${4 + checks.length}`).fillDown();
report.getRange(`A5:D${4 + checks.length}`).format.font = { name: fontName, size: 10, color: "#202124" };
report.getRange(`A5:D${4 + checks.length}`).format.verticalAlignment = "top";
report.getRange(`B5:B${4 + checks.length}`).format.wrapText = true;
report.getRange(`D5:D${4 + checks.length}`).conditionalFormats.add("containsText", { text: "OK", format: { fill: green, font: { color: "#1B5E20", bold: true } } });
report.getRange(`D5:D${4 + checks.length}`).conditionalFormats.add("containsText", { text: "CHECK", format: { fill: red, font: { color: "#B3261E", bold: true } } });
report.getRange("A:A").format.columnWidth = 24;
report.getRange("B:B").format.columnWidth = 66;
report.getRange("C:C").format.columnWidth = 16;
report.getRange("D:D").format.columnWidth = 16;
report.freezePanes.freezeRows(4);

const lookups = wb.worksheets.add("LOOKUPS");
lookups.showGridLines = false;
lookups.tabColor = "#7A7A7A";
lookups.getRange("A1").values = [["Controlled values"]];
lookups.getRange("A1").format.font = { name: fontName, size: 16, bold: true, color: dark };
lookups.getRange("A3:D3").values = [["Category", "Code", "Meaning", "Used in"]];
lookups.getRange("A3:D3").format = { fill: dark, font: { name: fontName, bold: true, color: "#FFFFFF" } };
const lookupRows = [
  ["Source role", "CORE_CURRICULUM", "Giáo trình làm trục lesson", "BOOK_CATALOG"],
  ["Source role", "DIALOGUE_SUPPLEMENT", "Nguồn hội thoại/Can-do", "BOOK_CATALOG; DIALOGUE"],
  ["Source role", "LISTENING_SUPPLEMENT", "Nguồn nghe, script, task", "BOOK_CATALOG; COMPREHENSION"],
  ["Source role", "KANJI_SUPPLEMENT", "Nguồn Kanji chuyên sâu", "BOOK_CATALOG; LESSON_KANJI"],
  ["Source role", "PRACTICE_SOURCE", "Nguồn câu hỏi luyện tập", "BOOK_CATALOG; EXERCISE"],
  ["Source role", "ANSWER_KEY", "Nguồn đối soát đáp án", "BOOK_CATALOG; EXERCISE"],
  ["Canonical", "MATCHED", "Đã nối ID canonical hiện có", "LESSON_VOCAB/KANJI/GRAMMAR"],
  ["Canonical", "NEEDS_LOOKUP", "Chờ tra canonical", "LESSON_VOCAB/KANJI/GRAMMAR"],
  ["Canonical", "CREATE_CANONICAL", "Đã xác minh cần tạo canonical mới", "LESSON_VOCAB/KANJI/GRAMMAR"],
  ["Canonical", "CONFLICT", "Có nhiều ứng viên hoặc dữ liệu lệch", "LESSON_VOCAB/KANJI/GRAMMAR"],
  ["Destination", "JLPT_LESSON", "Gắn vào lesson N5/N4", "DIALOGUE"],
  ["Destination", "TRAVEL_TOPIC", "Topic du lịch", "DIALOGUE"],
  ["Destination", "BA_TOPIC", "Topic BRSE/Business", "DIALOGUE"],
  ["Destination", "DAILY_LIFE_TOPIC", "Topic đời sống", "DIALOGUE"],
  ["Import question", "MULTIPLE_CHOICE", "DB auto-graded; TRUE_FALSE dùng hai option Đúng/Sai", "COMPREHENSION; EXERCISE"],
  ["Import question", "FILL_BLANK", "DB auto-graded bằng answer_text", "COMPREHENSION; EXERCISE"],
  ["Import question", "MATCHING", "DB auto-graded khi có đủ cặp trái/phải", "COMPREHENSION; EXERCISE"],
  ["Import question", "LESSON_ONLY", "Chỉ là bài luyện trong lesson, không tạo assessment_question", "COMPREHENSION; EXERCISE"],
];
lookups.getRange(`A4:D${3 + lookupRows.length}`).values = lookupRows;
lookups.getRange(`A4:D${3 + lookupRows.length}`).format.font = { name: fontName, size: 10, color: "#202124" };
lookups.getRange(`A4:D${3 + lookupRows.length}`).format.wrapText = true;
lookups.getRange("A:A").format.columnWidth = 20;
lookups.getRange("B:B").format.columnWidth = 28;
lookups.getRange("C:C").format.columnWidth = 50;
lookups.getRange("D:D").format.columnWidth = 40;
lookups.freezePanes.freezeRows(3);

for (const sheetName of ["README", "BOOK_CATALOG", "LP_TEXTBOOK", "LP_UNIT", "LP_LESSON", "LESSON_VOCAB", "LESSON_KANJI", "KANJI_COMPOUND", "LESSON_GRAMMAR", "DIALOGUE", "COMPREHENSION", "EXERCISE", "FOUNDATION_CHAR", "VALIDATION_REPORT", "LOOKUPS"]) {
  const sheet = wb.worksheets.getItem(sheetName);
  const used = sheet.getUsedRange();
  if (used) used.format.font = { ...used.format.font, name: fontName };
}

await fs.mkdir(outputDir, { recursive: true });
await fs.mkdir(previewDir, { recursive: true });

const keyInspection = await wb.inspect({
  kind: "table",
  range: "BOOK_CATALOG!A1:I16",
  include: "values,formulas",
  tableMaxRows: 16,
  tableMaxCols: 10,
});
console.log(keyInspection.ndjson);

const validationInspection = await wb.inspect({
  kind: "table",
  range: `VALIDATION_REPORT!A1:D${4 + checks.length}`,
  include: "values,formulas",
  tableMaxRows: 4 + checks.length,
  tableMaxCols: 6,
});
console.log(validationInspection.ndjson);

for (const range of ["KANJI_COMPOUND!A1:H5", "COMPREHENSION!A1:X5", "EXERCISE!A1:V5"]) {
  const inspection = await wb.inspect({
    kind: "table",
    range,
    include: "values,formulas",
    tableMaxRows: 5,
    tableMaxCols: 24,
  });
  console.log(inspection.ndjson);
}

const errors = await wb.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
  options: { useRegex: true, maxResults: 300 },
  summary: "final formula error scan",
});
console.log(errors.ndjson);

for (const sheetName of ["README", "BOOK_CATALOG", "LP_TEXTBOOK", "LP_UNIT", "LP_LESSON", "LESSON_VOCAB", "LESSON_KANJI", "KANJI_COMPOUND", "LESSON_GRAMMAR", "DIALOGUE", "COMPREHENSION", "EXERCISE", "FOUNDATION_CHAR", "VALIDATION_REPORT", "LOOKUPS"]) {
  const preview = await wb.render({ sheetName, autoCrop: "all", scale: 1, format: "png" });
  await fs.writeFile(path.join(previewDir, `${sheetName}.png`), new Uint8Array(await preview.arrayBuffer()));
}

const output = await SpreadsheetFile.exportXlsx(wb);
await output.save(outputPath);
console.log(JSON.stringify({ outputPath, sheets: 15 }));
