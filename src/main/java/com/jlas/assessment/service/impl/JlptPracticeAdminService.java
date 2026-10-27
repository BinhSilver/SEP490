package com.jlas.assessment.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JlptPracticeAdminService {
    private static final Pattern LEVEL = Pattern.compile("N[1-5]");
    private static final Pattern TYPE = Pattern.compile("[A-Z0-9_]{2,50}");
    private final JdbcTemplate jdbc;

    public record PracticeType(String level, String section, String code, String nameVi, String nameJa,
                               String defaultNameVi, String defaultNameJa,
                               int questionCount, boolean enabled, Integer orderIndex) {}
    public record PracticeTypeUpdate(String level, String code, boolean enabled, String nameVi,
                                     String nameJa, Integer orderIndex) {}
    public record PracticeChoice(int number, String text, String furigana, String html) {}
    public record PracticeQuestion(long id, String examCode, String examTitle, int mondaiNumber,
                                   int questionNumber, String instruction, String passage, String passageHtml,
                                   String passageVi, String prompt, String furigana, String html,
                                   Integer correctOption, String imageUrl, String audioUrl,
                                   List<PracticeChoice> options) {}
    public record PracticeQuestionPage(List<PracticeQuestion> questions, int page, int pageSize,
                                       long totalQuestions, int totalPages) {}

    public List<PracticeType> list() {
        return jdbc.query("""
                SELECT inventory.level_code, inventory.type_code, inventory.question_count,
                       config.is_enabled, config.name_vi, config.name_ja, config.order_index
                FROM (
                    SELECT e.jlpt_level AS level_code,
                           COALESCE(qt.type_code, NULLIF(UPPER(m.format_code), ''),
                                    CONCAT(UPPER(p.part_code), '_OTHER')) AS type_code,
                           COUNT(q.id) AS question_count
                    FROM jlpt_question q
                    JOIN jlpt_mondai m ON m.id=q.mondai_id
                    JOIN jlpt_part p ON p.id=m.part_id
                    JOIN jlpt_exam e ON e.id=p.exam_id
                    LEFT JOIN jlpt_question_type qt ON qt.id=q.question_type_id
                    WHERE e.status IN ('DRAFT','PUBLISHED')
                      AND NOT (p.part_code = 'listening' AND
                          REPLACE(REPLACE(TRIM(q.question_text),' ',''),'　','') IN
                          ('れい','例','(れい)','（れい）','(例)','（例）','(例(れい))','（例（れい））'))
                    GROUP BY e.jlpt_level,
                             COALESCE(qt.type_code, NULLIF(UPPER(m.format_code), ''),
                                      CONCAT(UPPER(p.part_code), '_OTHER'))
                ) inventory
                LEFT JOIN jlpt_practice_type_config config
                  ON config.level_code=inventory.level_code AND config.type_code=inventory.type_code
                ORDER BY FIELD(inventory.level_code,'N5','N4','N3','N2','N1'),
                         CASE WHEN config.order_index IS NULL THEN 1 ELSE 0 END,
                         config.order_index, inventory.type_code
                """, (rs, row) -> {
            String code = rs.getString("type_code");
            String vi = rs.getString("name_vi");
            String ja = rs.getString("name_ja");
            boolean enabled = rs.getBoolean("is_enabled");
            if (rs.wasNull()) enabled = true;
            return new PracticeType(rs.getString("level_code"), JlptServiceImpl.sectionForType(code), code,
                    vi, ja, JlptServiceImpl.typeName(code), JlptServiceImpl.typeNameJa(code),
                    rs.getInt("question_count"), enabled,
                    rs.getObject("order_index", Integer.class));
        });
    }

    public PracticeQuestionPage questions(String level, String code, int requestedPage, int requestedSize) {
        if (level == null || !LEVEL.matcher(level).matches() || code == null || !TYPE.matcher(code).matches()
                || requestedPage < 0 || requestedSize < 1 || requestedSize > 25) {
            throw new IllegalArgumentException("Bộ lọc câu hỏi không hợp lệ.");
        }
        String typeExpression = "COALESCE(qt.type_code, NULLIF(UPPER(m.format_code), ''), CONCAT(UPPER(p.part_code), '_OTHER'))";
        String filter = "e.jlpt_level=? AND e.status IN ('DRAFT','PUBLISHED') AND " + typeExpression + "=?"
                + " AND NOT (p.part_code='listening' AND REPLACE(REPLACE(TRIM(q.question_text),' ',''),'　','')"
                + " IN ('れい','例','(れい)','（れい）','(例)','（例）','(例(れい))','（例（れい））'))";
        Long total = jdbc.queryForObject("""
                SELECT COUNT(q.id)
                FROM jlpt_question q
                JOIN jlpt_mondai m ON m.id=q.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id
                JOIN jlpt_exam e ON e.id=p.exam_id
                LEFT JOIN jlpt_question_type qt ON qt.id=q.question_type_id
                WHERE """ + filter, Long.class, level, code);
        long count = total == null ? 0 : total;
        int pageSize = requestedSize;
        int totalPages = (int) Math.ceil(count / (double) pageSize);
        int page = totalPages == 0 ? 0 : Math.min(requestedPage, totalPages - 1);
        List<Map<String, Object>> resultRows = jdbc.queryForList("""
                SELECT q.id,q.question_number,q.question_text,q.question_furigana,q.question_html,
                       q.correct_option,q.image_url,e.exam_code,e.title AS exam_title,m.mondai_number,
                       m.instruction_html,ps.passage_ja,ps.passage_html,ps.passage_vi,ac.audio_url
                FROM jlpt_question q
                JOIN jlpt_mondai m ON m.id=q.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id
                JOIN jlpt_exam e ON e.id=p.exam_id
                LEFT JOIN jlpt_question_type qt ON qt.id=q.question_type_id
                LEFT JOIN jlpt_audio_clip ac ON ac.question_id=q.id
                LEFT JOIN jlpt_passage ps ON ps.id=q.passage_id
                WHERE """ + filter + " ORDER BY e.exam_year DESC,e.exam_month DESC,e.exam_code,p.order_index,"
                        + "m.mondai_number,q.question_number LIMIT ? OFFSET ?",
                level, code, pageSize, (long) page * pageSize);
        if (resultRows.isEmpty()) return new PracticeQuestionPage(List.of(), page, pageSize, count, totalPages);

        List<Long> ids = resultRows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, List<PracticeChoice>> choicesByQuestion = new LinkedHashMap<>();
        jdbc.query("SELECT question_id,option_number,option_text,option_furigana,option_html FROM jlpt_option WHERE question_id IN ("
                + placeholders + ") ORDER BY question_id,order_index", ids.toArray(), rs -> {
            long questionId = rs.getLong("question_id");
            choicesByQuestion.computeIfAbsent(questionId, ignored -> new ArrayList<>()).add(
                    new PracticeChoice(rs.getInt("option_number"), rs.getString("option_text"),
                            rs.getString("option_furigana"), rs.getString("option_html")));
        });
        List<PracticeQuestion> questions = resultRows.stream().map(row -> {
            long id = ((Number) row.get("id")).longValue();
            Object correct = row.get("correct_option");
            return new PracticeQuestion(id, string(row.get("exam_code")), string(row.get("exam_title")),
                    number(row.get("mondai_number")), number(row.get("question_number")),
                    string(row.get("instruction_html")), string(row.get("passage_ja")),
                    string(row.get("passage_html")), string(row.get("passage_vi")),
                    string(row.get("question_text")), string(row.get("question_furigana")),
                    string(row.get("question_html")), correct == null ? null : ((Number) correct).intValue(),
                    string(row.get("image_url")), string(row.get("audio_url")),
                    choicesByQuestion.getOrDefault(id, List.of()));
        }).toList();
        return new PracticeQuestionPage(questions, page, pageSize, count, totalPages);
    }

    @Transactional
    public void save(List<PracticeTypeUpdate> updates, Long updatedBy) {
        if (updates == null || updates.size() > 500) throw new IllegalArgumentException("Danh sách dạng luyện không hợp lệ.");
        for (var update : updates) {
            validate(update);
            jdbc.update("""
                    INSERT INTO jlpt_practice_type_config
                        (level_code,type_code,is_enabled,name_vi,name_ja,order_index,updated_at,updated_by)
                    VALUES (?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE is_enabled=VALUES(is_enabled),name_vi=VALUES(name_vi),
                        name_ja=VALUES(name_ja),order_index=VALUES(order_index),
                        updated_at=VALUES(updated_at),updated_by=VALUES(updated_by)
                    """, update.level(), update.code(), update.enabled(), nullable(update.nameVi()),
                    nullable(update.nameJa()), update.orderIndex(), java.sql.Timestamp.from(Instant.now()), updatedBy);
        }
    }

    private static void validate(PracticeTypeUpdate update) {
        if (update == null || update.level() == null || !LEVEL.matcher(update.level()).matches()
                || update.code() == null || !TYPE.matcher(update.code()).matches()
                || (update.nameVi() != null && update.nameVi().length() > 120)
                || (update.nameJa() != null && update.nameJa().length() > 120)
                || (update.orderIndex() != null && (update.orderIndex() < 1 || update.orderIndex() > 999)))
            throw new IllegalArgumentException("Thông tin dạng luyện không hợp lệ.");
    }

    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String string(Object value) { return value == null ? null : value.toString(); }
    private static int number(Object value) { return value == null ? 0 : ((Number) value).intValue(); }
}
