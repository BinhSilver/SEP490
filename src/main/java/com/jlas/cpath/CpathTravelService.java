package com.jlas.cpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] F70 (owner 12/11): Du lịch is learnt for fun and has no base set; instead it gets three extras built
 * from the content already seeded for the goal (no new content):
 * <ul>
 *   <li>Sổ câu cấp tốc — the goal's phrases grouped by place (topic), each one plays on a tap;</li>
 *   <li>Trò chơi nghe nhanh — 60 seconds: hear a word or phrase, pick its meaning, a streak adds to the score;</li>
 *   <li>Huy hiệu theo điểm đến — a stamp per topic whose situations are all done, on the trip map.</li>
 * </ul>
 */
@Service
public class CpathTravelService {

    static final String GOAL = "TRAVEL";
    static final int GAME_SECONDS = 60;
    static final int GAME_POOL = 60;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate db;

    public CpathTravelService(JdbcTemplate db) {
        this.db = db;
    }

    private static String url(Object path) {
        return path == null || path.toString().isBlank() ? null : CpathService.AUDIO_URL + path;
    }

    /** The learner's active TRAVEL path (main or secondary), or null. */
    private Map<String, Object> travelPath(Long userId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM cpath_user_path WHERE user_id = ? AND goal_code = ? "
                + "AND status = 'ACTIVE' ORDER BY role = 'MAIN' DESC, id DESC LIMIT 1", userId, GOAL);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> display(Map<String, Object> path) {
        if (path != null) return CpathService.displayOf(path);
        return CpathService.displayOf(Map.of("goal_code", GOAL, "self_level", "BEGINNER"));
    }

    /** Sổ câu cấp tốc: every phrase of the goal, grouped by topic (place) in topic order. */
    @Transactional(readOnly = true)
    public Map<String, Object> phrasebook(Long userId) {
        Map<String, Map<String, Object>> topics = new LinkedHashMap<>();
        for (Map<String, Object> r : db.queryForList("SELECT t.code topic, t.name_vi, t.name_ja, i.code, i.ja, i.reading, i.vi, i.note_vi, i.audio_path "
                + "FROM cpath_item i JOIN cpath_topic t ON t.code = i.topic_code WHERE t.goal_code = ? AND i.kind = 'PHRASE' "
                + "ORDER BY t.sort_order, i.sort_order", GOAL)) {
            Map<String, Object> t = topics.computeIfAbsent(String.valueOf(r.get("topic")), k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("code", k);
                x.put("nameVi", r.get("name_vi"));
                x.put("nameJa", r.get("name_ja"));
                x.put("phrases", new ArrayList<Map<String, Object>>());
                return x;
            });
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", r.get("code"));
            p.put("ja", r.get("ja"));
            p.put("reading", r.get("reading"));
            p.put("vi", r.get("vi"));
            p.put("note", r.get("note_vi"));
            p.put("audioUrl", url(r.get("audio_path")));
            @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) t.get("phrases");
            list.add(p);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("display", display(travelPath(userId)));
        m.put("topics", new ArrayList<>(topics.values()));
        return m;
    }

    /** Trò chơi nghe nhanh: a shuffled pool of words and phrases that have audio, each with four meanings. */
    @Transactional(readOnly = true)
    public Map<String, Object> game(Long userId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT i.code, i.kind, i.ja, i.reading, i.vi, i.audio_path FROM cpath_item i "
                + "JOIN cpath_topic t ON t.code = i.topic_code WHERE t.goal_code = ? AND i.audio_path IS NOT NULL AND i.audio_path <> '' "
                + "AND i.vi IS NOT NULL ORDER BY i.code", GOAL);
        Random rnd = new Random(System.nanoTime());
        List<Map<String, Object>> pool = new ArrayList<>(rows);
        Collections.shuffle(pool, rnd);
        List<Map<String, Object>> qs = new ArrayList<>();
        for (Map<String, Object> r : pool.subList(0, Math.min(GAME_POOL, pool.size()))) {
            List<String> others = new ArrayList<>();
            for (Map<String, Object> o : rows) {
                String vi = String.valueOf(o.get("vi"));
                if (o.get("kind").equals(r.get("kind")) && !vi.equals(r.get("vi")) && !others.contains(vi)) others.add(vi);
            }
            Collections.shuffle(others, rnd);
            List<String> options = new ArrayList<>(others.subList(0, Math.min(3, others.size())));
            int answer = rnd.nextInt(options.size() + 1);
            options.add(answer, String.valueOf(r.get("vi")));
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", r.get("code"));
            q.put("kind", r.get("kind"));
            q.put("ja", r.get("ja"));
            q.put("reading", r.get("reading"));
            q.put("audioUrl", url(r.get("audio_path")));
            q.put("options", options);
            q.put("answer", answer); // a game for fun: no CP, so the page checks the answer itself
            qs.add(q);
        }
        Map<String, Object> path = travelPath(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seconds", GAME_SECONDS);
        m.put("best", bestOf(path));
        m.put("display", display(path));
        m.put("questions", qs);
        return m;
    }

    private static int bestOf(Map<String, Object> path) {
        Object raw = path == null ? null : path.get("display_json");
        if (raw == null) return 0;
        try {
            Object b = JSON.readValue(raw.toString(), Map.class).get("gameBest");
            return b instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Keeps the best score on the TRAVEL path (next to the display choices); returns whether it is a new best. */
    @Transactional
    public Map<String, Object> submitGame(Long userId, Map<String, Object> body) {
        Object raw = body == null ? null : body.get("score");
        if (!(raw instanceof Number n) || n.intValue() < 0 || n.intValue() > 100_000)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Điểm không hợp lệ.");
        int score = n.intValue();
        Map<String, Object> path = travelPath(userId);
        int best = bestOf(path);
        boolean record = path != null && score > best;
        if (record) {
            try {
                Map<String, Object> saved = new LinkedHashMap<>();
                Object dj = path.get("display_json");
                if (dj != null) {
                    @SuppressWarnings("unchecked") Map<String, Object> old = JSON.readValue(dj.toString(), Map.class);
                    saved.putAll(old);
                }
                saved.put("gameBest", score);
                db.update("UPDATE cpath_user_path SET display_json = ? WHERE id = ?", JSON.writeValueAsString(saved), ((Number) path.get("id")).longValue());
                best = score;
            } catch (Exception e) {
                record = false; // a DB without the F61 column keeps no best score
            }
        }
        return Map.of("score", score, "best", Math.max(best, score), "record", record);
    }

    /** Huy hiệu theo điểm đến: one stamp per topic of the TRAVEL path, earned when all its situations are done. */
    @Transactional(readOnly = true)
    public Map<String, Object> stamps(Long userId) {
        Map<String, Object> path = travelPath(userId);
        Long pathId = path == null ? null : ((Number) path.get("id")).longValue();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : db.queryForList("SELECT t.code, t.name_vi, t.name_ja, COUNT(s.code) total, "
                + "SUM(CASE WHEN u.done_at IS NOT NULL THEN 1 ELSE 0 END) done, MAX(u.done_at) last_done "
                + "FROM cpath_topic t JOIN cpath_situation s ON s.topic_code = t.code "
                + "LEFT JOIN cpath_user_situation u ON u.situation_code = s.code AND u.path_id = ? "
                + "WHERE t.goal_code = ? GROUP BY t.code, t.name_vi, t.name_ja, t.sort_order ORDER BY t.sort_order", pathId, GOAL)) {
            int total = ((Number) t.get("total")).intValue();
            int done = t.get("done") == null ? 0 : ((Number) t.get("done")).intValue();
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("topic", t.get("code"));
            x.put("nameVi", t.get("name_vi"));
            x.put("nameJa", t.get("name_ja"));
            x.put("done", done);
            x.put("total", total);
            x.put("stamped", total > 0 && done >= total);
            x.put("stampedAt", total > 0 && done >= total && t.get("last_done") != null ? t.get("last_done").toString().substring(0, 10) : null);
            out.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasPath", path != null);
        m.put("stamps", out);
        m.put("earned", out.stream().filter(x -> Boolean.TRUE.equals(x.get("stamped"))).count());
        return m;
    }
}
