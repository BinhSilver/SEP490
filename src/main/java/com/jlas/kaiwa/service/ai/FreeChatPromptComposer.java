package com.jlas.kaiwa.service.ai;

import com.jlas.kaiwa.dto.FreeChatSettings;
import com.jlas.kaiwa.model.AiRole;
import com.jlas.kaiwa.model.Scenario;
import com.jlas.kaiwa.service.understanding.ConversationUnderstanding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Modular free-chat system prompt builder.
 * Loads markdown modules from {@code classpath:prompts/} and composes one system prompt
 * based on user settings (personality, level, AI initiative, favorite topics, ...).
 */
@Service
public class FreeChatPromptComposer {

    private static final Logger log = LoggerFactory.getLogger(FreeChatPromptComposer.class);

    private static final Set<String> PERSONALITIES = Set.of(
            "friendly", "teacher", "friend", "coworker", "senpai", "funny", "assistant");
    private static final Set<String> LEVELS = Set.of("n5", "n4", "n3", "n2", "n1", "native");
    private static final Set<String> STYLES = Set.of("casual", "polite", "keigo", "osaka", "anime");
    private static final Set<String> EMOTIONS = Set.of("calm", "friendly", "excited", "cheerful", "gentle");
    private static final Set<String> INITIATIVES = Set.of("low", "medium", "high");
    private static final Set<String> LENGTHS = Set.of("short", "medium", "long");
    private static final Set<String> MODES = Set.of(
            "free-talk", "interview", "debate", "storytelling", "question-game");

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final FreeChatTopicLibrary topicLibrary;

    public FreeChatPromptComposer(FreeChatTopicLibrary topicLibrary) {
        this.topicLibrary = topicLibrary;
    }

    public String compose(Scenario scenario, FreeChatSettings settings) {
        return compose(scenario, settings, null, null);
    }

    public String compose(Scenario scenario, FreeChatSettings settings, ConversationUnderstanding understanding) {
        return compose(scenario, settings, understanding, null);
    }

    /**
     * Slim compose for OpenRouter key prompt-token caps (~2.5–4k).
     * Full markdown packs remain on disk for docs; runtime injects compact rules.
     * Dynamic blocks (understanding / dialogue engine) carry turn-specific state.
     *
     * @param dialogueEngineBlock backend DialogueEngine snapshot (phase/entity/depth/recovery/…)
     */
    public String compose(
            Scenario scenario,
            FreeChatSettings settings,
            ConversationUnderstanding understanding,
            String dialogueEngineBlock) {
        FreeChatSettings s = settings != null ? settings : FreeChatSettings.defaults();
        List<String> parts = new ArrayList<>();

        String personality = pick(s.getPersonality(), PERSONALITIES, "friendly");
        String style = pick(s.getStyle(), STYLES, "casual");
        String initiative = pick(s.getInitiative(), INITIATIVES, "medium");
        String level = pick(s.getLevel(), LEVELS, "n3");
        String emotion = pick(s.getEmotion(), EMOTIONS, "friendly");
        String length = pick(s.getResponseLength(), LENGTHS, "medium");
        String mode = pick(s.getConversationMode(), MODES, "free-talk");

        // Core role + JSON schema (compact, not full base.md)
        parts.add(compactBase());
        // Silent correction (compact, not full correction.md)
        parts.add(compactCorrection());

        // Pre-LLM understanding (already short)
        if (understanding != null && understanding.getPromptBlock() != null
                && !understanding.getPromptBlock().isBlank()) {
            parts.add(truncate(understanding.getPromptBlock(), 900));
        }
        // Dialogue engine snapshot (already slimmed in DialogueEngineService)
        if (dialogueEngineBlock != null && !dialogueEngineBlock.isBlank()) {
            parts.add(truncate(dialogueEngineBlock, 1200));
        }

        parts.add(roleOverlay(scenario));
        // One compact settings line instead of 6 full markdown files + conversation pack
        parts.add(compactProfile(personality, level, style, emotion, initiative, length, mode));
        parts.add(questionDifficultyOverride(level));
        parts.add(keigoContextBlock(personality, style));

        if (s.isConversationActive()) {
            parts.add(compactActiveConversation(initiative));
            parts.add(buildFavoriteTopicsBlock(s.getFavoriteTopics()));
            // Compact topic bank only (max 2 packs, few lines each)
            parts.add(topicLibrary.buildTopicLibraryBlockCompact(s.getFavoriteTopics(), 2, 2, 4));
        } else {
            parts.add("""
                    # Passive
                    Only respond; do not force new topics. Still fill correction fields when JP is wrong.
                    """);
        }

        parts.add(compactFinalOverride());
        parts.add(settingBehavior(style, length, personality));

        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!out.isEmpty()) out.append("\n\n---\n\n");
            out.append(p.trim());
        }
        String result = out.toString();
        // Hard cap: leave room for history under ~2.6k–4k token key limits
        if (result.length() > 6500) {
            log.warn("Free-chat system prompt still long: {} chars — truncating", result.length());
            result = result.substring(0, 6500) + "\n\n# NOTE\nPrompt truncated for token limit. Keep JSON output.";
        }
        log.info("Free-chat system prompt chars={}", result.length());
        return result;
    }

    private static String compactBase() {
        return """
                # Role
                You are a real Japanese conversation partner (voice/text). Never reveal you are AI.

                # Hard split
                - "reply" = ONLY natural Japanese chat (no teaching, no quoting wrong form as lesson).
                - Correction  hasError/originalWrong/corrected/grammarReason(Vietnamese)/mistakeType.
                - Infer intended meaning; reply as if user said the natural form.
                - Never reply only with すみません、もう一度お願いします.

                # Output (ONLY valid JSON, no markdown fences)
                {"reply":"...","hasError":false,"originalWrong":null,"corrected":null,"grammarReason":null,"mistakeType":null,"objectivesMet":[],"readyToAdvanceState":false,"readyToEnd":false,"expressions":[]}
                """;
    }

    private static String compactCorrection() {
        return """
                # Silent correction (every turn)
                Detect wrong collocation/particle/tense/keigo/unnatural JP.
                hasError=true + corrected JP + grammarReason Vietnamese; reply continues natural chat.
                Examples: ゲームを食べますゲームをします; アニメをしますアニメを見ます; ケーキを飲みたいケーキを食べたい; 学校を行く学校に行く; 昨日行きます昨日行きました.
                Learners speak, so a wrong READING (いちひと for 一人) is a mistake about how to say it: write that
                part of corrected in hiragana as it must be said (ひとりです) and give kanji with its reading in
                grammarReason, e.g. 「一人（ひとり）」.
                Preserve the learner's intended tense, subject, people and place. Do not change present/future
                to past just because the assistant previously asked a past-tense question.
                Never lecture inside reply.
                """;
    }

    private static String compactProfile(
            String personality, String level, String style,
            String emotion, String initiative, String length, String mode) {
        return """
                # Profile
                personality=%s | level=%s | style=%s | emotion=%s | initiative=%s | length=%s | mode=%s
                Match JP difficulty to level. Answer questions with useful content; at most one follow-up question.
                Empathize first if user is tired/sad/happy/worried. 100%% Japanese in reply field only.
                """.formatted(personality, level, style, emotion, initiative, length, mode);
    }

    private static String compactActiveConversation(String initiative) {
        String depth = "high".equals(initiative)
                ? "High initiative: deepen current entity 5–15 turns; mix reaction/opinion/one question."
                : "medium".equals(initiative)
                ? "Share lead: sometimes ask, sometimes react/share."
                : "Low initiative: user leads; light prompt only if stuck.";
        return """
                # Conversation (AI active)
                %s
                Anti-loop: do not only 好き？なぜ？他は？ Rotate fact/feeling/choice/memory.
                Depth first on current entity before new topic. Related transitions only (そういえば…).
                Short user replies (はい/うん): react/share more, fewer questions.
                Remember user favorites mentioned in-session.
                """.formatted(depth);
    }

    private static String compactFinalOverride() {
        return """
                # FINAL (highest priority)
                reply=JP only, zero teaching. hasError for clear mistakes; grammarReason=Vietnamese.
                N5–N3: concrete questions only (not あなたにとって〜はどんな存在).
                Stay on entity; answer the request before asking a follow-up; return ONLY JSON.
                """;
    }

    private static String settingBehavior(String style, String length, String personality) {
        String speech = switch (style) {
            case "casual" -> "Use natural FRIEND-TO-FRIEND plain forms (だ、だね、する、かな). Avoid です/ます endings in reply. Never copy polite example endings from other blocks.";
            case "polite" -> "Use consistent です/ます politeness, friendly and respectful.";
            case "keigo" -> "Use respectful Japanese. In corrected, honor actions of superiors using 尊敬語 (e.g. 食べる -> 召し上がる), own actions toward them using 謙譲語. Adding です/ます alone is insufficient. Explain in Vietnamese.";
            default -> "Use the selected speech style consistently.";
        };
        String size = switch (length) {
            case "short" -> "Reply with 1–2 brief sentences, about 15–60 Japanese characters.";
            case "long" -> "Reply with 5–7 substantive sentences, about 180–350 Japanese characters. Explain, compare, or give concrete examples relevant to the question; do not just ask a question.";
            default -> "Reply with 2–3 concise sentences, about 60–150 Japanese characters.";
        };
        String role = "teacher".equals(personality)
                ? "As a teacher, provide reassuring guidance. When a learner describes a learning difficulty or requests advice, include one concrete, achievable practice action in this reply; encouragement and a diagnostic question alone are insufficient. Keep it conversational, not a lecture."
                : "friend".equals(personality) ? "As a friend, empathize and share personal encouragement naturally." : "Be warm and attentive.";
        return "# Selected behavior (overrides conflicting example styles and one-move hints)\n" + speech + "\n" + size + "\n" + role;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "\n…(truncated)";
    }

    private String questionDifficultyOverride(String level) {
        if ("n5".equals(level) || "n4".equals(level) || "n3".equals(level)) {
            return """
                    # Active level: %s (CONCRETE QUESTIONS ONLY)

                    Your follow-up questions MUST be concrete and easy.

                    Allowed patterns:
                    - どんなところが好きですか？
                    - 好きなキャラクターは誰ですか？
                    - 好きなシーンはありますか？
                    - どうして好きですか？
                    - もう一度見たいですか？

                    Forbidden (too abstract for this level):
                    - あなたにとって〜はどんな存在ですか？
                    - 〜の意味は何ですか？ (philosophical)
                    - 人生で何を感じましたか？
                    """.formatted(level.toUpperCase(Locale.ROOT));
        }
        return """
                # Active level: %s

                Questions may be a bit richer, but still prefer concrete over philosophical.
                """.formatted(level.toUpperCase(Locale.ROOT));
    }

    /**
     * Extra reminder so teacher/senpai/polite styles always check keigo/politeness.
     */
    private String keigoContextBlock(String personality, String style) {
        boolean respectRole = "teacher".equals(personality) || "senpai".equals(personality);
        boolean politeStyle = "polite".equals(style) || "keigo".equals(style);
        if (!respectRole && !politeStyle) {
            return """
                    # Politeness context

                    Role is peer/casual. Still correct clear grammar/vocab/particle errors.
                    Do not force keigo on the learner unless they attempt keigo incorrectly.
                    """;
        }
        return """
                # Politeness / Keigo context (ACTIVE)

                The learner is speaking in a respectful context
                (teacher, senpai, or polite/keigo style).

                You MUST check every turn for:
                - missing です・ます when politeness is expected
                - rude / overly rough plain forms toward a superior
                - incorrect keigo

                Report these via hasError + corrected + grammarReason (Vietnamese)
                with mistakeType "keigo" (or "grammar" if purely form/tense).
                """;
    }

    private String roleOverlay(Scenario scenario) {
        AiRole aiRole = scenario != null ? scenario.getAiRole() : null;
        if (aiRole == null) {
            return "# Role Overlay\n\nYou are a friendly Japanese conversation partner.";
        }
        String name = n(aiRole.getName());
        String personality = n(aiRole.getPersonality());
        String speech = n(aiRole.getSpeechStyle());
        return """
                # Role Overlay

                Display name / role: %s
                Personality notes: %s
                Speech notes: %s
                """.formatted(
                name.isBlank() ? "日本人の友達" : name,
                personality.isBlank() ? "(default)" : personality,
                speech.isBlank() ? "(default)" : speech);
    }

    private String buildFavoriteTopicsBlock(List<String> topics) {
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        if (topics != null) {
            for (String t : topics) {
                if (t == null) continue;
                String x = t.trim();
                if (!x.isEmpty()) cleaned.add(x);
            }
        }
        if (cleaned.isEmpty()) {
            return """
                    # Favorite Topics

                    The user has not selected favorite topics yet.

                    When you need a new topic, use light everyday subjects (food, weather, weekend, hobbies)
                    and transition naturally.
                    """;
        }
        StringBuilder list = new StringBuilder();
        for (String t : cleaned) {
            list.append("- ").append(t).append('\n');
        }
        return """
                # Favorite Topics

                The user's favorite topics are:

                %s
                These topics should be considered conversation anchors.

                Whenever you need a new topic, prioritize these interests before using general daily-life topics.

                The user should gradually feel that you remember what they enjoy discussing.
                """.formatted(list);
    }

    private String load(String relativePath) {
        return cache.computeIfAbsent(relativePath, this::readClasspath);
    }

    private String readClasspath(String relativePath) {
        String path = "prompts/" + relativePath;
        try {
            ClassPathResource res = new ClassPathResource(path);
            if (!res.exists()) {
                log.warn("Prompt module missing: {}", path);
                return "";
            }
            try (InputStream in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to load prompt module {}: {}", path, e.getMessage());
            return "";
        }
    }

    private static String pick(String value, Set<String> allowed, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String key = value.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
        return allowed.contains(key) ? key : fallback;
    }

    private static String n(String s) {
        return s == null ? "" : s.trim();
    }
}
