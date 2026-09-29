package com.jlas.kaiwa.service.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.kaiwa.model.ConversationSession;
import com.jlas.kaiwa.model.Scenario;
import com.jlas.kaiwa.model.ScenarioSuggestedReply;
import com.jlas.kaiwa.model.TextAnnotationCache;
import com.jlas.kaiwa.repository.ScenarioSuggestedReplyRepository;
import com.jlas.kaiwa.repository.TextAnnotationCacheRepository;
import com.jlas.kaiwa.service.ai.OpenRouterClient;
import com.jlas.kaiwa.service.scenario.ScenarioContentService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SuggestedReplyService {

    private final ScenarioSuggestedReplyRepository replyRepository;
    private final TextAnnotationCacheRepository cacheRepository;
    private final OpenRouterClient openRouterClient;
    private final ObjectMapper objectMapper;
    private final ScenarioContentService scenarioContent;

    public SuggestedReplyService(
            ScenarioSuggestedReplyRepository replyRepository,
            TextAnnotationCacheRepository cacheRepository,
            OpenRouterClient openRouterClient,
            ObjectMapper objectMapper,
            ScenarioContentService scenarioContent) {
        this.replyRepository = replyRepository;
        this.cacheRepository = cacheRepository;
        this.openRouterClient = openRouterClient;
        this.objectMapper = objectMapper;
        this.scenarioContent = scenarioContent;
    }

    /** Instant: only DB rows (no OpenRouter). Use for page render. */
    @Transactional(readOnly = true)
    public List<String> suggestionsFromDbOnly(ConversationSession session) {
        Scenario scenario = session.getScenario();
        String stateName = session.getCurrentState() != null ? session.getCurrentState().getName() : null;
        List<String> compact = scenarioContent.suggestedReplies(scenario, session.getCurrentState());
        if (!compact.isEmpty()) return compact.stream().limit(5).toList();
        return replyRepository.findForState(scenario.getId(), stateName).stream()
                .map(ScenarioSuggestedReply::getContentJa)
                .limit(5)
                .toList();
    }

    @Transactional
    public List<String> suggestions(ConversationSession session) {
        Scenario scenario = session.getScenario();
        String stateName = session.getCurrentState() != null ? session.getCurrentState().getName() : null;

        List<String> compact = scenarioContent.suggestedReplies(scenario, session.getCurrentState());
        if (!compact.isEmpty()) return compact.stream().limit(5).toList();

        List<ScenarioSuggestedReply> db = replyRepository.findForState(scenario.getId(), stateName);
        if (!db.isEmpty()) {
            return db.stream().map(ScenarioSuggestedReply::getContentJa).limit(5).toList();
        }

        String cacheKey = "suggest:" + scenario.getId() + ":" + (stateName == null ? "_" : stateName)
                + ":" + scenario.getJlptLevel();
        String hash = sha256(cacheKey);
        var cached = cacheRepository.findByContentHash(hash);
        if (cached.isPresent() && cached.get().getFuriganaJson() != null) {
            // reuse furigana_json field to store JSON array of strings for suggestions
            try {
                return objectMapper.readValue(cached.get().getFuriganaJson(), new TypeReference<>() {});
            } catch (Exception ignored) {
            }
        }

        List<String> ai = callAi(session);
        try {
            TextAnnotationCache row = cacheRepository.findByContentHash(hash).orElseGet(TextAnnotationCache::new);
            row.setContentHash(hash);
            row.setContentJa(cacheKey);
            row.setFuriganaJson(objectMapper.writeValueAsString(ai));
            row.setSource("ai");
            cacheRepository.save(row);
        } catch (Exception ignored) {
        }
        return ai;
    }

    private List<String> callAi(ConversationSession session) {
        try {
            Scenario s = session.getScenario();
            String state = session.getCurrentState() != null ? session.getCurrentState().getName() : "";
            String system = """
                    You generate short Japanese suggested replies for a language learner.
                    Return ONLY a JSON array of 3 to 5 short Japanese strings.
                    Match JLPT level and current conversation state.
                    Do NOT spoil future plot. Optional hints only.
                    Example: ["一人です。","二人です。","予約しました。"]
                    """;
            String user = "Scenario: " + s.getTitle()
                    + "\nJLPT: " + s.getJlptLevel()
                    + "\nState: " + state
                    + "\nUser role: " + (s.getUserRole() != null ? s.getUserRole().getName() : "learner");
            String raw = openRouterClient.complete(system, List.of(), user);
            int a = raw.indexOf('[');
            int b = raw.lastIndexOf(']');
            if (a >= 0 && b > a) {
                return objectMapper.readValue(raw.substring(a, b + 1), new TypeReference<>() {});
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
