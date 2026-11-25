package com.jlas.kaiwa.service.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.kaiwa.model.ConversationMessage;
import com.jlas.kaiwa.model.ConversationSession;
import com.jlas.kaiwa.model.ScenarioCompactDefinition;
import com.jlas.kaiwa.model.Scenario;
import com.jlas.kaiwa.model.ScenarioSuggestedReply;
import com.jlas.kaiwa.model.TextAnnotationCache;
import com.jlas.kaiwa.repository.ConversationMessageRepository;
import com.jlas.kaiwa.repository.ScenarioSuggestedReplyRepository;
import com.jlas.kaiwa.repository.TextAnnotationCacheRepository;
import com.jlas.kaiwa.service.ai.OpenRouterClient;
import com.jlas.kaiwa.service.scenario.ScenarioContentService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SuggestedReplyService {

    private final ScenarioSuggestedReplyRepository replyRepository;
    private final TextAnnotationCacheRepository cacheRepository;
    private final OpenRouterClient openRouterClient;
    private final ObjectMapper objectMapper;
    private final ScenarioContentService scenarioContent;
    private final ConversationMessageRepository messageRepository;

    /** Shared two-character chunks needed before the AI's line is taken to point at one next state. */
    private static final int MIN_BRANCH_OVERLAP = 2;
    /** Next states offered when the AI's line does not point at one. */
    private static final int FALLBACK_BRANCHES = 2;

    public SuggestedReplyService(
            ScenarioSuggestedReplyRepository replyRepository,
            TextAnnotationCacheRepository cacheRepository,
            OpenRouterClient openRouterClient,
            ObjectMapper objectMapper,
            ScenarioContentService scenarioContent,
            ConversationMessageRepository messageRepository) {
        this.replyRepository = replyRepository;
        this.cacheRepository = cacheRepository;
        this.openRouterClient = openRouterClient;
        this.objectMapper = objectMapper;
        this.scenarioContent = scenarioContent;
        this.messageRepository = messageRepository;
    }

    /** Instant: only DB rows (no OpenRouter). Use for page render. */
    @Transactional(readOnly = true)
    public List<String> suggestionsFromDbOnly(ConversationSession session) {
        Scenario scenario = session.getScenario();
        String stateName = session.getCurrentState() != null ? session.getCurrentState().getName() : null;
        List<String> compact = scenarioContent.suggestedReplies(scenario, session.getCurrentState());
        if (!compact.isEmpty()) return followConversation(session, compact);
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
        if (!compact.isEmpty()) return followConversation(session, compact);

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
            String raw = openRouterClient.complete(com.jlas.common.ai.AiFeature.AI_CALL_SUGGEST, system, List.of(), user);
            int a = raw.indexOf('[');
            int b = raw.lastIndexOf(']');
            if (a >= 0 && b > a) {
                return objectMapper.readValue(raw.substring(a, b + 1), new TypeReference<>() {});
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>();
    }

    /**
     * Suggestions belong to the scenario state, but the state only moves on once its goal is met, while the AI may
     * already be talking about the next step (the learner said "一人です。" but has not asked for a table, and the
     * staff already asks them to wait). A suggestion the learner has already said is dropped; once one is, the
     * suggestions of the next state follow the AI instead: the next state whose opening line is closest to what the
     * AI just said, or every next state when nothing points to one branch.
     */
    List<String> followConversation(ConversationSession session, List<String> current) {
        List<ConversationMessage> messages = session.getId() == null ? List.of()
                : messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        Set<String> said = new HashSet<>();
        String lastAi = "";
        for (ConversationMessage message : messages) {
            if ("user".equals(message.getRole())) said.add(spoken(message.getContent()));
            else if ("assistant".equals(message.getRole()) && message.getContent() != null) lastAi = message.getContent();
        }
        List<String> fresh = current.stream().filter(text -> !said.contains(spoken(text))).toList();
        if (fresh.size() == current.size()) return current.stream().limit(5).toList();

        Set<String> result = new LinkedHashSet<>(fresh);
        for (String text : nextSuggestions(session, lastAi)) {
            if (!said.contains(spoken(text))) result.add(text);
        }
        return result.stream().limit(5).toList();
    }

    private List<String> nextSuggestions(ConversationSession session, String lastAi) {
        ScenarioCompactDefinition flow = scenarioContent.flow(session.getScenario()).orElse(null);
        if (flow == null || flow.states() == null || flow.edges() == null) return List.of();
        String code = session.getCurrentState() != null ? session.getCurrentState().getCode() : flow.initialState();
        List<ScenarioCompactDefinition.State> next = flow.edges().stream()
                .filter(edge -> Objects.equals(edge.source(), code))
                .map(ScenarioCompactDefinition.Edge::target).distinct()
                .flatMap(target -> flow.states().stream().filter(state -> Objects.equals(state.code(), target)).limit(1))
                .toList();
        int best = next.stream().mapToInt(state -> overlap(lastAi, state.openingLine())).max().orElse(0);
        // One shared chunk ("はい") says nothing; without a clear branch, offer the scenario's first two next steps.
        List<ScenarioCompactDefinition.State> chosen = best >= MIN_BRANCH_OVERLAP
                ? next.stream().filter(state -> overlap(lastAi, state.openingLine()) == best).toList()
                : next.stream().limit(FALLBACK_BRANCHES).toList();
        return chosen.stream()
                .flatMap(state -> state.suggestedReplies() == null ? java.util.stream.Stream.<String>empty()
                        : state.suggestedReplies().stream())
                .toList();
    }

    /** Shared two-character chunks, ignoring punctuation and spaces: enough to tell "満席…待" from "お席へどうぞ". */
    static int overlap(String a, String b) {
        Set<String> left = bigrams(a);
        left.retainAll(bigrams(b));
        return left.size();
    }

    private static Set<String> bigrams(String text) {
        int[] points = spoken(text).codePoints().toArray();
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < points.length; i++) result.add(new String(points, i, 2));
        return result;
    }

    /** What was said, without width, spacing or punctuation differences: "一人です" equals "一人です。". */
    static String spoken(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return normalized.replaceAll("[\\s\\p{Punct}、。！？「」『』（）・…～〜]", "");
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
