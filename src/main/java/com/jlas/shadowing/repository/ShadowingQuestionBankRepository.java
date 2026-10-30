package com.jlas.shadowing.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.shadowing.model.practice.PreparedQuestion;
import com.jlas.shadowing.model.practice.QuestionEvidencePlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.SQLException;

@Repository
public class ShadowingQuestionBankRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ShadowingQuestionBankRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public boolean queue(long videoId, String levels, String kinds, String targets) {
        int inserted = jdbc.update("INSERT IGNORE INTO shadowing_preparation_job "
                        + "(video_id, levels_csv, kinds_csv, targets_csv, status, progress, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'QUEUED', 0, CURRENT_TIMESTAMP(6))",
                videoId, levels, kinds, targets);
        if (inserted == 1) return true;
        int queued = jdbc.update("UPDATE shadowing_preparation_job SET levels_csv=?, kinds_csv=?, targets_csv=?, "
                        + "status='QUEUED', progress=0, error_message=NULL, updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE video_id=? AND status NOT IN ('QUEUED','RUNNING')", levels, kinds, targets, videoId);
        return queued == 1;
    }

    public void clearGenerationIssues(long videoId) {
        jdbc.update("DELETE FROM shadowing_question_generation_issue WHERE video_id=?", videoId);
    }

    public void recordGenerationIssue(long videoId, String kind, String level, String prompt,
                                      String answer, List<String> choices, String reason,
                                      Double startTime, int attempt) {
        recordGenerationIssue(videoId, kind, level, prompt, answer, choices, reason, startTime,
                attempt, List.of(), List.of(), List.of());
    }

    public void recordGenerationIssue(long videoId, String kind, String level, String prompt,
                                      String answer, List<String> choices, String reason,
                                      Double startTime, int attempt, List<Long> sourceIds,
                                      List<Long> suppliedIds, List<java.util.Map<String, Object>> evidence) {
        try {
            jdbc.update("INSERT INTO shadowing_question_generation_issue "
                            + "(video_id, kind, level_code, prompt_text, answer_text, choices_json, "
                            + "reason, start_time, attempt_number, source_ids_json, supplied_ids_json, evidence_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    videoId, kind, level, prompt, answer, mapper.writeValueAsString(choices),
                    reason, startTime, attempt, mapper.writeValueAsString(sourceIds),
                    mapper.writeValueAsString(suppliedIds), mapper.writeValueAsString(evidence));
        } catch (Exception exception) {
            throw new IllegalStateException("Không lưu được lý do loại câu hỏi", exception);
        }
    }

    public List<GenerationIssue> generationIssues(long videoId) {
        return jdbc.query("SELECT id, kind, level_code, prompt_text, answer_text, choices_json, reason, "
                        + "start_time, attempt_number, source_ids_json, supplied_ids_json, evidence_json "
                        + "FROM shadowing_question_generation_issue "
                        + "WHERE video_id=? ORDER BY id DESC LIMIT 40",
                (rs, row) -> {
                    try {
                        return new GenerationIssue(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5),
                                rs.getString(6) == null ? List.of()
                                        : mapper.readValue(rs.getString(6), new TypeReference<List<String>>() {}),
                                rs.getString(7), rs.getObject(8) == null ? null : rs.getDouble(8),
                                rs.getInt(9), readJson(rs.getString(10), new TypeReference<List<Long>>() {}),
                                readJson(rs.getString(11), new TypeReference<List<Long>>() {}),
                                readJson(rs.getString(12), new TypeReference<List<java.util.Map<String, Object>>>() {}));
                    } catch (Exception exception) {
                        throw new IllegalStateException("Không đọc được lý do loại câu hỏi", exception);
                    }
                }, videoId);
    }

    public record GenerationIssue(long id, String kind, String level, String prompt,
                                  String answer, List<String> choices, String reason,
                                  Double startTime, int attempt, List<Long> sourceIds,
                                  List<Long> suppliedIds, List<java.util.Map<String, Object>> evidence) {
    }

    public PreparationJob findJob(long videoId) {
        return jdbc.query("SELECT video_id, levels_csv, kinds_csv, status, progress, error_message, "
                        + "suggestions_csv, suggestion_hash, suggested_jlpt_level, selected_jlpt_level, "
                        + "targets_csv, capacities_csv, analysis_version, evidence_plans_json "
                        + "FROM shadowing_preparation_job WHERE video_id = ?",
                rs -> rs.next() ? new PreparationJob(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                        rs.getString(13), rs.getString(14))
                        : null, videoId);
    }

    public void saveSuggestions(long videoId, String csv, String hash, String suggestedLevel,
                                String capacities, String analysisVersion, List<QuestionEvidencePlan> plans) {
        jdbc.update("INSERT INTO shadowing_preparation_job (video_id, levels_csv, kinds_csv, status, progress, "
                        + "suggestions_csv, suggestion_hash, suggested_jlpt_level, selected_jlpt_level, "
                        + "capacities_csv, analysis_version, evidence_plans_json, updated_at) "
                        + "VALUES (?, '', '', 'IDLE', 0, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6)) "
                        + "ON DUPLICATE KEY UPDATE suggestions_csv=VALUES(suggestions_csv), "
                        + "suggestion_hash=VALUES(suggestion_hash), "
                        + "suggested_jlpt_level=VALUES(suggested_jlpt_level), "
                        + "capacities_csv=VALUES(capacities_csv), "
                        + "analysis_version=VALUES(analysis_version), evidence_plans_json=VALUES(evidence_plans_json), "
                        + "selected_jlpt_level=COALESCE(selected_jlpt_level, VALUES(selected_jlpt_level))",
                videoId, csv, hash, suggestedLevel, suggestedLevel, capacities, analysisVersion, writeJson(plans));
    }

    public List<QuestionEvidencePlan> evidencePlans(PreparationJob job) {
        return job == null ? List.of() : readJson(job.evidencePlansJson(), new TypeReference<List<QuestionEvidencePlan>>() {});
    }

    private <T> List<T> readJson(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, type); }
        catch (Exception exception) { throw new IllegalStateException("Không đọc được dẫn chứng đã lưu", exception); }
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Không lưu được dẫn chứng", exception); }
    }

    /**
     * The video's JLPT level from question preparation as {selected, suggested}, or null without a job.
     * selected starts as the AI suggestion and only differs once the owner picks another level.
     */
    public String[] jlptLevels(long videoId) {
        return jdbc.query("SELECT selected_jlpt_level, suggested_jlpt_level FROM shadowing_preparation_job "
                        + "WHERE video_id = ?",
                rs -> rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null, videoId);
    }

    public void selectVideoLevel(long videoId, String level) {
        jdbc.update("INSERT INTO shadowing_preparation_job "
                        + "(video_id, levels_csv, kinds_csv, status, progress, selected_jlpt_level, updated_at) "
                        + "VALUES (?, '', '', 'IDLE', 0, ?, CURRENT_TIMESTAMP(6)) "
                        + "ON DUPLICATE KEY UPDATE selected_jlpt_level=VALUES(selected_jlpt_level)",
                videoId, level);
    }

    public void recoverExpired() {
        jdbc.update("UPDATE shadowing_preparation_job SET status='QUEUED', claim_token=NULL, "
                + "error_message='Tác vụ được tiếp tục sau gián đoạn' "
                + "WHERE status='RUNNING' AND lease_until < CURRENT_TIMESTAMP(6)");
    }

    public PreparationJob claim() {
        String token = UUID.randomUUID().toString();
        int updated = jdbc.update("UPDATE shadowing_preparation_job SET status='RUNNING', claim_token=?, "
                        + "lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 15 MINUTE), "
                        + "updated_at=CURRENT_TIMESTAMP(6) WHERE status='QUEUED' "
                        + "ORDER BY updated_at LIMIT 1", token);
        if (updated == 0) return null;
        return jdbc.query("SELECT video_id, levels_csv, kinds_csv, status, progress, error_message, "
                        + "suggestions_csv, suggestion_hash, suggested_jlpt_level, selected_jlpt_level, "
                        + "targets_csv, capacities_csv, analysis_version, evidence_plans_json "
                        + "FROM shadowing_preparation_job WHERE claim_token = ?",
                rs -> rs.next() ? new PreparationJob(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                        rs.getString(13), rs.getString(14))
                        : null, token);
    }

    public void progress(long videoId, int percent) {
        jdbc.update("UPDATE shadowing_preparation_job SET progress=?, lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), "
                + "INTERVAL 15 MINUTE), updated_at=CURRENT_TIMESTAMP(6) WHERE video_id=? AND status='RUNNING'",
                percent, videoId);
    }

    public void finish(long videoId, String status, String message) {
        jdbc.update("UPDATE shadowing_preparation_job SET status=?, progress=100, error_message=?, "
                + "claim_token=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE video_id=?",
                status, message, videoId);
    }

    public List<PreparedQuestion> findByVideo(long videoId) {
        return findByVideo(videoId, false);
    }

    public List<PreparedQuestion> findByVideo(long videoId, boolean includeDraft) {
        return jdbc.query("SELECT id, video_id, subtitle_id, source_ids_json, kind, level_code, prompt_text, "
                        + "answer_text, choices_json, explanation, start_time, end_time, source_hash, "
                        + "occurrence_index, content_hash, status FROM shadowing_question_bank "
                + "WHERE video_id=? AND status IN (" + (includeDraft ? "'READY','DRAFT'" : "'READY'")
                + ") ORDER BY CASE WHEN status='READY' THEN 0 ELSE 1 END, id DESC",
                (rs, row) -> mapQuestion(rs), videoId);
    }

    public int approve(long videoId, List<Long> questionIds) {
        if (questionIds.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(questionIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(videoId);
        params.addAll(questionIds);
        return jdbc.update("UPDATE shadowing_question_bank SET status='READY' "
                + "WHERE video_id=? AND status='DRAFT' AND id IN (" + placeholders + ")",
                params.toArray());
    }

    public int rejectQuestions(long videoId, List<Long> questionIds) {
        if (questionIds.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(questionIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(videoId);
        params.addAll(questionIds);
        return jdbc.update("UPDATE shadowing_question_bank SET status='REJECTED' "
                + "WHERE video_id=? AND status IN ('DRAFT','READY') AND id IN (" + placeholders + ")",
                params.toArray());
    }

    public PreparedQuestion findById(long id) {
        return jdbc.query("SELECT id, video_id, subtitle_id, source_ids_json, kind, level_code, prompt_text, "
                        + "answer_text, choices_json, explanation, start_time, end_time, source_hash, "
                        + "occurrence_index, content_hash, status FROM shadowing_question_bank WHERE id=?",
                rs -> rs.next() ? mapQuestion(rs) : null, id);
    }

    private PreparedQuestion mapQuestion(ResultSet rs) throws SQLException {
        try {
            return new PreparedQuestion(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                    mapper.readValue(rs.getString(4), new TypeReference<List<Long>>() {}),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    mapper.readValue(rs.getString(9), new TypeReference<List<String>>() {}),
                    rs.getString(10), rs.getDouble(11), rs.getDouble(12), rs.getString(13),
                    rs.getInt(14), rs.getString(15), rs.getString(16));
        } catch (Exception exception) {
            throw new IllegalStateException("Dữ liệu câu hỏi đã lưu không hợp lệ", exception);
        }
    }

    public boolean save(PreparedQuestion question) {
        try {
            return jdbc.update("INSERT INTO shadowing_question_bank (video_id, subtitle_id, source_ids_json, kind, "
                            + "level_code, prompt_text, answer_text, choices_json, explanation, start_time, "
                            + "end_time, source_hash, occurrence_index, content_hash, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT') "
                            + "ON DUPLICATE KEY UPDATE updated_at=CURRENT_TIMESTAMP(6)",
                    question.videoId(), question.subtitleId(), mapper.writeValueAsString(question.sourceIds()),
                    question.kind(), question.level(), question.prompt(), question.answer(),
                    mapper.writeValueAsString(question.choices()), question.explanation(),
                    question.startTime(), question.endTime(), question.sourceHash(), question.occurrence(),
                    question.contentHash()) == 1;
        } catch (Exception exception) {
            throw new IllegalStateException("Không lưu được câu hỏi đã chuẩn bị", exception);
        }
    }

    /** Serialize owner submissions so different prompts cannot bypass the same-evidence duplicate rule. */
    public void lockVideo(long videoId) {
        jdbc.queryForObject("SELECT id FROM video WHERE id=? FOR UPDATE", Long.class, videoId);
    }

    public PreparedQuestion findByContentHash(long videoId, String contentHash) {
        Long id = jdbc.query("SELECT id FROM shadowing_question_bank WHERE video_id=? AND content_hash=?",
                rs -> rs.next() ? rs.getLong(1) : null, videoId, contentHash);
        return id == null ? null : findById(id);
    }

    public void reject(long videoId, long questionId) {
        jdbc.update("UPDATE shadowing_question_bank SET status='REJECTED' WHERE id=? AND video_id=?",
                questionId, videoId);
    }

    public boolean claimContribution(long videoId, long userId) {
        return jdbc.update("INSERT IGNORE INTO shadowing_question_contribution "
                + "(video_id, user_id, created_on) VALUES (?, ?, CURRENT_DATE())", videoId, userId) == 1;
    }

    public boolean contributedToday(long videoId, long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM shadowing_question_contribution "
                + "WHERE video_id=? AND user_id=? AND created_on=CURRENT_DATE()",
                Integer.class, videoId, userId);
        return count != null && count > 0;
    }

    public List<RejectedQuestion> rejected(long videoId) {
        return jdbc.query("SELECT subtitle_id, kind, level_code, prompt_text, answer_text "
                        + "FROM shadowing_question_bank WHERE video_id=? AND status='REJECTED'",
                (rs, row) -> new RejectedQuestion(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)), videoId);
    }

    public record RejectedQuestion(long subtitleId, String kind, String level,
                                   String prompt, String answer) {
    }

    public record PreparationJob(long videoId, String levelsCsv, String kindsCsv, String status,
                                 int progress, String errorMessage, String suggestionsCsv,
                                 String suggestionHash, String suggestedJlptLevel,
                                 String selectedJlptLevel, String targetsCsv, String capacitiesCsv,
                                 String analysisVersion, String evidencePlansJson) {
        public List<String> levels() { return split(levelsCsv); }
        public List<String> kinds() { return split(kindsCsv); }
        public java.util.Map<String, Integer> targets() { return numberMap(targetsCsv); }
        public java.util.Map<String, Integer> capacities() { return numberMap(capacitiesCsv); }
        public static java.util.Map<String, Integer> numberMap(String csv) {
            java.util.Map<String, Integer> values = new java.util.LinkedHashMap<>();
            if (csv == null || csv.isBlank()) return values;
            for (String part : csv.split(",")) {
                String[] pair = part.split(":", 2);
                if (pair.length != 2) continue;
                try { values.put(pair[0], Integer.parseInt(pair[1])); }
                catch (NumberFormatException ignored) { }
            }
            return values;
        }
        public static String serialize(java.util.Map<String, Integer> values) {
            return values.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(","));
        }
        private static List<String> split(String csv) {
            if (csv == null || csv.isBlank()) return List.of();
            List<String> values = new ArrayList<>();
            for (String value : csv.split(",")) if (!value.isBlank()) values.add(value.trim());
            return values;
        }
    }
}
