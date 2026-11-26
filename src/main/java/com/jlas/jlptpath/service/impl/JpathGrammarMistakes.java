package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * [JLAS-W0108] Q6 (owner 24/11): the "Ngữ pháp hay sai" tab of the "Lộ trình học" notebook. Read only: the grammar
 * patterns the learner answered wrong in path checks, how many times, the latest wrong question and a link to the
 * lesson. Never makes review cards and never changes item state.
 */
@Service
@RequiredArgsConstructor
public class JpathGrammarMistakes {

    static final int LIMIT = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate db;
    private final PathTeamNotebook teamNotebook;

    /** One pattern the learner got wrong. {@code lastPrompt}/{@code lastChosen} may be null when the check is gone. */
    public record Mistake(String code, String pattern, String meaningVi, String level, int wrongCount,
                          LocalDateTime lastWrongAt, String lastPrompt, String lastChosen, String lessonHref) {
    }

    public record View(Long notebookId, List<Mistake> items) {
    }

    public View read(Long userId) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT e.item_code code, COUNT(*) wrong_count, MAX(e.created_at) last_at,
                       MAX(g.pattern) pattern, MAX(g.meaning_vi) meaning_vi, MAX(g.level) level
                FROM jpath_study_event e LEFT JOIN jpath_grammar g ON g.code = e.item_code
                WHERE e.user_id = ? AND e.item_type = 'GRAMMAR' AND e.action = 'CHECK_ANSWER' AND e.correct = FALSE
                GROUP BY e.item_code ORDER BY COUNT(*) DESC, MAX(e.created_at) DESC LIMIT %d""".formatted(LIMIT), userId);
        List<Mistake> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            String code = (String) r.get("code");
            String[] last = lastWrong(userId, code);
            out.add(new Mistake(code, (String) r.get("pattern"), (String) r.get("meaning_vi"), (String) r.get("level"),
                    ((Number) r.get("wrong_count")).intValue(), time(r.get("last_at")), last[0], last[1],
                    "/learning/jlpt/grammar/" + code));
        }
        return new View(teamNotebook.notebookIdFor(userId), out);
    }

    /** The prompt and chosen option of the latest wrong answer, read from its check set ("c{setId}-{questionId}"). */
    private String[] lastWrong(Long userId, String code) {
        List<String> ids = db.queryForList("""
                SELECT client_event_id FROM jpath_study_event
                WHERE user_id = ? AND item_type = 'GRAMMAR' AND item_code = ? AND action = 'CHECK_ANSWER' AND correct = FALSE
                ORDER BY created_at DESC, id DESC LIMIT 1""", String.class, userId, code);
        if (ids.isEmpty()) return new String[2];
        return promptOf(userId, ids.get(0));
    }

    String[] promptOf(Long userId, String clientEventId) {
        String[] none = new String[2];
        if (clientEventId == null || !clientEventId.startsWith("c")) return none;
        int dash = clientEventId.indexOf('-');
        if (dash < 2) return none;
        long setId;
        try {
            setId = Long.parseLong(clientEventId.substring(1, dash));
        } catch (NumberFormatException e) {
            return none;
        }
        String qid = clientEventId.substring(dash + 1);
        List<Map<String, Object>> sets = db.queryForList(
                "SELECT questions_json, progress_json FROM jpath_check_set WHERE id = ? AND user_id = ?", setId, userId);
        if (sets.isEmpty()) return none;
        return fromJson((String) sets.get(0).get("questions_json"), (String) sets.get(0).get("progress_json"), qid);
    }

    static String[] fromJson(String questionsJson, String progressJson, String qid) {
        String[] out = new String[2];
        try {
            JsonNode qs = JSON.readTree(questionsJson == null ? "[]" : questionsJson);
            JsonNode q = null;
            for (JsonNode n : qs) {
                if (qid.equals(n.path("id").asText())) { q = n; break; }
            }
            if (q == null) return out;
            out[0] = q.path("prompt").isTextual() ? q.path("prompt").asText() : null;
            String chosen = JSON.readTree(progressJson == null ? "{}" : progressJson)
                    .path("answers").path(qid).path("value").asText(null);
            if (chosen != null) {
                for (JsonNode o : q.path("options")) {
                    if (chosen.equals(o.path("id").asText())) { out[1] = o.path("label").asText(null); break; }
                }
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private static LocalDateTime time(Object o) {
        if (o instanceof LocalDateTime t) return t;
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        return null;
    }
}
