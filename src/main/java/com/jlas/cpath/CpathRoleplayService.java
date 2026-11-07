package com.jlas.cpath;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Questions are rebuilt on submission so neither answers nor scores come from the browser. */
@Service
public class CpathRoleplayService {
    private final JdbcTemplate db;
    private final CpathService path;

    public CpathRoleplayService(JdbcTemplate db, CpathService path) {
        this.db = db;
        this.path = path;
    }

    record Question(String id, String promptJa, String promptVi, String promptAudio,
                    String replyJa, List<String> options, int answer, boolean speaking) {
        Map<String, Object> publicView() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("promptJa", promptJa);
            out.put("promptVi", promptVi);
            out.put("promptAudioUrl", promptAudio);
            out.put("speaking", speaking);
            if (speaking) out.put("replyJa", replyJa);
            else out.put("options", options);
            return out;
        }
    }

    private record Context(long pathId, String goal, String scope, String code) {}

    private Context context(Long userId, String scope, String code) {
        if (!List.of("goal", "topic", "situation").contains(scope))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Loại bài nhập vai không hợp lệ.");
        if (!"goal".equals(scope) && (code == null || code.isBlank()))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu mã chủ đề hoặc tình huống.");
        List<Map<String, Object>> paths;
        if ("situation".equals(scope)) {
            paths = db.queryForList("SELECT p.id, p.goal_code FROM cpath_user_path p "
                    + "JOIN cpath_topic t ON t.goal_code = p.goal_code "
                    + "JOIN cpath_situation s ON s.topic_code = t.code AND s.code = ? "
                    + "WHERE p.user_id = ? AND p.status = 'ACTIVE' ORDER BY p.role = 'MAIN' DESC, p.id DESC LIMIT 1", code, userId);
        } else if ("topic".equals(scope)) {
            paths = db.queryForList("SELECT p.id, p.goal_code FROM cpath_user_path p "
                    + "JOIN cpath_topic t ON t.goal_code = p.goal_code AND t.code = ? "
                    + "WHERE p.user_id = ? AND p.status = 'ACTIVE' ORDER BY p.role = 'MAIN' DESC, p.id DESC LIMIT 1", code, userId);
        } else {
            paths = db.queryForList("SELECT id, goal_code FROM cpath_user_path WHERE user_id = ? AND role = 'MAIN' AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1", userId);
        }
        if (paths.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình giao tiếp.");
        long id = ((Number) paths.get(0).get("id")).longValue();
        String goal = String.valueOf(paths.get(0).get("goal_code"));
        if (!"goal".equals(scope)) {
            String sql = "topic".equals(scope)
                    ? "SELECT COUNT(*) FROM cpath_topic WHERE code = ? AND goal_code = ?"
                    : "SELECT COUNT(*) FROM cpath_situation s JOIN cpath_topic t ON t.code = s.topic_code WHERE s.code = ? AND t.goal_code = ?";
            Integer count = db.queryForObject(sql, Integer.class, code, goal);
            if (count == null || count == 0) throw new ApiException(ErrorCode.NOT_FOUND, "Nội dung không thuộc lộ trình hiện tại.");
        }
        return new Context(id, goal, scope, code);
    }

    private static String audio(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        String s = String.valueOf(value);
        return s.startsWith("/") ? s : "/uploads/content-audio/" + s;
    }

    private static boolean learner(Map<String, Object> row) {
        Object value = row.get("learner");
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }

    private List<Question> questions(Context ctx) {
        List<Map<String, Object>> rows = db.queryForList("SELECT l.situation_code, l.line_order, l.learner, l.ja, l.vi, l.audio_path, s.topic_code "
                + "FROM cpath_line l JOIN cpath_situation s ON s.code = l.situation_code "
                + "JOIN cpath_topic t ON t.code = s.topic_code WHERE t.goal_code = ? "
                + "ORDER BY s.sort_order, l.situation_code, l.line_order", ctx.goal());
        Set<String> replies = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) if (learner(row))
            replies.add(String.valueOf(row.get("ja")));
        if (replies.size() < 4) throw new ApiException(ErrorCode.CONFLICT, "Mục tiêu chưa đủ câu đáp để tạo bài nhập vai.");
        List<Map<String, Object>> candidates = new ArrayList<>();
        Map<String, Object> previous = null;
        for (Map<String, Object> row : rows) {
            if (learner(row) && previous != null && previous.get("situation_code").equals(row.get("situation_code"))
                    && !learner(previous)) {
                boolean inScope = "goal".equals(ctx.scope()) || ("topic".equals(ctx.scope()) && ctx.code().equals(row.get("topic_code")))
                        || ("situation".equals(ctx.scope()) && ctx.code().equals(row.get("situation_code")));
                if (inScope) {
                    Map<String, Object> pair = new LinkedHashMap<>();
                    pair.put("situation_code", row.get("situation_code"));
                    pair.put("line_order", row.get("line_order"));
                    pair.put("ja", row.get("ja"));
                    pair.put("prompt", previous);
                    candidates.add(pair);
                }
            }
            previous = row;
        }
        Collections.shuffle(candidates, new Random(ctx.pathId() * 31 + ctx.scope().hashCode() * 17L + (ctx.code() == null ? 0 : ctx.code().hashCode())));
        int limit = "goal".equals(ctx.scope()) ? 15 : "topic".equals(ctx.scope()) ? 10 : 5;
        List<Map<String, Object>> chosen = candidates.subList(0, Math.min(limit, candidates.size()));
        if (chosen.isEmpty()) throw new ApiException(ErrorCode.CONFLICT, "Chưa có câu thoại để luyện nhập vai.");
        int speakingCount = "situation".equals(ctx.scope()) ? 0 : Math.min(5, Math.max(3, chosen.size() / 3));
        speakingCount = Math.min(speakingCount, Math.max(0, chosen.size() - 1));
        List<String> allReplies = new ArrayList<>(replies);
        List<Question> out = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            Map<String, Object> row = chosen.get(i);
            @SuppressWarnings("unchecked") Map<String, Object> prompt = (Map<String, Object>) row.get("prompt");
            String right = String.valueOf(row.get("ja"));
            String id = row.get("situation_code") + "-L" + row.get("line_order");
            boolean speaking = i >= chosen.size() - speakingCount;
            List<String> options = new ArrayList<>();
            int answer = -1;
            if (!speaking) {
                options.add(right);
                List<String> distractors = new ArrayList<>(allReplies);
                Collections.shuffle(distractors, new Random(id.hashCode()));
                for (String other : distractors) if (!other.equals(right) && !options.contains(other) && options.size() < 4) options.add(other);
                if (options.size() != 4) throw new ApiException(ErrorCode.CONFLICT, "Bài nhập vai chưa đủ phương án trả lời.");
                Collections.shuffle(options, new Random(ctx.pathId() + id.hashCode()));
                answer = options.indexOf(right);
            }
            out.add(new Question(id, String.valueOf(prompt.get("ja")), String.valueOf(prompt.getOrDefault("vi", "")),
                    audio(prompt.get("audio_path")), right, List.copyOf(options), answer, speaking));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long userId, String scope, String code) {
        Context ctx = context(userId, scope, code);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("goal", ctx.goal());
        out.put("code", code);
        out.put("passScore", "topic".equals(scope) ? 80 : 70);
        out.put("open", !"goal".equals(scope) || path.goalRetryOpen(userId));
        out.put("questions", questions(ctx).stream().map(Question::publicView).toList());
        return out;
    }

    List<Question> gradingQuestions(Long userId, String scope, String code) {
        return questions(context(userId, scope, code));
    }

    @Transactional
    public Map<String, Object> submit(Long userId, String scope, String code, Map<String, Integer> answers,
                                      Map<String, Integer> speakingScores) {
        Context ctx = context(userId, scope, code);
        List<Question> qs = questions(ctx);
        int correct = 0, choiceTotal = 0, speakSum = 0;
        List<Map<String, Object>> review = new ArrayList<>();
        for (Question q : qs) {
            if (q.speaking()) {
                Integer score = speakingScores.get(q.id());
                if (score != null) speakSum += Math.max(0, Math.min(100, score));
            } else {
                choiceTotal++;
                Integer answer = answers.get(q.id());
                if (answer == null || answer < 0 || answer >= 4) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Hãy trả lời đủ câu chọn đáp án.");
                boolean ok = answer == q.answer();
                if (ok) correct++;
                review.add(Map.of("id", q.id(), "ja", q.promptJa(), "correct", ok, "answer", q.replyJa()));
            }
        }
        int choiceScore = Math.round(100f * correct / choiceTotal);
        int speakCount = (int) qs.stream().filter(q -> q.speaking() && speakingScores.get(q.id()) != null).count();
        int speakScore = speakCount == 0 ? 0 : Math.round((float) speakSum / speakCount);
        int score = speakCount == 0 ? choiceScore : Math.round((choiceScore + speakScore) / 2f);
        boolean passed = score >= ("topic".equals(scope) ? 80 : 70);
        int cp = 0;
        if ("goal".equals(scope)) cp = path.recordFallbackGoal(userId, score);
        else if ("topic".equals(scope)) {
            cp = path.recordTopicRoleplay(userId, ctx.code(), score);
            passed = path.topicPassed(userId, ctx.code());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("choiceScore", choiceScore);
        out.put("speakingScore", speakCount == 0 ? null : speakScore);
        out.put("speakingSkipped", speakCount == 0 && qs.stream().anyMatch(Question::speaking));
        out.put("correct", correct);
        out.put("total", choiceTotal);
        out.put("passed", passed);
        if ("topic".equals(scope)) {
            out.put("roleplayPassed", score >= 80);
            out.put("missingSpeak", score >= 80 && !passed);
        }
        out.put("cp", cp);
        out.put("review", review);
        return out;
    }
}
