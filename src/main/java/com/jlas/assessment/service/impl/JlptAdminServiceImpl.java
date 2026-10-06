package com.jlas.assessment.service.impl;

import com.jlas.assessment.dto.JlptAdminExamDetailResponse;
import com.jlas.assessment.dto.JlptAdminExamRequest;
import com.jlas.assessment.dto.JlptAdminExamSummaryResponse;
import com.jlas.assessment.dto.JlptAdminQuestionRequest;
import com.jlas.assessment.entity.JlptExam;
import com.jlas.assessment.repository.JlptExamRepository;
import com.jlas.assessment.service.JlptAdminService;
import com.jlas.assessment.service.JlptService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class JlptAdminServiceImpl implements JlptAdminService {

    private final JdbcTemplate jdbc;
    private final JlptExamRepository examRepository;
    private final JlptService jlptService;

    @Override
    @Transactional(readOnly = true)
    public List<JlptAdminExamSummaryResponse> listExams() {
        return jdbc.query("""
                SELECT e.id,e.exam_code,e.title,e.jlpt_level,e.exam_year,e.exam_month,e.status,
                       e.passing_score,COUNT(q.id) question_count,
                       COALESCE((SELECT SUM(p2.duration_minutes) FROM jlpt_part p2 WHERE p2.exam_id=e.id),0) duration_minutes
                FROM jlpt_exam e
                LEFT JOIN jlpt_part p ON p.exam_id=e.id
                LEFT JOIN jlpt_mondai m ON m.part_id=p.id
                LEFT JOIN jlpt_question q ON q.mondai_id=m.id
                GROUP BY e.id,e.exam_code,e.title,e.jlpt_level,e.exam_year,e.exam_month,e.status,
                         e.passing_score
                ORDER BY e.exam_year DESC,e.exam_month DESC,e.id DESC
                """, (rs, row) -> new JlptAdminExamSummaryResponse(
                rs.getLong("id"), rs.getString("exam_code"), rs.getString("title"),
                rs.getString("jlpt_level"), nullableInt(rs.getObject("exam_year")),
                nullableInt(rs.getObject("exam_month")), rs.getString("status"),
                rs.getInt("passing_score"),
                rs.getInt("question_count"), rs.getInt("duration_minutes")));
    }

    @Override
    @Transactional(readOnly = true)
    public JlptAdminExamDetailResponse getExam(String code) {
        return new JlptAdminExamDetailResponse(summary(code), jlptService.getExamForAdmin(code));
    }

    @Override
    @Transactional
    public JlptAdminExamSummaryResponse createExam(JlptAdminExamRequest request) {
        String code = normalizeCode(request.code());
        if (examRepository.existsByExamCode(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã đề đã tồn tại.");
        }
        JlptExam exam = apply(JlptExam.builder().examCode(code).build(), request);
        examRepository.saveAndFlush(exam);
        createDefaultStructure(exam.getId());
        return summary(code);
    }

    @Override
    @Transactional
    public JlptAdminExamSummaryResponse updateExam(String code, JlptAdminExamRequest request) {
        JlptExam exam = findExam(code);
        String newCode = normalizeCode(request.code());
        examRepository.findByExamCode(newCode).filter(other -> !other.getId().equals(exam.getId()))
                .ifPresent(other -> { throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã đề đã tồn tại."); });
        exam.setExamCode(newCode);
        apply(exam, request);
        examRepository.saveAndFlush(exam);
        return summary(newCode);
    }

    @Override
    @Transactional
    public long createQuestion(String examCode, JlptAdminQuestionRequest request) {
        assertMondaiBelongsToExam(request.mondaiId(), examCode);
        return insertQuestion(request, null);
    }

    @Override
    @Transactional
    public void updateQuestion(long questionId, JlptAdminQuestionRequest request) {
        QuestionLocation old = questionLocation(questionId);
        assertMondaiExists(request.mondaiId());
        Long passageId = upsertPassage(old.passageId(), request.mondaiId(), request.passage());
        long sourceId = old.mondaiId() == request.mondaiId()
                ? old.sourceId() : nextLong("jlpt_question", "source_question_id", "mondai_id", request.mondaiId());
        int order = old.mondaiId() == request.mondaiId()
                ? old.orderIndex() : nextInt("jlpt_question", "order_index", "mondai_id", request.mondaiId());
        Long typeId = ensureQuestionType(request.type(), request.mondaiId());
        jdbc.update("""
                UPDATE jlpt_question SET mondai_id=?,passage_id=?,question_type_id=?,source_question_id=?,
                    question_number=?,question_text=?,question_furigana=?,question_html=?,correct_option=?,score=?,
                    image_url=?,source_page=?,explanation=?,order_index=?,correct_order=? WHERE id=?
                """, request.mondaiId(), passageId, typeId, sourceId, request.number(), request.prompt(),
                request.prompt(), request.prompt(), JlptSentenceOrder.correctChoice(request), defaultScore(request), blankToNull(request.imageUrl()),
                request.sourcePage(), blankToNull(request.explanation()), order, JlptSentenceOrder.write(request), questionId);
        replaceOptions(questionId, request.choices());
        replaceAudio(questionId, request.mondaiId(), request);
        deletePassageIfOrphan(old.passageId(), passageId);
    }

    @Override
    @Transactional
    public void deleteQuestion(long questionId) {
        QuestionLocation location = questionLocation(questionId);
        deleteClipTranscripts(questionId);
        jdbc.update("DELETE FROM jlpt_question WHERE id=?", questionId);
        deletePassageIfOrphan(location.passageId(), null);
    }

    @Override
    @Transactional
    public long duplicateQuestion(long questionId) {
        QuestionLocation location = questionLocation(questionId);
        List<String> choices = jdbc.query("SELECT option_text FROM jlpt_option WHERE question_id=? ORDER BY order_index",
                (rs, row) -> rs.getString(1), questionId);
        var row = jdbc.queryForMap("""
                SELECT COALESCE(qt.type_code,m.format_code,'OTHER') type_code,q.question_number,q.question_text,
                       q.correct_option,q.correct_order,q.score,q.image_url,q.source_page,q.explanation,
                       ps.passage_html,ps.passage_ja,c.audio_url,c.start_seconds,c.end_seconds
                FROM jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
                LEFT JOIN jlpt_question_type qt ON qt.id=q.question_type_id
                LEFT JOIN jlpt_passage ps ON ps.id=q.passage_id
                LEFT JOIN jlpt_audio_clip c ON c.question_id=q.id WHERE q.id=?
                """, questionId);
        JlptAdminQuestionRequest request = new JlptAdminQuestionRequest(
                location.mondaiId(), nextInt("jlpt_question", "question_number", "mondai_id", location.mondaiId()),
                String.valueOf(row.get("type_code")), String.valueOf(row.get("question_text")),
                stringOrNull(row.get("passage_html")) != null ? stringOrNull(row.get("passage_html")) : stringOrNull(row.get("passage_ja")),
                choices, nullableInt(row.get("correct_option")), (java.math.BigDecimal) row.get("score"),
                stringOrNull(row.get("image_url")), nullableInt(row.get("source_page")),
                stringOrNull(row.get("explanation")), transcriptText(questionId, "text_ja"),
                transcriptText(questionId, "text_vi"), stringOrNull(row.get("audio_url")),
                decimalOrNull(row.get("start_seconds")), decimalOrNull(row.get("end_seconds")),
                row.get("correct_order") == null ? null : JlptSentenceOrder.read(row.get("correct_order")));
        return insertQuestion(request, location.passageId());
    }

    private long insertQuestion(JlptAdminQuestionRequest request, Long sharedPassageId) {
        assertMondaiExists(request.mondaiId());
        Long passageId = sharedPassageId != null ? sharedPassageId
                : upsertPassage(null, request.mondaiId(), request.passage());
        Long typeId = ensureQuestionType(request.type(), request.mondaiId());
        long sourceId = nextLong("jlpt_question", "source_question_id", "mondai_id", request.mondaiId());
        int order = nextInt("jlpt_question", "order_index", "mondai_id", request.mondaiId());
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO jlpt_question
                    (mondai_id,passage_id,question_type_id,source_question_id,question_number,question_text,
                     question_furigana,question_html,correct_option,score,image_url,source_page,explanation,order_index,correct_order)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, request.mondaiId());
            setNullableLong(statement, 2, passageId);
            setNullableLong(statement, 3, typeId);
            statement.setLong(4, sourceId);
            statement.setInt(5, request.number());
            statement.setString(6, request.prompt());
            statement.setString(7, request.prompt());
            statement.setString(8, request.prompt());
            setNullableInt(statement, 9, JlptSentenceOrder.correctChoice(request));
            statement.setBigDecimal(10, defaultScore(request));
            statement.setString(11, blankToNull(request.imageUrl()));
            setNullableInt(statement, 12, request.sourcePage());
            statement.setString(13, blankToNull(request.explanation()));
            statement.setInt(14, order);
            statement.setString(15, JlptSentenceOrder.write(request));
            return statement;
        }, keys);
        long id = requiredKey(keys);
        replaceOptions(id, request.choices());
        replaceAudio(id, request.mondaiId(), request);
        return id;
    }

    private void replaceAudio(long questionId, long mondaiId, JlptAdminQuestionRequest request) {
        String audioUrl = blankToNull(request.audioUrl());
        List<Long> existing = jdbc.query("SELECT id FROM jlpt_audio_clip WHERE question_id=?",
                (rs, row) -> rs.getLong(1), questionId);
        if (audioUrl == null) {
            if (!existing.isEmpty()) {
                deleteClipTranscripts(questionId);
                jdbc.update("DELETE FROM jlpt_audio_clip WHERE id=?", existing.getFirst());
            }
            return;
        }
        java.math.BigDecimal start = request.audioStart() == null ? java.math.BigDecimal.ZERO : request.audioStart();
        java.math.BigDecimal end = request.audioEnd();
        if (end == null || end.compareTo(start) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời gian kết thúc audio phải lớn hơn thời gian bắt đầu.");
        }
        long clipId;
        if (existing.isEmpty()) {
            KeyHolder keys = new GeneratedKeyHolder();
            int order = nextInt("jlpt_audio_clip", "order_index", "mondai_id", mondaiId);
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO jlpt_audio_clip(mondai_id,question_id,clip_type,audio_url,start_seconds,end_seconds,order_index)
                        VALUES (?,?, 'QUESTION', ?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, mondaiId);
                statement.setLong(2, questionId);
                statement.setString(3, audioUrl);
                statement.setBigDecimal(4, start);
                statement.setBigDecimal(5, end);
                statement.setInt(6, order);
                return statement;
            }, keys);
            clipId = requiredKey(keys);
        } else {
            clipId = existing.getFirst();
            jdbc.update("""
                    UPDATE jlpt_audio_clip SET mondai_id=?,audio_url=?,start_seconds=?,end_seconds=? WHERE id=?
                    """, mondaiId, audioUrl, start, end, clipId);
            jdbc.update("DELETE FROM jlpt_transcript_segment WHERE audio_clip_id=?", clipId);
        }
        String script = blankToNull(request.scriptText());
        String translation = blankToNull(request.scriptTranslation());
        if (script != null || translation != null) {
            jdbc.update("""
                    INSERT INTO jlpt_transcript_segment
                    (mondai_id,audio_clip_id,start_seconds,end_seconds,text_ja,text_ja_furigana,text_vi,order_index)
                    VALUES (?,?,?,?,?,?,?,1)
                    """, mondaiId, clipId, start, end, script, script, translation);
        }
    }

    private void deleteClipTranscripts(long questionId) {
        jdbc.update("""
                DELETE FROM jlpt_transcript_segment
                WHERE audio_clip_id IN (SELECT id FROM jlpt_audio_clip WHERE question_id=?)
                """, questionId);
    }

    private void replaceOptions(long questionId, List<String> choices) {
        jdbc.update("DELETE FROM jlpt_option WHERE question_id=?", questionId);
        for (int index = 0; index < choices.size(); index++) {
            int number = index + 1;
            String text = choices.get(index).trim();
            jdbc.update("""
                    INSERT INTO jlpt_option(question_id,option_number,option_text,option_furigana,option_html,order_index)
                    VALUES (?,?,?,?,?,?)
                    """, questionId, number, text, text, text, number);
        }
    }

    private String transcriptText(long questionId, String column) {
        if (!column.equals("text_ja") && !column.equals("text_vi")) {
            throw new IllegalArgumentException("Unsupported transcript column");
        }
        String sql = "SELECT t." + column + " AS line FROM jlpt_transcript_segment t "
                + "JOIN jlpt_audio_clip c ON c.id=t.audio_clip_id "
                + "WHERE c.question_id=? AND t." + column + " IS NOT NULL ORDER BY t.order_index";
        List<String> lines = jdbc.query(sql, (rs, row) -> rs.getString("line"), questionId);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private Long upsertPassage(Long currentId, long mondaiId, String passage) {
        String value = blankToNull(passage);
        if (value == null) return null;
        long partId = jdbc.queryForObject("SELECT part_id FROM jlpt_mondai WHERE id=?", Long.class, mondaiId);
        if (currentId != null) {
            Long currentPartId = jdbc.queryForObject("SELECT part_id FROM jlpt_passage WHERE id=?", Long.class, currentId);
            Integer references = jdbc.queryForObject("SELECT COUNT(*) FROM jlpt_question WHERE passage_id=?", Integer.class, currentId);
            if (currentPartId != null && currentPartId == partId && references != null) {
                jdbc.update("UPDATE jlpt_passage SET passage_ja=?,passage_html=? WHERE id=?", value, value, currentId);
                return currentId;
            }
        }
        long sourceId = nextLong("jlpt_passage", "source_passage_id", "part_id", partId);
        int order = nextInt("jlpt_passage", "order_index", "part_id", partId);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO jlpt_passage(part_id,source_passage_id,passage_ja,passage_html,order_index)
                    VALUES (?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, partId);
            statement.setLong(2, sourceId);
            statement.setString(3, value);
            statement.setString(4, value);
            statement.setInt(5, order);
            return statement;
        }, keys);
        return requiredKey(keys);
    }

    private void deletePassageIfOrphan(Long oldId, Long retainedId) {
        if (oldId == null || oldId.equals(retainedId)) return;
        jdbc.update("DELETE FROM jlpt_passage WHERE id=? AND NOT EXISTS (SELECT 1 FROM jlpt_question WHERE passage_id=?)",
                oldId, oldId);
    }

    private Long ensureQuestionType(String rawType, long mondaiId) {
        String type = rawType.trim().toUpperCase(Locale.ROOT);
        List<Long> existing = jdbc.query("SELECT id FROM jlpt_question_type WHERE type_code=?",
                (rs, row) -> rs.getLong(1), type);
        if (!existing.isEmpty()) return existing.getFirst();
        String partCode = jdbc.queryForObject("""
                SELECT p.part_code FROM jlpt_mondai m JOIN jlpt_part p ON p.id=m.part_id WHERE m.id=?
                """, String.class, mondaiId);
        Integer nextOrder = jdbc.queryForObject("SELECT COALESCE(MAX(order_index),0)+1 FROM jlpt_question_type",
                Integer.class);
        try {
            jdbc.update("""
                    INSERT INTO jlpt_question_type(type_code,part_code,name_vi,name_ja,order_index)
                    VALUES (?,?,?,NULL,?)
                    """, type, partCode, type.replace('_', ' '), nextOrder);
        } catch (DuplicateKeyException ignored) {
            // Another request created the same reusable type concurrently.
        }
        return jdbc.queryForObject("SELECT id FROM jlpt_question_type WHERE type_code=?", Long.class, type);
    }

    private void createDefaultStructure(long examId) {
        createPart(examId, "vocabulary", "Từ vựng", 30, 1, "KANJI_READING");
        createPart(examId, "grammar_reading", "Ngữ pháp & Đọc hiểu", 70, 2, "GRAMMAR_FORM");
        createPart(examId, "listening", "Nghe hiểu", 40, 3, "LISTENING_TASK_BASED");
    }

    private void createPart(long examId, String code, String title, int duration, int order, String format) {
        jdbc.update("INSERT INTO jlpt_part(exam_id,part_code,title,duration_minutes,order_index) VALUES (?,?,?,?,?)",
                examId, code, title, duration, order);
        long partId = jdbc.queryForObject("SELECT id FROM jlpt_part WHERE exam_id=? AND part_code=?", Long.class,
                examId, code);
        jdbc.update("""
                INSERT INTO jlpt_mondai(part_id,mondai_number,format_code,instruction_html,audio_url,order_index)
                VALUES (?,1,?,NULL,NULL,1)
                """, partId, format);
    }

    private JlptExam apply(JlptExam exam, JlptAdminExamRequest request) {
        exam.setTitle(request.title().trim());
        exam.setJlptLevel(request.level());
        exam.setExamYear(request.year() == null ? null : request.year().shortValue());
        exam.setExamMonth(request.month() == null ? null : request.month().byteValue());
        exam.setStatus(request.status());
        exam.setPassingScore(request.passingScore() == null ? 95 : request.passingScore());
        if (exam.getReviewStatus() == null) exam.setReviewStatus("UNREVIEWED");
        return exam;
    }

    private JlptAdminExamSummaryResponse summary(String code) {
        return jdbc.queryForObject("""
                SELECT e.id,e.exam_code,e.title,e.jlpt_level,e.exam_year,e.exam_month,e.status,
                       e.passing_score,COUNT(q.id) question_count,
                       COALESCE((SELECT SUM(p2.duration_minutes) FROM jlpt_part p2 WHERE p2.exam_id=e.id),0) duration_minutes
                FROM jlpt_exam e LEFT JOIN jlpt_part p ON p.exam_id=e.id
                LEFT JOIN jlpt_mondai m ON m.part_id=p.id LEFT JOIN jlpt_question q ON q.mondai_id=m.id
                WHERE e.exam_code=?
                GROUP BY e.id,e.exam_code,e.title,e.jlpt_level,e.exam_year,e.exam_month,e.status,
                         e.passing_score
                """, (rs, row) -> new JlptAdminExamSummaryResponse(
                rs.getLong("id"), rs.getString("exam_code"), rs.getString("title"),
                rs.getString("jlpt_level"), nullableInt(rs.getObject("exam_year")),
                nullableInt(rs.getObject("exam_month")), rs.getString("status"), rs.getInt("passing_score"),
                rs.getInt("question_count"), rs.getInt("duration_minutes")), code);
    }

    private JlptExam findExam(String code) {
        return examRepository.findByExamCode(normalizeCode(code))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đề JLPT."));
    }

    private void assertMondaiBelongsToExam(long mondaiId, String code) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM jlpt_mondai m JOIN jlpt_part p ON p.id=m.part_id
                JOIN jlpt_exam e ON e.id=p.exam_id WHERE m.id=? AND e.exam_code=?
                """, Integer.class, mondaiId, normalizeCode(code));
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "問題 không thuộc đề đã chọn.");
    }

    private void assertMondaiExists(long mondaiId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM jlpt_mondai WHERE id=?", Integer.class, mondaiId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "問題 không tồn tại.");
    }

    private QuestionLocation questionLocation(long questionId) {
        List<QuestionLocation> rows = jdbc.query("""
                SELECT mondai_id,passage_id,source_question_id,order_index FROM jlpt_question WHERE id=?
                """, (rs, row) -> new QuestionLocation(rs.getLong("mondai_id"),
                rs.getObject("passage_id") == null ? null : rs.getLong("passage_id"),
                rs.getLong("source_question_id"), rs.getInt("order_index")), questionId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy câu hỏi JLPT.");
        return rows.getFirst();
    }

    private long nextLong(String table, String column, String ownerColumn, long ownerId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(" + column + "),0)+1 FROM " + table + " WHERE " + ownerColumn + "=?",
                Long.class, ownerId);
    }

    private int nextInt(String table, String column, String ownerColumn, long ownerId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(" + column + "),0)+1 FROM " + table + " WHERE " + ownerColumn + "=?",
                Integer.class, ownerId);
    }

    private static java.math.BigDecimal defaultScore(JlptAdminQuestionRequest request) {
        return request.score() == null ? java.math.BigDecimal.ONE : request.score();
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static java.math.BigDecimal decimalOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof java.math.BigDecimal decimal) return decimal;
        return new java.math.BigDecimal(value.toString());
    }

    private static long requiredKey(KeyHolder keys) {
        Number key = keys.getKey();
        if (key == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không lấy được ID vừa tạo.");
        return key.longValue();
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value);
    }

    private record QuestionLocation(long mondaiId, Long passageId, long sourceId, int orderIndex) {}
}
