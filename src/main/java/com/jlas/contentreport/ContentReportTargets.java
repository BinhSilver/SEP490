package com.jlas.contentreport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.conjugation.VerbCatalog;
import com.jlas.conjugation.VerbEngine;
import com.jlas.conjugation.VerbPracticeService;
import com.jlas.conjugation.VerbSessionRepository;
import com.jlas.dictionary.kanjimap.KanjiMapContentService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.jlas.contentreport.ContentReportDtos.*;

/** Validates source identity and records the source independently of client-supplied labels or links. */
@Component @RequiredArgsConstructor
public class ContentReportTargets {
    private final JdbcTemplate jdbc;
    private final VerbSessionRepository sessions;
    private final VerbCatalog verbs;
    private final KanjiMapContentService kanjis;
    private final ObjectMapper mapper = new ObjectMapper();

    public TargetSnapshot resolve(Long userId, Request request) {
        if (request.targetType() == null || request.page() == null) throw invalid();
        return switch (request.targetType()) {
            case JLPT_QUESTION, AI_EXPLANATION -> jlpt(request);
            case VERB_QUESTION -> verb(userId, request);
            case KANJI_MAP -> kanji(request);
        };
    }

    private TargetSnapshot jlpt(Request request) {
        if (request.page() != Page.JLPT_EXAM && request.page() != Page.JLPT_PRACTICE) throw invalid();
        long id;
        try { id = Long.parseLong(request.questionId()); if (id <= 0) throw invalid(); }
        catch (NumberFormatException | NullPointerException error) { throw invalid(); }
        // Match learner exam/practice visibility in JlptServiceImpl, including accessible draft exams.
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT q.id,q.question_number,q.question_text,q.correct_option,e.exam_code,e.title,e.jlpt_level
                FROM jlpt_question q JOIN jlpt_mondai m ON m.id=q.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id JOIN jlpt_exam e ON e.id=p.exam_id
                WHERE q.id=? AND e.status IN ('DRAFT','PUBLISHED')
                """, id);
        if (rows.isEmpty()) throw missing();
        Map<String,Object> row = rows.getFirst();
        ObjectNode context = mapper.valueToTree(row);
        context.set("options", mapper.valueToTree(jdbc.queryForList(
                "SELECT option_number,option_text FROM jlpt_option WHERE question_id=? ORDER BY option_number", id)));
        String code = String.valueOf(id);
        if (request.targetType() == Target.AI_EXPLANATION) {
            String explanation = trim(request.explanation());
            if (explanation.isEmpty() || explanation.length() > 20000) throw invalid();
            // A snapshot of what the reporter saw, not a new AI generation or an authoritative content edit.
            context.put("reportedExplanation", explanation);
            code += ":" + digest(explanation);
        }
        String label = row.get("exam_code") + " · Câu " + row.get("question_number");
        if (request.targetType() == Target.AI_EXPLANATION) label += " · Giải thích AI";
        return new TargetSnapshot(request.targetType(), code, label,
                "/jlpt/exam?code=" + encode(String.valueOf(row.get("exam_code"))), context);
    }

    private TargetSnapshot verb(Long userId, Request request) {
        if (request.page() != Page.VERB_PRACTICE) throw invalid();
        var session = sessions.findByIdAndUserId(trim(request.sessionId()), userId).orElseThrow(ContentReportTargets::missing);
        VerbPracticeService.Payload payload;
        try { payload = mapper.readValue(session.getPayload(), VerbPracticeService.Payload.class); }
        catch (JsonProcessingException error) { throw missing(); }
        var prompt = payload.questions().stream().filter(q -> q.id().equals(request.questionId()))
                .findFirst().orElseThrow(ContentReportTargets::missing);
        var word = verbs.verb(prompt.verbId());
        ObjectNode context = mapper.createObjectNode();
        context.put("sessionId", session.getId()); context.put("questionId", prompt.id());
        context.set("verb", mapper.valueToTree(word)); context.put("form", prompt.form().name());
        context.put("expected", VerbEngine.conjugate(word, prompt.form(), false));
        context.put("explanation", VerbEngine.explanation(word, prompt.form()));
        context.set("choices", mapper.valueToTree(prompt.choices()));
        if (prompt.contextId() != null) context.set("sentence", mapper.valueToTree(verbs.context(prompt.contextId())));
        return new TargetSnapshot(Target.VERB_QUESTION, session.getId() + ":" + prompt.id(),
                word.word() + " → " + prompt.form().label, "/practice/verbs", context);
    }

    private TargetSnapshot kanji(Request request) {
        if (request.page() != Page.KANJI_MAP) throw invalid();
        String character = trim(request.character());
        var node = kanjis.graph(false).path("nodes");
        for (var candidate : node) {
            if (!character.isEmpty() && character.equals(candidate.path("character").asText())) {
                ObjectNode context = mapper.createObjectNode();
                for (String field : List.of("character","hanViet","meaningVi","on","kun","components","memoryHint","level")) {
                    if (candidate.has(field)) context.set(field, candidate.get(field).deepCopy());
                }
                return new TargetSnapshot(Target.KANJI_MAP, character,
                        character + " · " + candidate.path("hanViet").asText(),
                        "/dictionary/kanji-map?kanji=" + encode(character), context);
            }
        }
        throw missing();
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static ApiException invalid() { return new ApiException(ErrorCode.VALIDATION_FAILED,"Nội dung báo cáo không hợp lệ."); }
    private static ApiException missing() { return new ApiException(ErrorCode.NOT_FOUND,"Không tìm thấy nội dung cần báo cáo."); }
}
