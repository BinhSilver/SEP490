package com.jlas.assessment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.dto.JlptExamResponse;
import com.jlas.assessment.dto.JlptMockResultResponse;
import com.jlas.assessment.dto.JlptMockAttemptReviewResponse;
import com.jlas.assessment.dto.JlptMockSubmissionRequest;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-owned Mock Test grading and persistence for the bundled JLPT exam UI. */
@Service
public class JlptMockAttemptService {
    private static final Map<String, Double> TOTAL_PASS = Map.of("N1", .556, "N2", .5, "N3", .528, "N4", .5, "N5", .444);
    private static final double SECTION_PASS = .317;
    private final JlptService exams;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JlptMockAttemptService(JlptService exams, JdbcTemplate jdbc, ObjectMapper json) {
        this.exams = exams; this.jdbc = jdbc; this.json = json;
    }

    @Transactional
    public JlptMockResultResponse submit(Long userId, JlptMockSubmissionRequest request) {
        JlptExamResponse exam = exams.getExam(request.examCode());
        Map<String, JlptMockResultResponse.SectionResult> sections = new LinkedHashMap<>();
        int total = 0, correct = 0;
        for (JlptExamResponse.Part part : exam.parts()) {
            int partTotal = 0, partCorrect = 0;
            for (JlptExamResponse.Mondai mondai : part.mondai()) for (JlptExamResponse.Question question : mondai.questions()) {
                if (question.correctOption() == null) continue;
                partTotal++;
                if (question.correctOption().equals(request.answers().get(question.id()))) partCorrect++;
            }
            total += partTotal; correct += partCorrect;
            int percent = percent(partCorrect, partTotal);
            sections.put(part.code(), new JlptMockResultResponse.SectionResult(partCorrect, partTotal, percent,
                    partTotal > 0 && ((double) partCorrect / partTotal) >= SECTION_PASS));
        }
        if (total == 0) throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Đề JLPT chưa có câu hỏi có thể chấm.");
        int score = percent(correct, total);
        boolean passed = ((double) correct / total) >= TOTAL_PASS.getOrDefault(exam.level(), .5)
                && sections.values().stream().allMatch(JlptMockResultResponse.SectionResult::passed);
        LocalDateTime now = LocalDateTime.now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO assessment_attempt(user_id,attempt_type,status,started_at,submitted_at,score_percent) VALUES(?,'JLPT','SUBMITTED',?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId); ps.setTimestamp(2, Timestamp.valueOf(now)); ps.setTimestamp(3, Timestamp.valueOf(now)); ps.setInt(4, score);
            return ps;
        }, key);
        long attemptId = key.getKey().longValue();
        try {
            jdbc.update("INSERT INTO jlpt_mock_attempt(attempt_id,exam_code,jlpt_level,policy_version,correct_count,question_count,score_percent,passed,section_results_json,answers_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    attemptId, exam.code(), exam.level(), "JLPT_SELF_ASSESSMENT_V1", correct, total, score, passed,
                    json.writeValueAsString(sections), json.writeValueAsString(request.answers()));
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không thể lưu kết quả thi thử.");
        }
        return new JlptMockResultResponse(attemptId, exam.code(), exam.level(), correct, total, score, passed, sections, now);
    }

    @Transactional(readOnly = true)
    public JlptMockResultResponse latest(Long userId, String level) {
        return jdbc.query("""
                SELECT a.id,m.exam_code,m.jlpt_level,m.correct_count,m.question_count,m.score_percent,m.passed,
                       m.section_results_json,a.submitted_at FROM assessment_attempt a
                JOIN jlpt_mock_attempt m ON m.attempt_id=a.id
                WHERE a.user_id=? AND m.jlpt_level=? AND a.status='SUBMITTED' ORDER BY a.id DESC LIMIT 1
                """, rs -> rs.next() ? read(rs) : null, userId, level);
    }

    /** Latest submitted attempt is the qualification evidence for a target-level path certificate. */
    @Transactional(readOnly = true)
    public boolean hasPassingAttempt(Long userId, String level) {
        JlptMockResultResponse result = latest(userId, level);
        return result != null && result.passed();
    }

    @Transactional(readOnly = true)
    public JlptMockAttemptReviewResponse review(Long userId, Long attemptId) {
        var stored = jdbc.query("""
                SELECT a.id,m.exam_code,m.jlpt_level,m.correct_count,m.question_count,m.score_percent,m.passed,
                       m.section_results_json,m.answers_json,a.submitted_at
                FROM assessment_attempt a JOIN jlpt_mock_attempt m ON m.attempt_id=a.id
                WHERE a.user_id=? AND a.id=? AND a.status='SUBMITTED'
                """, rs -> {
            if (!rs.next()) return null;
            return new StoredReview(read(rs), rs.getString("answers_json"));
        }, userId, attemptId);
        if (stored == null) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy lượt thi thử đã lưu.");
        try {
            Map<Long, Integer> answers = json.readValue(stored.answersJson(), new TypeReference<HashMap<Long, Integer>>() { });
            return new JlptMockAttemptReviewResponse(stored.result(), exams.getExam(stored.result().examCode()), answers);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không thể đọc lại đáp án của lượt thi.");
        }
    }

    private record StoredReview(JlptMockResultResponse result, String answersJson) { }

    private JlptMockResultResponse read(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            var type = json.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, JlptMockResultResponse.SectionResult.class);
            Map<String, JlptMockResultResponse.SectionResult> sections = json.readValue(rs.getString("section_results_json"), type);
            return new JlptMockResultResponse(rs.getLong("id"), rs.getString("exam_code"), rs.getString("jlpt_level"),
                    rs.getInt("correct_count"), rs.getInt("question_count"), rs.getInt("score_percent"), rs.getBoolean("passed"),
                    sections, rs.getTimestamp("submitted_at").toLocalDateTime());
        } catch (JsonProcessingException ex) { throw new java.sql.SQLException(ex); }
    }
    private static int percent(int correct, int total) { return total == 0 ? 0 : Math.round(correct * 100f / total); }
}
