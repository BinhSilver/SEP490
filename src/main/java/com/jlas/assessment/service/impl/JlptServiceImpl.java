package com.jlas.assessment.service.impl;

import com.jlas.assessment.dto.JlptExamResponse;
import com.jlas.assessment.dto.JlptExamSummaryResponse;
import com.jlas.assessment.dto.JlptPracticeQuestionResponse;
import com.jlas.assessment.dto.JlptPracticeTypeResponse;
import com.jlas.assessment.service.JlptService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class JlptServiceImpl implements JlptService {

    private final JdbcTemplate jdbc;
    private final JlptLocalAudio localAudio;

    @Override
    public List<JlptExamSummaryResponse> listExams(String level) {
        return jdbc.query("""
                SELECT e.exam_code,e.title,e.jlpt_level,e.exam_year,e.exam_month,e.status,
                       COUNT(CASE WHEN p.part_code = 'listening' AND
                           REPLACE(REPLACE(TRIM(q.question_text),' ',''),'　','') IN
                           ('れい','例','(れい)','（れい）','(例)','（例）','(例(れい))','（例（れい））')
                           THEN NULL ELSE q.id END) AS question_count,
                       COALESCE((SELECT SUM(p2.duration_minutes) FROM jlpt_part p2 WHERE p2.exam_id=e.id),0)
                           AS duration_minutes
                FROM jlpt_exam e
                LEFT JOIN jlpt_part p ON p.exam_id=e.id
                LEFT JOIN jlpt_mondai m ON m.part_id=p.id
                LEFT JOIN jlpt_question q ON q.mondai_id=m.id
                WHERE e.jlpt_level=? AND e.status IN ('DRAFT','PUBLISHED')
                GROUP BY e.id,e.exam_code,e.title,e.jlpt_level,e.exam_year,e.exam_month,e.status
                ORDER BY e.exam_year DESC,e.exam_month DESC
                """, (rs, row) -> new JlptExamSummaryResponse(
                rs.getString("exam_code"),
                rs.getString("title"),
                rs.getString("jlpt_level"),
                nullableInt(rs.getObject("exam_year")),
                nullableInt(rs.getObject("exam_month")),
                rs.getString("status"),
                rs.getInt("question_count"),
                rs.getInt("duration_minutes")), level);
    }

    @Override
    public List<JlptPracticeTypeResponse> listPracticeTypes(String level) {
        return jdbc.query("""
                SELECT inventory.type_code, inventory.question_count,
                       config.name_vi, config.name_ja, config.order_index
                FROM (
                    SELECT COALESCE(qt.type_code, NULLIF(UPPER(m.format_code), ''),
                                    CONCAT(UPPER(p.part_code), '_OTHER')) AS type_code,
                           COUNT(q.id) AS question_count,
                           MIN(p.order_index) AS part_order,
                           MIN(m.order_index) AS mondai_order
                    FROM jlpt_question q
                    JOIN jlpt_mondai m ON m.id=q.mondai_id
                    JOIN jlpt_part p ON p.id=m.part_id
                    JOIN jlpt_exam e ON e.id=p.exam_id
                    LEFT JOIN jlpt_question_type qt ON qt.id=q.question_type_id
                    WHERE e.jlpt_level=? AND e.status IN ('DRAFT','PUBLISHED')
                      AND NOT (p.part_code = 'listening' AND
                          REPLACE(REPLACE(TRIM(q.question_text),' ',''),'　','') IN
                          ('れい','例','(れい)','（れい）','(例)','（例）','(例(れい))','（例（れい））'))
                    GROUP BY COALESCE(qt.type_code, NULLIF(UPPER(m.format_code), ''),
                                      CONCAT(UPPER(p.part_code), '_OTHER'))
                ) inventory
                LEFT JOIN jlpt_practice_type_config config
                  ON config.level_code=? AND config.type_code=inventory.type_code
                WHERE COALESCE(config.is_enabled, TRUE)=TRUE
                ORDER BY COALESCE(config.order_index, inventory.part_order * 1000 + inventory.mondai_order),
                         inventory.part_order,inventory.mondai_order,inventory.type_code
                """, (rs, row) -> {
            String code = rs.getString("type_code");
            String nameVi = rs.getString("name_vi");
            String nameJa = rs.getString("name_ja");
            return new JlptPracticeTypeResponse(code, sectionForType(code),
                    nameVi == null || nameVi.isBlank() ? typeName(code) : nameVi,
                    nameJa == null || nameJa.isBlank() ? typeNameJa(code) : nameJa,
                    rs.getInt("question_count"));
        }, level, level);
    }

    @Override
    public List<JlptPracticeQuestionResponse> practiceQuestions(
            String level, String section, String typeCode, int limit) {
        if (!section.equals(sectionForType(typeCode))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dạng câu hỏi không thuộc phần đã chọn.");
        }
        Boolean enabled = jdbc.queryForObject("""
                SELECT COALESCE((SELECT is_enabled FROM jlpt_practice_type_config
                                 WHERE level_code=? AND type_code=?), TRUE)
                """, Boolean.class, level, typeCode);
        if (!Boolean.TRUE.equals(enabled)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dạng luyện này hiện đang ẩn.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT q.id,q.question_number,q.question_text,q.question_furigana,
                       q.question_html,q.correct_option,q.correct_order,q.score,q.image_url,q.mondai_id,
                       m.instruction_html,m.audio_url AS mondai_audio_url,m.mondai_number,p.part_code,
                       e.exam_code,e.title AS exam_title,e.jlpt_level,
                       ps.source_passage_id,ps.passage_ja,ps.passage_html,ps.passage_vi,
                       COALESCE(qt.type_code, NULLIF(UPPER(m.format_code), ''),
                                CONCAT(UPPER(p.part_code), '_OTHER')) AS type_code
                FROM jlpt_question q
                JOIN jlpt_mondai m ON m.id=q.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id
                JOIN jlpt_exam e ON e.id=p.exam_id
                LEFT JOIN jlpt_question_type qt ON qt.id=q.question_type_id
                LEFT JOIN jlpt_passage ps ON ps.id=q.passage_id
                WHERE e.jlpt_level=? AND e.status IN ('DRAFT','PUBLISHED')
                  AND COALESCE(qt.type_code, NULLIF(UPPER(m.format_code), ''),
                               CONCAT(UPPER(p.part_code), '_OTHER'))=?
                  AND NOT (p.part_code = 'listening' AND
                      REPLACE(REPLACE(TRIM(q.question_text),' ',''),'　','') IN
                      ('れい','例','(れい)','（れい）','(例)','（例）','(例(れい))','（例（れい））'))
                ORDER BY RAND()
                LIMIT ?
                """, level, typeCode, limit);

        List<JlptPracticeQuestionResponse> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long questionId = longValue(row.get("id"));
            long mondaiId = longValue(row.get("mondai_id"));
            Long passageSourceId = nullableLong(row.get("source_passage_id"));
            JlptExamResponse.Passage passage = passageSourceId == null ? null
                    : new JlptExamResponse.Passage(passageSourceId, string(row.get("passage_ja")),
                            string(row.get("passage_html")), string(row.get("passage_vi")));
            String code = string(row.get("type_code"));
            JlptExamResponse.AudioClip clip = practiceClip(questionId);
            if ("listening".equals(string(row.get("part_code")))) {
                clip = localAudio.resolve(string(row.get("exam_code")), intValue(row.get("mondai_number")),
                        intValue(row.get("question_number")), questionId, clip);
            }
            boolean local = clip != null && clip.audioUrl() != null && clip.audioUrl().startsWith("/audio/JLPT/");
            result.add(new JlptPracticeQuestionResponse(
                    questionId,
                    string(row.get("exam_code")),
                    string(row.get("exam_title")),
                    string(row.get("jlpt_level")),
                    sectionForType(code),
                    code,
                    typeName(code),
                    string(row.get("instruction_html")),
                    intValue(row.get("question_number")),
                    string(row.get("question_text")),
                    string(row.get("question_furigana")),
                    string(row.get("question_html")),
                    nullableInt(row.get("correct_option")),
                    (java.math.BigDecimal) row.get("score"),
                    string(row.get("image_url")),
                    passage,
                    clip,
                    local ? null : string(row.get("mondai_audio_url")),
                    local ? List.of() : practiceTranscript(mondaiId),
                    practiceOptions(questionId), JlptSentenceOrder.read(row.get("correct_order"))));
        }
        return result;
    }

    private List<JlptExamResponse.Option> practiceOptions(long questionId) {
        return jdbc.query("""
                SELECT option_number,option_text,option_furigana,option_html
                FROM jlpt_option WHERE question_id=? ORDER BY order_index
                """, (rs, row) -> new JlptExamResponse.Option(rs.getInt("option_number"),
                rs.getString("option_text"), rs.getString("option_furigana"),
                rs.getString("option_html")), questionId);
    }

    private JlptExamResponse.AudioClip practiceClip(long questionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,clip_type,audio_url
                FROM jlpt_audio_clip WHERE question_id=?
                """, questionId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        long clipId = longValue(row.get("id"));
        List<JlptExamResponse.TranscriptSegment> transcript = jdbc.query("""
                SELECT text_ja,text_ja_furigana,text_vi
                FROM jlpt_transcript_segment WHERE audio_clip_id=? ORDER BY order_index
                """, (rs, index) -> new JlptExamResponse.TranscriptSegment(
                null, null, rs.getString("text_ja"),
                rs.getString("text_ja_furigana"), rs.getString("text_vi")), clipId);
        return new JlptExamResponse.AudioClip(clipId, string(row.get("clip_type")),
                string(row.get("audio_url")), 0, null, transcript, false);
    }

    private List<JlptExamResponse.TranscriptSegment> practiceTranscript(long mondaiId) {
        return jdbc.query("""
                SELECT text_ja,text_ja_furigana,text_vi
                FROM jlpt_transcript_segment WHERE mondai_id=? ORDER BY order_index
                """, (rs, row) -> new JlptExamResponse.TranscriptSegment(
                null, null, rs.getString("text_ja"),
                rs.getString("text_ja_furigana"), rs.getString("text_vi")), mondaiId);
    }

    public static String sectionForType(String code) {
        if (code == null) return "vocabulary";
        if (code.startsWith("LISTENING_")) return "listening";
        if (code.startsWith("READING_") || code.equals("INFO_RETRIEVAL")
                || code.equals("INTEGRATED_COMPREHENSION") || code.equals("THEMATIC_COMPREHENSION")) {
            return "reading";
        }
        if (code.equals("GRAMMAR_FORM") || code.equals("SENTENCE_COMPOSITION")
                || code.equals("TEXT_GRAMMAR") || code.equals("GRAMMAR_READING_OTHER")) {
            return "grammar";
        }
        return "vocabulary";
    }

    public static String typeName(String code) {
        return switch (code) {
            case "KANJI_READING" -> "Cách đọc Kanji";
            case "ORTHOGRAPHY" -> "Chọn Kanji đúng";
            case "CONTEXT" -> "Từ vựng trong ngữ cảnh";
            case "PARAPHRASE" -> "Diễn đạt tương đương";
            case "USAGE" -> "Cách dùng từ";
            case "WORD_FORMATION" -> "Cấu tạo từ";
            case "GRAMMAR_FORM" -> "Chọn dạng ngữ pháp";
            case "SENTENCE_COMPOSITION" -> "Sắp xếp câu";
            case "TEXT_GRAMMAR" -> "Ngữ pháp trong đoạn văn";
            case "READING_SHORT" -> "Đoản văn";
            case "READING_MEDIUM" -> "Trung văn";
            case "READING_LONG" -> "Trường văn";
            case "INFO_RETRIEVAL" -> "Tìm kiếm thông tin";
            case "INTEGRATED_COMPREHENSION" -> "Đọc hiểu tổng hợp";
            case "THEMATIC_COMPREHENSION" -> "Đọc hiểu chủ đề";
            case "LISTENING_TASK_BASED" -> "Nghe hiểu nhiệm vụ";
            case "LISTENING_KEY_POINT" -> "Nghe hiểu điểm chính";
            case "LISTENING_GENERAL_OUTLINE" -> "Nghe hiểu khái quát";
            case "LISTENING_VERBAL_EXPRESSION" -> "Biểu đạt bằng lời";
            case "LISTENING_QUICK_RESPONSE" -> "Phản xạ nhanh";
            case "LISTENING_INTEGRATED" -> "Nghe hiểu tổng hợp";
            default -> "Dạng khác";
        };
    }

    public static String typeNameJa(String code) {
        return switch (code) {
            case "KANJI_READING" -> "漢字読み";
            case "ORTHOGRAPHY" -> "表記";
            case "CONTEXT" -> "文脈規定";
            case "PARAPHRASE" -> "言い換え類義";
            case "USAGE" -> "用法";
            case "WORD_FORMATION" -> "語形成";
            case "GRAMMAR_FORM" -> "文の文法";
            case "SENTENCE_COMPOSITION" -> "文の組み立て";
            case "TEXT_GRAMMAR" -> "文章の文法";
            case "READING_SHORT" -> "短文";
            case "READING_MEDIUM" -> "中文";
            case "READING_LONG" -> "長文";
            case "INFO_RETRIEVAL" -> "情報検索";
            case "INTEGRATED_COMPREHENSION" -> "統合理解";
            case "THEMATIC_COMPREHENSION" -> "主張理解";
            case "LISTENING_TASK_BASED" -> "課題理解";
            case "LISTENING_KEY_POINT" -> "ポイント理解";
            case "LISTENING_GENERAL_OUTLINE" -> "概要理解";
            case "LISTENING_VERBAL_EXPRESSION" -> "発話表現";
            case "LISTENING_QUICK_RESPONSE" -> "即時応答";
            case "LISTENING_INTEGRATED" -> "統合理解";
            default -> null;
        };
    }

    @Override
    public JlptExamResponse getExam(String code) {
        return getExam(code, false);
    }

    @Override
    public JlptExamResponse getExamForAdmin(String code) {
        return getExam(code, true);
    }

    private JlptExamResponse getExam(String code, boolean includeArchived) {
        String examSql = """
                SELECT id, exam_code, title, jlpt_level, exam_year, exam_month, review_status
                FROM jlpt_exam
                WHERE exam_code = ?
                """ + (includeArchived ? "" : " AND status IN ('DRAFT', 'PUBLISHED')");
        List<Map<String, Object>> exams = jdbc.queryForList(examSql, code);
        if (exams.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đề JLPT.");
        }
        Map<String, Object> exam = exams.getFirst();
        long examId = longValue(exam.get("id"));

        Map<Long, List<JlptExamResponse.Option>> options = options(examId);
        Map<Long, List<JlptExamResponse.TranscriptSegment>> clipTranscripts = clipTranscripts(examId);
        Map<Long, JlptExamResponse.AudioClip> clips = clips(examId, clipTranscripts);
        Map<Long, List<JlptExamResponse.Question>> questions = questions(examId, options, clips);
        Map<Long, List<JlptExamResponse.TranscriptSegment>> transcripts = transcripts(examId);
        Map<Long, List<JlptExamResponse.Mondai>> mondai = mondai(examId, questions, transcripts);

        List<JlptExamResponse.Part> parts = jdbc.query("""
                SELECT id, part_code, title, duration_minutes
                FROM jlpt_part WHERE exam_id = ? ORDER BY order_index
                """, (rs, row) -> {
            long id = rs.getLong("id");
            Integer duration = nullableInt(rs.getObject("duration_minutes"));
            return new JlptExamResponse.Part(id, rs.getString("part_code"), rs.getString("title"),
                    duration, mondai.getOrDefault(id, List.of()));
        }, examId);

        return new JlptExamResponse(
                string(exam.get("exam_code")),
                string(exam.get("title")),
                string(exam.get("jlpt_level")),
                nullableInt(exam.get("exam_year")),
                nullableInt(exam.get("exam_month")),
                string(exam.get("review_status")),
                parts);
    }

    private Map<Long, List<JlptExamResponse.Option>> options(long examId) {
        Map<Long, List<JlptExamResponse.Option>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT o.question_id, o.option_number, o.option_text, o.option_furigana, o.option_html
                FROM jlpt_option o
                JOIN jlpt_question q ON q.id=o.question_id
                JOIN jlpt_mondai m ON m.id=q.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id
                WHERE p.exam_id=? ORDER BY o.question_id,o.order_index
                """, (RowCallbackHandler) rs -> result.computeIfAbsent(rs.getLong("question_id"), ignored -> new java.util.ArrayList<>())
                .add(new JlptExamResponse.Option(rs.getInt("option_number"), rs.getString("option_text"),
                        rs.getString("option_furigana"), rs.getString("option_html"))), examId);
        return result;
    }

    private Map<Long, List<JlptExamResponse.Question>> questions(
            long examId,
            Map<Long, List<JlptExamResponse.Option>> options,
            Map<Long, JlptExamResponse.AudioClip> clips) {
        Map<Long, List<JlptExamResponse.Question>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT q.*, ps.source_passage_id,ps.passage_ja,ps.passage_html,ps.passage_vi,
                       e.exam_code,m.mondai_number,p.part_code,
                       COALESCE(qt.type_code,m.format_code) AS question_type
                FROM jlpt_question q
                JOIN jlpt_mondai m ON m.id=q.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id
                JOIN jlpt_exam e ON e.id=p.exam_id
                LEFT JOIN jlpt_passage ps ON ps.id=q.passage_id
                LEFT JOIN jlpt_question_type qt ON qt.id=q.question_type_id
                WHERE p.exam_id=?
                  AND NOT (p.part_code = 'listening' AND
                      REPLACE(REPLACE(TRIM(q.question_text),' ',''),'　','') IN
                      ('れい','例','(れい)','（れい）','(例)','（例）','(例(れい))','（例（れい））'))
                ORDER BY m.id,q.order_index
                """, (RowCallbackHandler) rs -> {
            long id = rs.getLong("id");
            Long passageSourceId = nullableLong(rs.getObject("source_passage_id"));
            JlptExamResponse.Passage passage = passageSourceId == null ? null
                    : new JlptExamResponse.Passage(passageSourceId, rs.getString("passage_ja"),
                            rs.getString("passage_html"), rs.getString("passage_vi"));
            Integer correct = nullableInt(rs.getObject("correct_option"));
            JlptExamResponse.AudioClip clip = clips.get(id);
            if ("listening".equals(rs.getString("part_code"))) {
                clip = localAudio.resolve(rs.getString("exam_code"), rs.getInt("mondai_number"),
                        rs.getInt("question_number"), id, clip);
            }
            result.computeIfAbsent(rs.getLong("mondai_id"), ignored -> new java.util.ArrayList<>())
                    .add(new JlptExamResponse.Question(id,
                            rs.getInt("question_number"), rs.getString("question_text"),
                            rs.getString("question_furigana"), rs.getString("question_html"),
                            rs.getString("question_type"), correct,
                            rs.getBigDecimal("score"), rs.getString("image_url"),
                            nullableInt(rs.getObject("source_page")), rs.getString("explanation"), passage, clip,
                            options.getOrDefault(id, List.of()), JlptSentenceOrder.read(rs.getString("correct_order")),
                            nullableLong(rs.getObject("target_vocabulary_id"))));
        }, examId);
        return result;
    }

    private Map<Long, List<JlptExamResponse.TranscriptSegment>> clipTranscripts(long examId) {
        Map<Long, List<JlptExamResponse.TranscriptSegment>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.* FROM jlpt_transcript_segment t
                JOIN jlpt_audio_clip c ON c.id=t.audio_clip_id
                JOIN jlpt_mondai m ON m.id=c.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id
                WHERE p.exam_id=? ORDER BY t.audio_clip_id,t.order_index
                """, (RowCallbackHandler) rs -> result
                .computeIfAbsent(rs.getLong("audio_clip_id"), ignored -> new java.util.ArrayList<>())
                .add(new JlptExamResponse.TranscriptSegment(null,
                        null, rs.getString("text_ja"),
                        rs.getString("text_ja_furigana"), rs.getString("text_vi"))), examId);
        return result;
    }

    private Map<Long, JlptExamResponse.AudioClip> clips(
            long examId, Map<Long, List<JlptExamResponse.TranscriptSegment>> transcripts) {
        Map<Long, JlptExamResponse.AudioClip> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.* FROM jlpt_audio_clip c
                JOIN jlpt_mondai m ON m.id=c.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id
                WHERE p.exam_id=? ORDER BY c.mondai_id,c.order_index
                """, (RowCallbackHandler) rs -> {
            long clipId = rs.getLong("id");
            result.put(rs.getLong("question_id"), new JlptExamResponse.AudioClip(
                    clipId, rs.getString("clip_type"), rs.getString("audio_url"),
                    0, null,
                    transcripts.getOrDefault(clipId, List.of()), false));
        }, examId);
        return result;
    }

    private Map<Long, List<JlptExamResponse.TranscriptSegment>> transcripts(long examId) {
        Map<Long, List<JlptExamResponse.TranscriptSegment>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.* FROM jlpt_transcript_segment t
                JOIN jlpt_mondai m ON m.id=t.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id
                WHERE p.exam_id=? ORDER BY t.mondai_id,t.order_index
                """, (RowCallbackHandler) rs -> result.computeIfAbsent(rs.getLong("mondai_id"), ignored -> new java.util.ArrayList<>())
                .add(new JlptExamResponse.TranscriptSegment(null,
                        null, rs.getString("text_ja"),
                        rs.getString("text_ja_furigana"), rs.getString("text_vi"))), examId);
        return result;
    }

    private Map<Long, List<JlptExamResponse.Mondai>> mondai(
            long examId,
            Map<Long, List<JlptExamResponse.Question>> questions,
            Map<Long, List<JlptExamResponse.TranscriptSegment>> transcripts) {
        Map<Long, List<JlptExamResponse.Mondai>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT m.* FROM jlpt_mondai m
                JOIN jlpt_part p ON p.id=m.part_id
                WHERE p.exam_id=? ORDER BY p.order_index,m.order_index
                """, (RowCallbackHandler) rs -> {
            long id = rs.getLong("id");
            List<JlptExamResponse.Question> items = questions.getOrDefault(id, List.of());
            boolean allLocal = !items.isEmpty() && items.stream().allMatch(q -> q.audioClip() != null
                    && q.audioClip().audioUrl() != null && q.audioClip().audioUrl().startsWith("/audio/JLPT/"));
            result.computeIfAbsent(rs.getLong("part_id"), ignored -> new java.util.ArrayList<>())
                    .add(new JlptExamResponse.Mondai(id, rs.getInt("mondai_number"),
                            rs.getString("format_code"), rs.getString("instruction_html"),
                            allLocal ? null : rs.getString("audio_url"), items,
                            allLocal ? List.of() : transcripts.getOrDefault(id, List.of())));
        }, examId);
        return result;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static long longValue(Object value) {
        return ((Number) value).longValue();
    }

    private static int intValue(Object value) {
        return ((Number) value).intValue();
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
