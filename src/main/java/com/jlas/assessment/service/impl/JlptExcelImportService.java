package com.jlas.assessment.service.impl;

import com.jlas.assessment.dto.JlptAdminExamSummaryResponse;
import com.jlas.assessment.service.JlptAdminService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

/** Imports one reviewable JLPT draft from four sheets in a single XLSX workbook. */
@Service
@RequiredArgsConstructor
public class JlptExcelImportService {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_ROWS = 1000;
    private static final Pattern CODE = Pattern.compile("N[1-5]_(?:\\d{4}_(?:07|12)|MOCK_\\d+)");
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_]{2,50}");
    private static final List<String> PARTS = List.of("vocabulary", "grammar_reading", "listening");
    private static final String[] EXAM_HEADERS = {"code", "title", "level", "year", "month", "passing_score"};
    private static final String[] MONDAI_HEADERS = {"part_code", "part_title", "duration_minutes", "mondai_number", "format_code", "instruction"};
    private static final String[] PASSAGE_HEADERS = {"part_code", "passage_key", "passage_ja", "passage_vi"};
    private static final String[] QUESTION_HEADERS = {"part_code", "mondai_number", "question_number", "question_text", "passage_key", "option_count", "option_1", "option_2", "option_3", "option_4", "correct_option", "correct_order", "score", "explanation", "image_url", "audio_url", "script_ja", "script_vi"};
    private static final String[] QUESTION_TEMPLATE_HEADERS = {"part_code", "mondai_number", "question_number", "question_text", "passage_key", "option_count", "option_1", "option_2", "option_3", "option_4", "correct_option", "correct_order", "score", "explanation", "image_url", "audio_url", "script_ja", "script_vi", "target_vocabulary_id"};

    private final JdbcTemplate jdbc;
    private final JlptAdminService adminService;

    public record Issue(String sheet, int row, String message) {}
    public record Preview(String code, String title, String level, int mondaiCount,
                          int passageCount, int questionCount, List<Issue> errors, List<Issue> warnings) {
        public boolean valid() { return errors.isEmpty(); }
    }

    private record Exam(String code, String title, String level, Integer year, Integer month, int passingScore) {}
    private record Mondai(String part, String partTitle, int duration, int number, String format, String instruction, int row) {}
    private record Passage(String part, String key, String ja, String vi, int row) {}
    private record Question(String part, int mondai, int number, String text, String passageKey,
                            List<String> options, int answer, String correctOrder, BigDecimal score,
                            String explanation, String imageUrl, String audioUrl, String scriptJa,
                            String scriptVi, Long targetVocabularyId, int row) {}
    private record Parsed(Exam exam, List<Mondai> mondai, List<Passage> passages,
                          List<Question> questions, List<Issue> errors, List<Issue> warnings) {
        Preview preview() {
            return new Preview(exam == null ? null : exam.code(), exam == null ? null : exam.title(),
                    exam == null ? null : exam.level(), mondai.size(), passages.size(), questions.size(),
                    List.copyOf(errors), List.copyOf(warnings));
        }
    }

    public Preview preview(MultipartFile file) { return parse(file).preview(); }

    @Transactional
    public JlptAdminExamSummaryResponse importDraft(MultipartFile file) {
        Parsed parsed = parse(file);
        if (!parsed.errors().isEmpty()) {
            Issue first = parsed.errors().getFirst();
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    first.sheet() + " dòng " + first.row() + ": " + first.message());
        }
        Exam exam = parsed.exam();
        if (exists(exam.code())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã đề đã tồn tại: " + exam.code());
        }
        long examId = insert("""
                INSERT INTO jlpt_exam(exam_code,title,jlpt_level,exam_year,exam_month,passing_score,review_status,review_note,status)
                VALUES (?,?,?,?,?,?,'UNREVIEWED','Đề nhập từ Excel; cần rà soát trước khi phát hành.','DRAFT')
                """, exam.code(), exam.title(), exam.level(), exam.year(), exam.month(), exam.passingScore());
        Map<String, Long> partIds = new HashMap<>();
        Map<String, Long> mondaiIds = new HashMap<>();
        for (int partOrder = 0; partOrder < PARTS.size(); partOrder++) {
            String part = PARTS.get(partOrder);
            List<Mondai> items = parsed.mondai().stream().filter(item -> item.part().equals(part))
                    .sorted(Comparator.comparingInt(Mondai::number)).toList();
            if (items.isEmpty()) continue;
            Mondai first = items.getFirst();
            long partId = insert("""
                    INSERT INTO jlpt_part(exam_id,part_code,title,duration_minutes,order_index)
                    VALUES (?,?,?,?,?)
                    """, examId, part, first.partTitle(), first.duration(), partOrder + 1);
            partIds.put(part, partId);
            for (Mondai item : items) {
                long id = insert("""
                        INSERT INTO jlpt_mondai(part_id,mondai_number,format_code,instruction_html,audio_url,order_index)
                        VALUES (?,?,?,?,NULL,?)
                        """, partId, item.number(), item.format(), html(item.instruction()), item.number());
                mondaiIds.put(key(part, item.number()), id);
            }
        }
        Map<String, Long> passageIds = new HashMap<>();
        Map<String, Integer> passageOrder = new HashMap<>();
        for (Passage passage : parsed.passages()) {
            int order = passageOrder.merge(passage.part(), 1, Integer::sum);
            long id = insert("""
                    INSERT INTO jlpt_passage(part_id,source_passage_id,passage_ja,passage_html,passage_vi,order_index)
                    VALUES (?,?,?,?,?,?)
                    """, partIds.get(passage.part()), order, passage.ja(), html(passage.ja()),
                    blank(passage.vi()), order);
            passageIds.put(key(passage.part(), passage.key()), id);
        }
        Map<String, Integer> questionOrder = new HashMap<>();
        Map<String, Long> typeIds = new HashMap<>();
        for (Question question : parsed.questions()) {
            String group = key(question.part(), question.mondai());
            long mondaiId = mondaiIds.get(group);
            Long passageId = question.passageKey().isBlank() ? null
                    : passageIds.get(key(question.part(), question.passageKey()));
            String format = parsed.mondai().stream()
                    .filter(item -> item.part().equals(question.part()) && item.number() == question.mondai())
                    .findFirst().orElseThrow().format();
            Long typeId = typeIds.computeIfAbsent(key(question.part(), format),
                    value -> ensureQuestionType(question.part(), format));
            int order = questionOrder.merge(group, 1, Integer::sum);
            long questionId = insert("""
                    INSERT INTO jlpt_question(mondai_id,passage_id,question_type_id,question_number,question_text,
                                              question_html,correct_option,correct_order,score,image_url,explanation,order_index,target_vocabulary_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, mondaiId, passageId, typeId, question.number(), question.text(), html(question.text()),
                    question.answer(), blank(question.correctOrder()), question.score(), blank(question.imageUrl()),
                    blank(question.explanation()), order, question.targetVocabularyId());
            for (int i = 0; i < question.options().size(); i++) {
                jdbc.update("""
                        INSERT INTO jlpt_option(question_id,option_number,option_text,option_html,order_index)
                        VALUES (?,?,?,?,?)
                        """, questionId, i + 1, question.options().get(i),
                        html(question.options().get(i)), i + 1);
            }
            if (!question.audioUrl().isBlank()) {
                long clipId = insert("""
                        INSERT INTO jlpt_audio_clip(mondai_id,question_id,clip_type,audio_url,order_index)
                        VALUES (?,?,'QUESTION',?,?)
                        """, mondaiId, questionId, question.audioUrl(), order);
                jdbc.update("""
                        INSERT INTO jlpt_transcript_segment(mondai_id,audio_clip_id,text_ja,text_vi,order_index)
                        VALUES (?,?,?,?,1)
                        """, mondaiId, clipId, blank(question.scriptJa()), blank(question.scriptVi()));
            }
        }
        return adminService.listExams().stream().filter(item -> item.code().equals(exam.code()))
                .findFirst().orElseThrow();
    }

    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet guide = workbook.createSheet("Huong_dan");
            String[] help = {
                "Mỗi file tạo đúng một đề JLPT ở trạng thái DRAFT.",
                "Điền sheet Exam: một dòng dữ liệu. Mã ví dụ N3_MOCK_004 hoặc N3_2026_07.",
                "Mondai: mỗi dòng là một 問題. part_code: vocabulary, grammar_reading, listening.",
                "format_code ví dụ: kanji_reading, grammar_form, reading_short, listening_task_based.",
                "Passages: passage_key duy nhất trong từng part_code; bỏ trống sheet nếu đề không có bài đọc.",
                "Questions: mỗi dòng là một câu; passage_key liên kết tới Passages cùng part_code.",
                "option_count từ 2 đến 4; correct_option từ 1 đến option_count.",
                "target_vocabulary_id: ID từ trong vocabulary_entry, chỉ điền cho câu kiểm tra một từ Goi cụ thể; có thể để trống.",
                "Câu ★: correct_order là bốn số 1,2,3,4 theo thứ tự đúng, ví dụ 1,3,4,2.",
                "audio_url và image_url là đường dẫn file local đã có trong static; Excel không chứa MP3/PNG.",
                "Ví dụ audio_url: /audio/JLPT/N3/N3_MOCK_004/Mondai1/1.mp3",
                "Ví dụ image_url: /images/jlpt/N3/N3_MOCK_004/Mondai1/1.png",
                "Import sẽ kiểm tra từng dòng, xem trước lỗi, rồi tạo toàn bộ đề trong một giao dịch."
            };
            for (int i = 0; i < help.length; i++) guide.createRow(i).createCell(0).setCellValue(help[i]);
            guide.setColumnWidth(0, 110 * 256);
            headers(workbook, "Exam", EXAM_HEADERS);
            headers(workbook, "Mondai", MONDAI_HEADERS);
            headers(workbook, "Passages", PASSAGE_HEADERS);
            headers(workbook, "Questions", QUESTION_TEMPLATE_HEADERS);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể tạo file Excel mẫu.", exception);
        }
    }

    private Parsed parse(MultipartFile file) {
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES ||
                file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            errors.add(new Issue("File", 0, "Chọn file .xlsx không quá 10 MB."));
            return new Parsed(null, List.of(), List.of(), List.of(), errors, warnings);
        }
        Workbook workbook;
        try {
            workbook = new XSSFWorkbook(file.getInputStream());
        } catch (Exception exception) {
            errors.add(new Issue("File", 0, "Không đọc được workbook .xlsx hợp lệ."));
            return new Parsed(null, List.of(), List.of(), List.of(), errors, warnings);
        }
        try (workbook) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<Map<String, String>> examRows = rows(workbook, "Exam", EXAM_HEADERS, formatter, errors);
            List<Map<String, String>> mondaiRows = rows(workbook, "Mondai", MONDAI_HEADERS, formatter, errors);
            List<Map<String, String>> passageRows = rows(workbook, "Passages", PASSAGE_HEADERS, formatter, errors);
            List<Map<String, String>> questionRows = rows(workbook, "Questions", QUESTION_HEADERS, formatter, errors);
            Exam exam = readExam(examRows, errors);
            if (exam != null && exists(exam.code())) {
                errors.add(new Issue("Exam", 2, "Mã đề đã tồn tại trong database."));
            }
            List<Mondai> mondai = readMondai(mondaiRows, errors);
            List<Passage> passages = readPassages(passageRows, errors);
            for (Passage passage : passages) {
                if (mondai.stream().noneMatch(item -> item.part().equals(passage.part())))
                    errors.add(new Issue("Passages", passage.row(), "Phần thi này chưa có dòng tương ứng trong sheet Mondai."));
            }
            List<Question> questions = readQuestions(questionRows, exam, mondai, passages, errors, warnings);
            if (mondai.isEmpty()) errors.add(new Issue("Mondai", 2, "Cần ít nhất một 問題."));
            if (questions.isEmpty()) errors.add(new Issue("Questions", 2, "Cần ít nhất một câu hỏi."));
            return new Parsed(exam, mondai, passages, questions, errors, warnings);
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể đóng workbook Excel.", exception);
        }
    }

    private List<Map<String, String>> rows(Workbook workbook, String name, String[] required,
                                            DataFormatter formatter, List<Issue> errors) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            errors.add(new Issue(name, 1, "Thiếu sheet " + name + "."));
            return List.of();
        }
        Row header = sheet.getRow(0);
        if (header == null) {
            errors.add(new Issue(name, 1, "Thiếu hàng tiêu đề."));
            return List.of();
        }
        Map<String, Integer> indexes = new HashMap<>();
        for (Cell cell : header) indexes.put(formatter.formatCellValue(cell).trim().toLowerCase(Locale.ROOT), cell.getColumnIndex());
        for (String column : required) if (!indexes.containsKey(column))
            errors.add(new Issue(name, 1, "Thiếu cột " + column + "."));
        if (errors.stream().anyMatch(issue -> issue.sheet().equals(name) && issue.row() == 1)) return List.of();
        List<Map<String, String>> values = new ArrayList<>();
        if (sheet.getLastRowNum() > MAX_ROWS) {
            errors.add(new Issue(name, MAX_ROWS + 1, "Tối đa 1.000 dòng mỗi sheet."));
            return List.of();
        }
        for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
            Row row = sheet.getRow(rowNumber);
            if (row == null) continue;
            Map<String, String> value = new HashMap<>();
            boolean nonempty = false;
            for (String column : required) {
                String text = formatter.formatCellValue(row.getCell(indexes.get(column))).trim();
                value.put(column, text);
                nonempty |= !text.isEmpty();
            }
            for (String optional : List.of("target_vocabulary_id")) {
                if (indexes.containsKey(optional)) value.put(optional, formatter.formatCellValue(row.getCell(indexes.get(optional))).trim());
                else value.put(optional, "");
            }
            if (nonempty) {
                value.put("_row", Integer.toString(rowNumber + 1));
                values.add(value);
            }
        }
        return values;
    }

    private Exam readExam(List<Map<String, String>> rows, List<Issue> errors) {
        if (rows.size() != 1) {
            errors.add(new Issue("Exam", 2, "Sheet Exam phải có đúng một dòng dữ liệu."));
            return null;
        }
        Map<String, String> row = rows.getFirst();
        String code = row.get("code").toUpperCase(Locale.ROOT);
        String title = row.get("title");
        String level = row.get("level").toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) errors.add(new Issue("Exam", 2, "Mã đề không đúng dạng N3_MOCK_004 hoặc N3_2026_07."));
        if (title.isBlank() || title.length() > 255) errors.add(new Issue("Exam", 2, "Tên đề phải có 1–255 ký tự."));
        if (!level.matches("N[1-5]") || !code.startsWith(level + "_")) errors.add(new Issue("Exam", 2, "Level phải khớp tiền tố mã đề."));
        Integer year = integer(row, "year", "Exam", 2, 2000, 2100, false, errors);
        Integer month = integer(row, "month", "Exam", 2, 7, 12, false, errors);
        if (month != null && month != 7 && month != 12) errors.add(new Issue("Exam", 2, "Tháng chỉ nhận 7 hoặc 12."));
        Integer passing = integer(row, "passing_score", "Exam", 2, 0, 180, true, errors);
        if (code.matches("N[1-5]_\\d{4}_(07|12)")) {
            if (year == null || month == null || !code.endsWith(String.format("%04d_%02d", year, month)))
                errors.add(new Issue("Exam", 2, "Năm/tháng phải khớp mã đề chính thức."));
        }
        return new Exam(code, title, level, year, month, passing == null ? 95 : passing);
    }

    private List<Mondai> readMondai(List<Map<String, String>> rows, List<Issue> errors) {
        List<Mondai> result = new ArrayList<>();
        Map<String, String> partSettings = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, String> row : rows) {
            int line = Integer.parseInt(row.get("_row"));
            String part = row.get("part_code");
            String title = row.get("part_title");
            String format = row.get("format_code");
            Integer duration = integer(row, "duration_minutes", "Mondai", line, 1, 300, true, errors);
            Integer number = integer(row, "mondai_number", "Mondai", line, 1, 30, true, errors);
            if (!PARTS.contains(part)) errors.add(new Issue("Mondai", line, "part_code không hợp lệ."));
            if (title.isBlank() || title.length() > 255) errors.add(new Issue("Mondai", line, "Cần part_title tối đa 255 ký tự."));
            if (!FORMAT.matcher(format).matches()) errors.add(new Issue("Mondai", line, "format_code không hợp lệ."));
            if (PARTS.contains(part) && FORMAT.matcher(format).matches()) {
                Integer otherPart = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM jlpt_question_type WHERE type_code=? AND part_code<>?",
                        Integer.class, format.toUpperCase(Locale.ROOT), part);
                if (otherPart != null && otherPart > 0)
                    errors.add(new Issue("Mondai", line, "format_code đã thuộc một phần thi khác."));
            }
            if (number != null && !seen.add(key(part, number))) errors.add(new Issue("Mondai", line, "Trùng 問題 trong cùng phần."));
            String settings = title + "|" + duration;
            if (PARTS.contains(part) && partSettings.putIfAbsent(part, settings) != null && !partSettings.get(part).equals(settings))
                errors.add(new Issue("Mondai", line, "part_title và duration_minutes phải giống nhau trong cùng phần."));
            if (PARTS.contains(part) && number != null && duration != null && FORMAT.matcher(format).matches())
                result.add(new Mondai(part, title, duration, number, format, row.get("instruction"), line));
        }
        return result;
    }

    private List<Passage> readPassages(List<Map<String, String>> rows, List<Issue> errors) {
        List<Passage> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, String> row : rows) {
            int line = Integer.parseInt(row.get("_row"));
            String part = row.get("part_code");
            String key = row.get("passage_key");
            if (!PARTS.contains(part)) errors.add(new Issue("Passages", line, "part_code không hợp lệ."));
            if (!key.matches("[A-Za-z0-9_-]{1,64}")) errors.add(new Issue("Passages", line, "passage_key chỉ gồm chữ, số, _ hoặc -."));
            if (row.get("passage_ja").isBlank()) errors.add(new Issue("Passages", line, "Thiếu passage_ja."));
            if (!seen.add(key(part, key))) errors.add(new Issue("Passages", line, "Trùng passage_key trong cùng phần."));
            if (PARTS.contains(part) && !row.get("passage_ja").isBlank() && key.matches("[A-Za-z0-9_-]{1,64}"))
                result.add(new Passage(part, key, row.get("passage_ja"), row.get("passage_vi"), line));
        }
        return result;
    }

    private List<Question> readQuestions(List<Map<String, String>> rows, Exam exam,
                                         List<Mondai> mondai, List<Passage> passages,
                                         List<Issue> errors, List<Issue> warnings) {
        List<Question> result = new ArrayList<>();
        Map<String, Mondai> mondaiByKey = new HashMap<>();
        for (Mondai item : mondai) mondaiByKey.put(key(item.part(), item.number()), item);
        Set<String> passageKeys = new HashSet<>();
        for (Passage item : passages) passageKeys.add(key(item.part(), item.key()));
        Set<String> seen = new HashSet<>();
        for (Map<String, String> row : rows) {
            int line = Integer.parseInt(row.get("_row"));
            String part = row.get("part_code");
            Integer m = integer(row, "mondai_number", "Questions", line, 1, 30, true, errors);
            Integer number = integer(row, "question_number", "Questions", line, 1, 1000, true, errors);
            Integer optionCount = integer(row, "option_count", "Questions", line, 2, 4, true, errors);
            Integer answer = integer(row, "correct_option", "Questions", line, 1, 4, true, errors);
            Long targetVocabularyId = null;
            if (!row.getOrDefault("target_vocabulary_id", "").isBlank()) {
                if (!"vocabulary".equals(part)) errors.add(new Issue("Questions", line, "target_vocabulary_id chỉ dùng cho câu thuộc phần vocabulary (Goi)."));
                else {
                    try {
                        long parsedTarget = Long.parseLong(row.get("target_vocabulary_id"));
                        Integer exists = parsedTarget < 1 ? 0 : jdbc.queryForObject("SELECT COUNT(*) FROM vocabulary_entry WHERE id=?", Integer.class, parsedTarget);
                        if (exists == null || exists == 0) errors.add(new Issue("Questions", line, "target_vocabulary_id không tồn tại trong từ điển."));
                        else targetVocabularyId = parsedTarget;
                    } catch (NumberFormatException exception) {
                        errors.add(new Issue("Questions", line, "target_vocabulary_id phải là ID số nguyên dương."));
                    }
                }
            }
            Mondai group = m == null ? null : mondaiByKey.get(key(part, m));
            if (group == null) errors.add(new Issue("Questions", line, "Không tìm thấy part_code + mondai_number trong sheet Mondai."));
            if (number != null && !seen.add(key(key(part, m), number)))
                errors.add(new Issue("Questions", line, "Trùng question_number trong cùng 問題."));
            String text = row.get("question_text");
            if (text.isBlank()) errors.add(new Issue("Questions", line, "Thiếu question_text."));
            String passageKey = row.get("passage_key");
            if (!passageKey.isBlank() && !passageKeys.contains(key(part, passageKey)))
                errors.add(new Issue("Questions", line, "passage_key không có trong sheet Passages cùng phần."));
            List<String> options = new ArrayList<>();
            if (optionCount != null) {
                for (int i = 1; i <= optionCount; i++) options.add(row.get("option_" + i));
                boolean spoken = part.equals("listening") && !row.get("script_ja").isBlank();
                if (!spoken && options.stream().anyMatch(String::isBlank))
                    errors.add(new Issue("Questions", line, "Thiếu lựa chọn trong option_1…option_" + optionCount + "."));
                if (answer != null && answer > optionCount)
                    errors.add(new Issue("Questions", line, "correct_option vượt quá option_count."));
            }
            String order = row.get("correct_order").replace(" ", "");
            if (!order.isBlank()) {
                String[] numbers = order.split(",");
                if (numbers.length != 4 || !new HashSet<>(List.of(numbers)).equals(Set.of("1", "2", "3", "4")) ||
                        group == null || !group.format().equalsIgnoreCase("sentence_composition") ||
                        answer == null || !numbers[1].equals(Integer.toString(answer)))
                    errors.add(new Issue("Questions", line, "correct_order phải là hoán vị 1,2,3,4; đáp án là vị trí ★ thứ hai."));
                else order = "[" + order + "]";
            } else if (group != null && group.format().equalsIgnoreCase("sentence_composition")) {
                errors.add(new Issue("Questions", line, "Câu ★ cần correct_order."));
            }
            BigDecimal score = decimal(row.get("score"), "Questions", line, errors);
            String image = row.get("image_url");
            String audio = row.get("audio_url");
            if (!image.isBlank()) media(image, "/images/jlpt/", "Questions", line, errors);
            if (!audio.isBlank()) {
                media(audio, "/audio/JLPT/", "Questions", line, errors);
                if (exam != null && !audio.startsWith("/audio/JLPT/" + exam.level() + "/" + exam.code() + "/"))
                    errors.add(new Issue("Questions", line, "audio_url phải nằm trong thư mục của đúng đề."));
                if (row.get("script_ja").isBlank()) warnings.add(new Issue("Questions", line, "Audio chưa có script_ja."));
            } else if (part.equals("listening")) {
                warnings.add(new Issue("Questions", line, "Câu nghe chưa có audio_url; có thể bổ sung khi biên tập."));
            }
            if (!image.isBlank() && exam != null && !image.startsWith("/images/jlpt/" + exam.level() + "/" + exam.code() + "/"))
                errors.add(new Issue("Questions", line, "image_url phải nằm trong thư mục của đúng đề."));
            if (row.get("explanation").isBlank()) warnings.add(new Issue("Questions", line, "Câu chưa có giải thích đáp án."));
            if (group != null && m != null && number != null && optionCount != null && answer != null && !text.isBlank())
                result.add(new Question(part, m, number, text, passageKey, List.copyOf(options), answer,
                        order, score, row.get("explanation"), image, audio, row.get("script_ja"), row.get("script_vi"), targetVocabularyId, line));
        }
        return result;
    }

    private void media(String url, String prefix, String sheet, int row, List<Issue> errors) {
        if (!url.startsWith(prefix) || url.contains("..") || url.contains("\\") || url.contains("?") || url.contains("#") ||
                !new ClassPathResource("static" + url).exists())
            errors.add(new Issue(sheet, row, "File media không tồn tại hoặc đường dẫn local không hợp lệ: " + url));
    }

    private Integer integer(Map<String, String> row, String column, String sheet, int line,
                            int min, int max, boolean required, List<Issue> errors) {
        String value = row.get(column);
        if (value.isBlank() && !required) return null;
        try {
            int number = Integer.parseInt(value);
            if (number >= min && number <= max) return number;
        } catch (NumberFormatException ignored) { }
        errors.add(new Issue(sheet, line, column + " phải là số nguyên từ " + min + " đến " + max + "."));
        return null;
    }

    private BigDecimal decimal(String value, String sheet, int line, List<Issue> errors) {
        if (value.isBlank()) return BigDecimal.ONE;
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.signum() >= 0 && parsed.compareTo(new BigDecimal("9999.99")) <= 0 && parsed.scale() <= 2)
                return parsed;
        } catch (NumberFormatException ignored) { }
        errors.add(new Issue(sheet, line, "score phải từ 0 đến 9999.99, tối đa 2 chữ số thập phân."));
        return BigDecimal.ONE;
    }

    private void headers(Workbook workbook, String name, String[] columns) {
        Sheet sheet = workbook.createSheet(name);
        Row row = sheet.createRow(0);
        for (int i = 0; i < columns.length; i++) {
            row.createCell(i).setCellValue(columns[i]);
            sheet.setColumnWidth(i, Math.min(35, Math.max(14, columns[i].length() + 3)) * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    private long insert(String query, Object... values) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("Không lấy được ID sau khi import.");
        return keys.getKey().longValue();
    }

    private boolean exists(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM jlpt_exam WHERE exam_code=?", Integer.class, code);
        return count != null && count > 0;
    }

    private long ensureQuestionType(String part, String format) {
        String code = format.toUpperCase(Locale.ROOT);
        List<Long> matches = jdbc.query("SELECT id FROM jlpt_question_type WHERE type_code=? AND part_code=?",
                (rs, row) -> rs.getLong(1), code, part);
        if (!matches.isEmpty()) return matches.getFirst();
        Integer order = jdbc.queryForObject(
                "SELECT COALESCE(MAX(order_index),0)+1 FROM jlpt_question_type", Integer.class);
        try {
            jdbc.update("INSERT INTO jlpt_question_type(type_code,part_code,name_vi,name_ja,order_index) " +
                    "VALUES (?,?,?,NULL,?)", code, part, code.replace('_', ' '), order);
        } catch (DuplicateKeyException ignored) {
            // Another request may have inserted the same reusable type.
        }
        return jdbc.queryForObject("SELECT id FROM jlpt_question_type WHERE type_code=? AND part_code=?",
                Long.class, code, part);
    }

    private static String html(String text) {
        if (text == null) return null;
        return HtmlUtils.htmlEscape(text).replace("&lt;u&gt;", "<u>").replace("&lt;/u&gt;", "</u>")
                .replace("\n", "<br/>");
    }
    private static String blank(String text) { return text == null || text.isBlank() ? null : text; }
    private static String key(String first, Object second) { return first + ":" + second; }
}
