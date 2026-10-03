import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const [inputArg, outputArg, previewArg] = process.argv.slice(2);
if (!inputArg || !outputArg || !previewArg) {
  throw new Error("Usage: node build_mazii_listening_staging.mjs <audit.json> <output.xlsx> <preview.png>");
}

const inputPath = path.resolve(inputArg);
const outputPath = path.resolve(outputArg);
const previewPath = path.resolve(previewArg);
const audit = JSON.parse(await fs.readFile(inputPath, "utf8"));
const workbook = Workbook.create();

const palette = {
  navy: "#18324A",
  blue: "#2F6690",
  paleBlue: "#EAF2F8",
  red: "#B42318",
  paleRed: "#FDECEC",
  amber: "#B54708",
  paleAmber: "#FFF4CC",
  green: "#157F3B",
  paleGreen: "#E7F4EC",
  gray: "#52616B",
  paleGray: "#F3F5F7",
};

function colName(index) {
  let n = index + 1;
  let out = "";
  while (n > 0) {
    out = String.fromCharCode(65 + ((n - 1) % 26)) + out;
    n = Math.floor((n - 1) / 26);
  }
  return out;
}

function value(value) {
  if (Array.isArray(value) || (value && typeof value === "object")) return JSON.stringify(value);
  if (typeof value === "boolean") return value ? "TRUE" : "FALSE";
  return value ?? "";
}

function writeRows(sheet, startRow, rows, width) {
  const chunkSize = 500;
  for (let offset = 0; offset < rows.length; offset += chunkSize) {
    const chunk = rows.slice(offset, offset + chunkSize);
    const first = startRow + offset;
    const last = first + chunk.length - 1;
    sheet.getRange(`A${first}:${colName(width - 1)}${last}`).values = chunk;
  }
}

function addDataSheet({ name, title, subtitle, headers, rows, widths, tableName, fill = palette.paleAmber }) {
  const sheet = workbook.worksheets.add(name);
  sheet.showGridLines = false;
  sheet.tabColor = palette.blue;
  const lastCol = colName(headers.length - 1);
  sheet.getRange("A1").values = [[title]];
  sheet.getRange("A1").format.font = { name: "Arial", size: 16, bold: true, color: palette.navy };
  sheet.getRange(`A2:${lastCol}2`).values = [[subtitle, ...Array(headers.length - 1).fill("")]];
  sheet.getRange(`A2:${lastCol}2`).format = {
    fill: palette.paleRed,
    font: { name: "Arial", size: 10, bold: true, color: palette.red },
    wrapText: true,
  };
  sheet.getRange(`A4:${lastCol}4`).values = [headers];
  sheet.getRange(`A4:${lastCol}4`).format = {
    fill: palette.navy,
    font: { name: "Arial", size: 10, bold: true, color: "#FFFFFF" },
    horizontalAlignment: "center",
    verticalAlignment: "center",
    wrapText: true,
  };
  const body = rows.length ? rows : [Array(headers.length).fill("")];
  writeRows(sheet, 5, body, headers.length);
  const lastRow = 4 + body.length;
  sheet.getRange(`A5:${lastCol}${lastRow}`).format = {
    fill,
    font: { name: "Arial", size: 9, color: "#202124" },
    verticalAlignment: "top",
    wrapText: true,
  };
  sheet.tables.add(`A4:${lastCol}${lastRow}`, true, tableName).style = "TableStyleMedium2";
  widths.forEach((width, index) => {
    sheet.getRange(`${colName(index)}:${colName(index)}`).format.columnWidth = width;
  });
  sheet.freezePanes.freezeRows(4);
  return sheet;
}

const summary = audit.summary;
const issueCount = new Map();
for (const candidate of audit.candidates) {
  for (const issue of candidate.issues) issueCount.set(issue, (issueCount.get(issue) ?? 0) + 1);
}
const coverage = new Map();
for (const candidate of audit.candidates) {
  const key = `${candidate.jlpt_level}:${candidate.exam_type}`;
  const current = coverage.get(key) ?? { total: 0, audio: 0, prompt: 0, answer: 0 };
  current.total += 1;
  current.audio += candidate.audio_path ? 1 : 0;
  current.prompt += candidate.prompt_ja ? 1 : 0;
  current.answer += candidate.correct_answer ? 1 : 0;
  coverage.set(key, current);
}

const summaryRows = [
  ["Release status", "NOT IMPORT READY", "Khóa fail-closed: không seed production"],
  ["Catalog questions", summary.catalog_rows, "JLPT + JLCT N5–N1"],
  ["Audio resolved", summary.audio_files_resolved, `${summary.catalog_rows - summary.audio_files_resolved} câu chưa có audio cục bộ`],
  ["Unique audio content", summary.unique_audio_resolved, "Tính theo SHA-256"],
  ["Recovered via raw manifest", audit.candidates.filter((row) => row.audio_resolution_source === "RAW_LISTENING_MANIFEST").length, "Map source URL → hashed local filename"],
  ["Scripts resolved", summary.script_files_resolved, `${summary.catalog_rows - summary.script_files_resolved} câu thiếu script`],
  ["Question files resolved", summary.question_files_resolved, `${summary.catalog_rows - summary.question_files_resolved} câu thiếu file câu hỏi/đáp án`],
  ["Prompts parsed", summary.questions_with_prompt, `${summary.catalog_rows - summary.questions_with_prompt} câu thiếu prompt`],
  ["Answers parsed", summary.questions_with_answer, `${summary.catalog_rows - summary.questions_with_answer} câu thiếu/sai đáp án`],
  ["License verified", 0, "Cần owner chứng minh quyền sử dụng Mazii/Migii"],
  ["Semantic review complete", 0, "Cần nghe và đối chiếu script/câu hỏi/đáp án"],
  ["Ready for import", summary.ready_for_import, "Phải giữ bằng 0 đến khi mọi gate đạt"],
];
for (const [key, item] of [...coverage.entries()].sort()) {
  summaryRows.push([`Coverage ${key}`, item.total, `audio=${item.audio}; prompt=${item.prompt}; answer=${item.answer}`]);
}
addDataSheet({
  name: "SUMMARY",
  title: "J-LAS Mazii Listening — staging audit",
  subtitle: "NOT IMPORT READY — file này chỉ dùng cho QA/data completion; không phải seed và không được chuyển PUBLISHED.",
  headers: ["metric", "value", "interpretation"],
  rows: summaryRows,
  widths: [34, 22, 76],
  tableName: "MaziiSummary",
  fill: palette.paleBlue,
});

const readmeRows = [
  ["Mục đích", "Tập hợp ứng viên Listening N5–N1 từ ba thư mục bàn giao, giữ provenance và lỗi để đội data hoàn thiện."],
  ["Không phải production", "Mọi câu đều DRAFT / NOT_REVIEWED / ready_for_import=FALSE. Không được dùng workbook này làm seed trực tiếp."],
  ["JLPT và JLCT", "Giữ tách exam_type. Không tự đưa câu JLCT vào blueprint JLPT nếu product owner chưa duyệt."],
  ["Quyền sử dụng", "URL nguồn không phải giấy phép. Cần tài liệu xác nhận quyền lưu trữ, phát audio, dùng câu hỏi/script/hình và yêu cầu attribution."],
  ["Review bắt buộc", "Nghe audio; đối chiếu đúng script, prompt, options, đáp án; bổ sung explanation_vi; kiểm tra hình; gắn reviewer và ngày duyệt."],
  ["Audio raw", "Một số audio được phục hồi qua listening.csv (URL nguồn → tên file hash). Cờ STANDARDIZED_AUDIO_MISSING_RAW_SOURCE_USED yêu cầu đóng gói lại trước import."],
  ["Audio-only options", "Các lựa chọn chỉ là 1/2/3/4 được giữ nguyên, không tự bịa nội dung chữ. Reviewer phải nghe audio để xác nhận."],
  ["Dữ liệu còn thiếu", "Xem ISSUE: missing audio/script/question/image/prompt/options/answer cùng các gate license/semantic/explanation."],
  ["Thứ tự xử lý", "Quyền sử dụng → asset còn thiếu → semantic review → explanation_vi → blueprint/section mapping → validator → fixture import → UAT."],
  ["Nguồn máy", audit.catalog_path ?? "",],
  ["Báo cáo máy", inputPath],
];
addDataSheet({
  name: "README",
  title: "Hướng dẫn bàn giao dữ liệu Listening",
  subtitle: "Đọc sheet này trước. Mọi thay đổi trạng thái cần bằng chứng và reviewer cụ thể.",
  headers: ["topic", "instruction"],
  rows: readmeRows,
  widths: [30, 110],
  tableName: "MaziiReadme",
  fill: palette.paleBlue,
});

const questionHeaders = [
  "question_code", "source_code", "skill", "jlpt_level", "exam_type", "source_exam_id",
  "source_question_id", "mondai", "source_section", "question_type", "prompt_ja", "options_json",
  "correct_option_order", "correct_answer", "audio_only_options", "audio_code", "audio_path",
  "audio_resolution_source", "source_audio_url", "script_path", "question_source_path", "image_path",
  "source_url", "external_key", "confidence", "review_status", "lifecycle_status",
  "answer_key_verified", "provenance_complete", "semantic_reviewed", "license_verified",
  "ready_for_import", "issues",
];
const questionRows = audit.candidates.map((candidate) => questionHeaders.map((header) => {
  if (header === "options_json") return JSON.stringify(candidate.options);
  if (header === "issues") return candidate.issues.join(" | ");
  return value(candidate[header]);
}));
addDataSheet({
  name: "QUESTION_CANDIDATE",
  title: "Listening question candidates",
  subtitle: "DRAFT/NOT_REVIEWED — không đổi cờ ready_for_import nếu chưa hoàn tất toàn bộ gate.",
  headers: questionHeaders,
  rows: questionRows,
  widths: questionHeaders.map((header) => ({
    prompt_ja: 42, options_json: 58, correct_answer: 34, audio_path: 58,
    source_audio_url: 58, script_path: 58, question_source_path: 58,
    image_path: 50, source_url: 46, issues: 65, source_section: 30,
  }[header] ?? 20)),
  tableName: "MaziiQuestions",
});

const optionHeaders = ["question_code", "option_order", "option_text", "is_correct", "audio_only_option", "review_status", "ready_for_import"];
const optionRows = audit.candidates.flatMap((candidate) => candidate.options.map((option, index) => [
  candidate.question_code, index + 1, option,
  candidate.correct_option_order === index + 1 ? "TRUE" : "FALSE",
  candidate.audio_only_options ? "TRUE" : "FALSE", "NOT_REVIEWED", "FALSE",
]));
addDataSheet({
  name: "OPTION_CANDIDATE",
  title: "Listening option candidates",
  subtitle: "Các option chỉ là số được giữ nguyên theo nguồn; không suy diễn nội dung nghe.",
  headers: optionHeaders,
  rows: optionRows,
  widths: [38, 16, 70, 14, 20, 20, 18],
  tableName: "MaziiOptions",
});

const mediaHeaders = ["audio_code", "source_code", "file_name", "local_path", "source_url", "checksum", "bytes", "license_note", "playback_verified", "license_verified", "review_status", "ready_for_import"];
const mediaRows = audit.media_candidates.map((candidate) => mediaHeaders.map((header) => value(candidate[header])));
addDataSheet({
  name: "MEDIA_CANDIDATE",
  title: "Listening media candidates",
  subtitle: "Checksum đã có; playback/license vẫn chưa được xác minh bởi người chịu trách nhiệm.",
  headers: mediaHeaders,
  rows: mediaRows,
  widths: [36, 26, 32, 72, 62, 66, 16, 66, 20, 18, 20, 18],
  tableName: "MaziiMedia",
});

const resolution = {
  MISSING_LOCAL_FILE_AUDIO: "Bổ sung đúng audio; xác nhận checksum và playback.",
  MISSING_LOCAL_FILE_SCRIPT: "Bổ sung transcript/script đúng audio.",
  MISSING_LOCAL_FILE_QUESTION: "Bổ sung file prompt/options/answer đúng QID.",
  MISSING_LOCAL_FILE_IMAGE: "Bổ sung hình đúng câu hoặc xác nhận câu không cần hình.",
  MISSING_PROMPT: "Nhập prompt thật từ nguồn; không dùng placeholder.",
  MISSING_OPTIONS: "Nhập đủ lựa chọn thật hoặc xác nhận loại câu audio-only.",
  MISSING_OR_INVALID_ANSWER: "Đối chiếu answer key và nhập đáp án hợp lệ.",
  SCRIPT_NOT_USABLE: "Tạo transcript có thể dùng và review thủ công.",
  LICENSE_NOT_VERIFIED: "Cung cấp bằng chứng quyền sử dụng và attribution.",
  SEMANTIC_REVIEW_REQUIRED: "Reviewer nghe và đối chiếu audio/script/câu hỏi/đáp án.",
  EXPLANATION_VI_MISSING: "Viết giải thích tiếng Việt sau khi chốt đáp án.",
  STANDARDIZED_AUDIO_MISSING_RAW_SOURCE_USED: "Đóng gói/copy asset raw vào tên chuẩn khi pipeline import được duyệt.",
};
const issueHeaders = ["question_code", "jlpt_level", "exam_type", "source_exam_id", "source_question_id", "issue_type", "severity", "resolution_action"];
const issueRows = audit.candidates.flatMap((candidate) => candidate.issues.map((issue) => [
  candidate.question_code, candidate.jlpt_level, candidate.exam_type, candidate.source_exam_id,
  candidate.source_question_id, issue,
  issue === "STANDARDIZED_AUDIO_MISSING_RAW_SOURCE_USED" ? "INFO" : "BLOCKER",
  resolution[issue] ?? "Review và xử lý theo provenance.",
]));
addDataSheet({
  name: "ISSUE",
  title: "Data completion backlog",
  subtitle: "BLOCKER phải về 0 trước production import; INFO vẫn cần chuẩn hóa đóng gói.",
  headers: issueHeaders,
  rows: issueRows,
  widths: [38, 14, 14, 18, 20, 42, 14, 78],
  tableName: "MaziiIssues",
  fill: palette.paleRed,
});

const errors = await workbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
  options: { useRegex: true, maxResults: 100 },
});
console.log(errors.ndjson);
const preview = await workbook.render({ sheetName: "SUMMARY", autoCrop: "all", scale: 1, format: "png" });
await fs.mkdir(path.dirname(outputPath), { recursive: true });
await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
const file = await SpreadsheetFile.exportXlsx(workbook);
await file.save(outputPath);
console.log(JSON.stringify({ outputPath, previewPath, questions: questionRows.length, options: optionRows.length, media: mediaRows.length, issues: issueRows.length }));
