package com.jlas.learning.pace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSettingsUpdateRequest;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathSettingsService;
import com.jlas.mypaths.MyPathsService;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Weekly pace advice for JLPT and communication paths. */
@Service
public class LearningPaceService {
    private final JdbcTemplate db;
    private final JpathClock clock;
    private final JpathSettingsService jlptSettings;
    private final MyPathsService myPaths;
    private final ObjectMapper json;

    public LearningPaceService(JdbcTemplate db, JpathClock clock, JpathSettingsService jlptSettings,
            MyPathsService myPaths, ObjectMapper json) {
        this.db = db;
        this.clock = clock;
        this.jlptSettings = jlptSettings;
        this.myPaths = myPaths;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> suggestion(Long userId, String key) {
        Path path = path(userId, key);
        LocalDate today = clock.today(userId);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        boolean complete = complete(path, monday, today);
        if (path.kind.equals("JLPT")) recordFirstJlptScore(userId, path, monday, today);
        List<Map<String, Object>> weeks = db.queryForList("SELECT first_score, decision FROM learning_pace_week "
                + "WHERE path_kind = ? AND path_id = ? AND week_start = ?", path.kind, path.id, monday);
        Integer first = weeks.isEmpty() || weeks.get(0).get("first_score") == null ? null
                : ((Number) weeks.get(0).get("first_score")).intValue();
        String decision = weeks.isEmpty() ? null : (String) weeks.get(0).get("decision");
        LearningPaceRule.Direction direction = first == null ? LearningPaceRule.Direction.KEEP
                : LearningPaceRule.weekly(first, complete);
        int proposed = path.kind.equals("JLPT") ? LearningPaceRule.proposedJlptWords(path.current, direction)
                : LearningPaceRule.proposedCommSituations(path.current, direction);
        boolean available = complete && first != null && decision == null && proposed != path.current;
        int proposedMinutes = path.kind.equals("COMM") ? proposed * 15 :
                jlptSettings.preview(userId, JpathSettingsUpdateRequest.pace(proposed)).estimatedMinutes();
        Map<String, Object> overview = myPaths.overview(userId);
        int currentTotal = ((Number) overview.get("totalMinutes")).intValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> activePaths = (List<Map<String, Object>>) overview.get("paths");
        int currentMinutes = activePaths.stream().filter(p -> key.equals(p.get("key")))
                .map(p -> ((Number) p.get("minutesPerDay")).intValue()).findFirst().orElse(path.minutes);
        int total = currentTotal - currentMinutes + proposedMinutes;
        Integer free = (Integer) overview.get("freeMinutes");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("weekStart", monday.toString());
        result.put("weekComplete", complete);
        result.put("firstScore", first);
        result.put("direction", direction.name());
        result.put("current", path.current);
        result.put("proposed", proposed);
        result.put("available", available);
        result.put("decision", decision);
        result.put("minutesPerDay", proposedMinutes);
        result.put("totalMinutes", total);
        result.put("freeMinutes", free);
        result.put("overFreeTime", free != null && total > free);
        result.put("focus", first != null && first < 90 ? focus(path, userId, monday) : null);
        return result;
    }

    private String focus(Path path, Long userId, LocalDate monday) {
        if (path.kind.equals("JLPT")) {
            List<Map<String, Object>> checks = db.queryForList("SELECT tab, issue_json FROM jpath_check_set WHERE user_id = ? "
                    + "AND kind = 'FREE_QUIZ' AND status = 'SUBMITTED' AND submitted_at >= ? "
                    + "AND submitted_at < ? ORDER BY submitted_at, id", userId,
                    monday.atStartOfDay().isAfter(path.created) ? monday.atStartOfDay() : path.created,
                    monday.plusDays(7).atStartOfDay());
            String tab = checks.stream().filter(c -> weeklyIssue(c.get("issue_json")))
                    .map(c -> String.valueOf(c.get("tab"))).findFirst().orElse("");
            String name = switch (tab) {
                case "VOCAB" -> "Từ vựng";
                case "KANJI" -> "Kanji";
                case "GRAMMAR" -> "Ngữ pháp";
                default -> "bài kiểm tra tuần";
            };
            return "Ưu tiên ôn phần " + name;
        }
        List<String> topics = db.queryForList("SELECT t.name_vi FROM cpath_user_week_miss m "
                + "JOIN cpath_item i ON i.code = m.item_code JOIN cpath_topic t ON t.code = i.topic_code "
                + "WHERE m.path_id = ? AND m.due_on >= ? AND m.due_on < ? ORDER BY m.due_on LIMIT 1",
                String.class, path.id, monday.plusDays(7), monday.plusDays(14));
        return topics.isEmpty() ? "Ưu tiên ôn câu sai của kiểm tra tuần" : "Ưu tiên ôn chủ đề " + topics.get(0);
    }

    @Transactional
    public Map<String, Object> decide(Long userId, String key, boolean apply) {
        Map<String, Object> advice = suggestion(userId, key);
        if (!Boolean.TRUE.equals(advice.get("available")))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tuần này chưa có gợi ý đổi nhịp để xử lý.");
        Path path = path(userId, key);
        LocalDate monday = LocalDate.parse((String) advice.get("weekStart"));
        int updated = db.update("UPDATE learning_pace_week SET decision = ?, decided_at = CURRENT_TIMESTAMP(6) "
                + "WHERE path_kind = ? AND path_id = ? AND week_start = ? AND decision IS NULL",
                apply ? "APPLIED" : "KEPT", path.kind, path.id, monday);
        if (updated != 1) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gợi ý này đã được xử lý.");
        if (apply) {
            int proposed = ((Number) advice.get("proposed")).intValue();
            if (path.kind.equals("JLPT")) {
                jlptSettings.update(userId, JpathSettingsUpdateRequest.pace(proposed));
            } else {
                db.update("UPDATE cpath_user_path SET daily_situations = ? WHERE id = ? AND user_id = ? AND status = 'ACTIVE'",
                        proposed, path.id, userId);
            }
        }
        return suggestion(userId, key);
    }

    @Transactional
    public void manualChange(Long userId, String key) {
        Path path = path(userId, key);
        LocalDate monday = clock.today(userId).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        db.update("INSERT INTO learning_pace_week (path_kind, path_id, week_start, decision, decided_at) "
                + "VALUES (?,?,?,'MANUAL',CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE "
                + "decision = 'MANUAL', decided_at = CURRENT_TIMESTAMP(6)", path.kind, path.id, monday);
    }

    private void recordFirstJlptScore(Long userId, Path path, LocalDate monday, LocalDate today) {
        List<Map<String, Object>> checks = db.queryForList("SELECT score_pct, issue_json FROM jpath_check_set "
                + "WHERE user_id = ? AND kind = 'FREE_QUIZ' AND status = 'SUBMITTED' AND submitted_at >= ? "
                + "AND submitted_at < ? ORDER BY submitted_at, id", userId,
                monday.atStartOfDay().isAfter(path.created) ? monday.atStartOfDay() : path.created,
                today.plusDays(1).atStartOfDay());
        for (Map<String, Object> check : checks) {
            if (!weeklyIssue(check.get("issue_json"))) continue;
            int score = ((Number) check.get("score_pct")).intValue();
            db.update("INSERT INTO learning_pace_week (path_kind, path_id, week_start, first_score) "
                    + "VALUES ('JLPT',?,?,?) ON DUPLICATE KEY UPDATE first_score = COALESCE(first_score, VALUES(first_score))",
                    path.id, monday, score);
            return;
        }
    }

    private boolean weeklyIssue(Object raw) {
        if (raw == null) return false;
        try {
            JsonNode node = json.readTree(String.valueOf(raw));
            return "WEEK".equals(node.path("source").asText()) && !node.hasNonNull("redoOf")
                    && node.path("count").asInt() >= 10;
        } catch (Exception ignored) { return false; }
    }

    private boolean complete(Path path, LocalDate monday, LocalDate today) {
        if (today.isBefore(monday)) return false;
        LocalDate start = path.created.toLocalDate().isAfter(monday) ? path.created.toLocalDate() : monday;
        if (path.kind.equals("COMM")) {
            int scheduled = 0;
            for (LocalDate day = start; !day.isAfter(monday.plusDays(6)); day = day.plusDays(1)) {
                if (!path.restDays.contains(day.getDayOfWeek().name().substring(0, 3))) scheduled++;
            }
            List<Map<String, Object>> rows = db.queryForList("SELECT done_at FROM cpath_user_situation "
                    + "WHERE path_id = ? AND study_day >= ? AND study_day < ?", path.id, start, monday.plusDays(7));
            if (rows.size() >= scheduled * path.current && rows.stream().allMatch(row -> row.get("done_at") != null))
                return true; // This week's full share can be learned ahead of the last scheduled day.
        }
        for (LocalDate day = start; !day.isAfter(monday.plusDays(6)); day = day.plusDays(1)) {
            if (path.restDays.contains(day.getDayOfWeek().name().substring(0, 3))) continue;
            if (day.isAfter(today)) return false;
            if (path.kind.equals("JLPT")) {
                Integer done = db.queryForObject("SELECT COUNT(*) FROM jpath_daily_plan WHERE path_id = ? "
                        + "AND study_day = ? AND all_done_at IS NOT NULL", Integer.class, path.id, day);
                if (done == null || done == 0) return false;
            } else {
                List<Map<String, Object>> progress = db.queryForList("SELECT done_at FROM cpath_user_situation "
                        + "WHERE path_id = ? AND study_day = ?", path.id, day);
                if (progress.isEmpty() || progress.stream().anyMatch(row -> row.get("done_at") == null)) return false;
            }
        }
        return true;
    }

    private Path path(Long userId, String key) {
        boolean jlpt = "JLPT".equals(key);
        if (!jlpt && (key == null || !key.startsWith("COMM:")))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Lộ trình không hợp lệ.");
        List<Map<String, Object>> rows = jlpt
                ? db.queryForList("SELECT p.id, p.created_at, p.rest_days, p.daily_minutes, l.slider_vocab pace "
                        + "FROM jpath_user_path p JOIN jpath_user_level l ON l.path_id = p.id AND l.level = p.open_level "
                        + "WHERE p.user_id = ? AND p.status = 'ACTIVE'", userId)
                : db.queryForList("SELECT id, created_at, rest_days, daily_situations pace, "
                        + "daily_situations * 15 daily_minutes FROM cpath_user_path "
                        + "WHERE user_id = ? AND status = 'ACTIVE' AND goal_code = ?", userId, key.substring(5));
        if (rows.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy lộ trình đang học.");
        Map<String, Object> row = rows.get(0);
        Object createdValue = row.get("created_at");
        LocalDateTime created = createdValue instanceof java.sql.Timestamp timestamp
                ? timestamp.toLocalDateTime() : (LocalDateTime) createdValue;
        return new Path(jlpt ? "JLPT" : "COMM", ((Number) row.get("id")).longValue(),
                ((Number) row.get("pace")).intValue(), ((Number) row.get("daily_minutes")).intValue(),
                created,
                row.get("rest_days") == null ? "" : String.valueOf(row.get("rest_days")));
    }

    private record Path(String kind, long id, int current, int minutes, LocalDateTime created, String restDays) {}
}
