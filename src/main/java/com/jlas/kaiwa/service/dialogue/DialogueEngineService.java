package com.jlas.kaiwa.service.dialogue;

import com.jlas.kaiwa.dto.FreeChatSettings;
import com.jlas.kaiwa.model.ConversationMessage;
import com.jlas.kaiwa.service.understanding.ConversationUnderstanding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Free-chat rule engine: Conversation State, Hallucination Guard, Pronoun Resolution,
 * Entity Stack, Topic Depth, Recovery, Question Diversity, Topic Transition.
 *
 * <p>State is backend-owned. Only the current snapshot is injected into the prompt builder.
 */
@Service
public class DialogueEngineService {

    private static final double CLARIFY_THRESHOLD = 0.60;
    private static final double PRONOUN_CONFIDENCE_THRESHOLD = 0.80;
    private static final int MIN_DEPTH_BEFORE_SWITCH = 4;
    private static final int MAX_ENTITY_STACK = 8;
    private static final int EXHAUST_AT_DEPTH = 6;
    private static final int SHORT_REPLY_RECOVERY = 2;

    private static final Pattern PRONOUN = Pattern.compile(
            "(それ|これ|あれ|彼|彼女|あの人|その人|この人|そっち|こっち|あっち)");
    private static final Pattern SHORT_ACK = Pattern.compile(
            "^[\\s　]*(はい|うん|ええ|そう|そうです|そうですね|わかりません|分かりません|"
                    + "知らない|わからない|分からない|yes|ok|okay|hmm+|…|\\.\\.\\.)[\\s　!！.。?？]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BYE = Pattern.compile(
            "さよなら|バイバイ|またね|じゃあね|失礼します|終わり|bye|goodbye|end",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GREETING = Pattern.compile(
            "こんにちは|こんばんは|おはよう|はじめまして|hello|hi\\b",
            Pattern.CASE_INSENSITIVE);
    /** Content-like tokens (JP 2–12 chars or Latin titles) */
    private static final Pattern ENTITY_TOKEN = Pattern.compile(
            "[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}ー]{2,16}|[A-Za-z][A-Za-z0-9\\-]{2,24}");
    private static final Set<String> STOP = Set.of(
            "です", "ます", "でした", "ですか", "ましょう", "これ", "それ", "あれ", "もの",
            "こと", "とき", "今日", "昨日", "明日", "好き", "ですよ", "ですね", "だよ",
            "だね", "から", "ので", "けど", "でも", "そして", "あと", "最近", "一番",
            "何", "なに", "誰", "どこ", "どう", "なぜ", "いつも", "ちょっと", "とても",
            "すごい", "面白い", "おもしろい", "楽しい", "うれしい", "疲れた", "はい",
            "うん", "そう", "私", "僕", "俺", "あなた", "みんな", "自分", "人", "話");

    private final ObjectMapper objectMapper;

    public DialogueEngineService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DialogueEngineState newState(FreeChatSettings settings) {
        DialogueEngineState s = new DialogueEngineState();
        s.setCurrentPhase(DialoguePhase.GREETING);
        s.setStateStartTurn(0);
        if (settings != null && settings.getFavoriteTopics() != null) {
            s.setFavoriteTopics(new ArrayList<>(settings.getFavoriteTopics()));
        }
        return s;
    }

    public DialogueEngineState parseState(String json, FreeChatSettings settings) {
        if (json == null || json.isBlank()) {
            return newState(settings);
        }
        try {
            DialogueEngineState s = objectMapper.readValue(json, DialogueEngineState.class);
            if (s.getCurrentPhase() == null) {
                s.setCurrentPhase(DialoguePhase.GREETING);
            }
            if (settings != null && settings.getFavoriteTopics() != null && !settings.getFavoriteTopics().isEmpty()) {
                s.setFavoriteTopics(new ArrayList<>(settings.getFavoriteTopics()));
            }
            return s;
        } catch (Exception e) {
            return newState(settings);
        }
    }

    public String serializeState(DialogueEngineState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Pre-LLM: update state from user turn + understanding; build dynamic prompt block.
     */
    public DialogueEngineResult process(
            DialogueEngineState state,
            String userMessage,
            List<ConversationMessage> history,
            FreeChatSettings settings,
            ConversationUnderstanding understanding) {

        DialogueEngineState s = state != null ? state : newState(settings);
        String msg = userMessage != null ? userMessage.trim() : "";
        List<ConversationMessage> hist = history != null ? history : List.of();

        s.setTurnIndex(s.getTurnIndex() + 1);
        s.setTurnsInCurrentPhase(s.getTurnsInCurrentPhase() + 1);

        // --- Recovery: short replies ---
        boolean shortAck = isShortReply(msg)
                || (understanding != null && understanding.getIntent() == ConversationUnderstanding.Intent.SHORT_ACK);
        if (shortAck) {
            s.setShortReplyCount(s.getShortReplyCount() + 1);
        } else {
            s.setShortReplyCount(0);
            s.setRecoveryStep(0);
        }

        // --- Entity stack + depth ---
        List<String> extracted = extractEntities(msg);
        updateEntityStack(s, extracted, msg);

        // --- Pronoun resolution ---
        PronounHit pronoun = resolvePronoun(msg, s, hist);
        if (pronoun != null) {
            s.setLastResolvedPronoun(pronoun.pronoun);
            s.setLastResolvedReferent(pronoun.referent);
            if (pronoun.confidence < PRONOUN_CONFIDENCE_THRESHOLD) {
                // low confidence  clarify
            } else if (pronoun.referent != null && !pronoun.referent.isBlank()) {
                touchEntity(s, pronoun.referent, "referent");
            }
        }

        // --- Hallucination / confidence merge ---
        double confidence = understanding != null ? understanding.getConfidence() : 0.85;
        if (pronoun != null && pronoun.confidence < confidence) {
            confidence = pronoun.confidence;
        }
        if (msg.length() <= 2 && !isShortReply(msg) && extracted.isEmpty()) {
            confidence = Math.min(confidence, 0.45);
        }
        // Ultra-short mystery token (e.g. しん？) with no stack match
        if (isOpaqueFragment(msg) && s.activeEntityName().isBlank()) {
            confidence = Math.min(confidence, 0.40);
        }

        boolean forceClarify = false;
        String clarifyReply = null;
        if (understanding != null && understanding.isForceClarify()
                && understanding.getClarificationReply() != null) {
            forceClarify = true;
            clarifyReply = understanding.getClarificationReply();
            confidence = Math.min(confidence, understanding.getConfidence());
        } else if (pronoun != null && pronoun.confidence < PRONOUN_CONFIDENCE_THRESHOLD
                && pronoun.candidates.size() >= 2) {
            forceClarify = true;
            clarifyReply = buildPronounClarify(pronoun);
            confidence = pronoun.confidence;
        } else if (confidence < CLARIFY_THRESHOLD && !shortAck
                && (understanding == null
                || understanding.getIntent() != ConversationUnderstanding.Intent.VOCAB_HELP)) {
            forceClarify = true;
            clarifyReply = buildLowConfidenceClarify(msg, s);
        }

        // --- Phase machine ---
        advancePhase(s, msg, shortAck, settings != null && settings.isConversationActive());

        // --- Question diversity: recommend next type ---
        QuestionType nextQ = recommendQuestionType(s, shortAck);
        s.getLastQuestionTypes().add(nextQ.name());
        while (s.getLastQuestionTypes().size() > 8) {
            s.getLastQuestionTypes().remove(0);
        }

        // --- Topic transition hint ---
        String transitionHint = buildTransitionHint(s, settings);

        // --- Recovery step advance when stuck ---
        if (s.getShortReplyCount() >= SHORT_REPLY_RECOVERY) {
            s.setLastRecoveryTurn(s.getTurnIndex());
            s.setRecoveryStep(Math.min(5, s.getRecoveryStep() + 1));
        }

        DialogueEngineResult result = new DialogueEngineResult();
        result.setState(s);
        result.setConfidence(confidence);
        result.setForceClarify(forceClarify);
        result.setClarificationReply(clarifyReply);
        result.setRecommendedQuestionType(nextQ.name());
        result.setTransitionHint(transitionHint);
        result.setPromptBlock(buildPromptBlock(s, nextQ, transitionHint, shortAck, confidence, pronoun));
        return result;
    }

    /**
     * Post-LLM: record what question style the AI actually used (best-effort).
     */
    public void afterAssistantReply(DialogueEngineState state, String assistantReply) {
        if (state == null || assistantReply == null || assistantReply.isBlank()) {
            return;
        }
        QuestionType detected = detectQuestionType(assistantReply);
        if (detected != null) {
            List<String> hist = state.getLastQuestionTypes();
            if (!hist.isEmpty()) {
                hist.set(hist.size() - 1, detected.name());
            } else {
                hist.add(detected.name());
            }
        }
        // Extract entities from AI too (for stack candidates)
        for (String e : extractEntities(assistantReply)) {
            state.getMentionedEntities().add(e);
        }
    }

    // ─── Phase machine ─────────────────────────────────────────────

    private void advancePhase(DialogueEngineState s, String msg, boolean shortAck, boolean active) {
        DialoguePhase phase = s.getCurrentPhase();
        if (phase == null) {
            phase = DialoguePhase.GREETING;
        }

        if (BYE.matcher(msg).find()) {
            setPhase(s, DialoguePhase.ENDING);
            return;
        }

        switch (phase) {
            case GREETING -> {
                if (GREETING.matcher(msg).find() || s.getTurnIndex() >= 1) {
                    setPhase(s, DialoguePhase.ICE_BREAKING);
                }
            }
            case ICE_BREAKING -> {
                if (s.getTurnsInCurrentPhase() >= 2 || !s.activeEntityName().isBlank()) {
                    if (!s.activeEntityName().isBlank()) {
                        setPhase(s, DialoguePhase.TOPIC_EXPLORATION);
                    } else {
                        setPhase(s, DialoguePhase.TOPIC_DISCOVERY);
                    }
                }
            }
            case TOPIC_DISCOVERY -> {
                if (!s.activeEntityName().isBlank()) {
                    setPhase(s, DialoguePhase.TOPIC_EXPLORATION);
                } else if (s.getTurnsInCurrentPhase() >= 4 && active) {
                    // stay in discovery but allow transition later
                }
            }
            case TOPIC_EXPLORATION -> {
                if (s.getTopicDepth() >= 5) {
                    setPhase(s, DialoguePhase.DEEP_DISCUSSION);
                } else if (s.isTopicExhausted()) {
                    setPhase(s, DialoguePhase.TOPIC_TRANSITION);
                } else if (s.getShortReplyCount() >= 4 && s.getTurnIndex() >= 12) {
                    setPhase(s, DialoguePhase.WRAP_UP);
                }
            }
            case DEEP_DISCUSSION -> {
                if (s.isTopicExhausted() || s.getTopicDepth() >= EXHAUST_AT_DEPTH) {
                    s.setTopicExhausted(true);
                    setPhase(s, DialoguePhase.TOPIC_TRANSITION);
                } else if (s.getShortReplyCount() >= 4 && s.getTurnIndex() >= 14) {
                    setPhase(s, DialoguePhase.WRAP_UP);
                }
            }
            case TOPIC_TRANSITION -> {
                if (!s.activeEntityName().isBlank() && s.getTurnsInCurrentPhase() >= 1
                        && !shortAck && s.getTopicDepth() < MIN_DEPTH_BEFORE_SWITCH) {
                    // new topic started
                    s.setTopicExhausted(false);
                    setPhase(s, DialoguePhase.TOPIC_EXPLORATION);
                } else if (s.getTurnsInCurrentPhase() >= 3 && shortAck) {
                    setPhase(s, DialoguePhase.WRAP_UP);
                }
            }
            case WRAP_UP -> {
                if (BYE.matcher(msg).find()) {
                    setPhase(s, DialoguePhase.ENDING);
                }
            }
            case ENDING -> {
                // stay
            }
        }
    }

    private void setPhase(DialogueEngineState s, DialoguePhase next) {
        if (s.getCurrentPhase() == next) {
            return;
        }
        s.setPreviousPhase(s.getCurrentPhase());
        s.setCurrentPhase(next);
        s.setStateStartTurn(s.getTurnIndex());
        s.setTurnsInCurrentPhase(0);
    }

    // ─── Entity stack ──────────────────────────────────────────────

    private void updateEntityStack(DialogueEngineState s, List<String> extracted, String msg) {
        if (extracted.isEmpty()) {
            // still deepen if user continues talking about active entity
            if (!isShortReply(msg) && !s.activeEntityName().isBlank()) {
                s.setTopicDepth(Math.min(EXHAUST_AT_DEPTH, s.getTopicDepth() + 1));
                s.setExploredNodes(s.getExploredNodes() + 1);
            }
            if (s.getTopicDepth() >= EXHAUST_AT_DEPTH) {
                s.setTopicExhausted(true);
            }
            return;
        }

        for (String name : extracted) {
            s.getMentionedEntities().add(name);
            // Already active  bump depth
            if (name.equalsIgnoreCase(s.activeEntityName())
                    || (s.getActiveTopic() != null && name.equalsIgnoreCase(s.getActiveTopic()))) {
                s.setTopicDepth(Math.min(EXHAUST_AT_DEPTH, s.getTopicDepth() + 1));
                s.setExploredNodes(s.getExploredNodes() + 1);
                DialogueEntity active = s.activeEntity();
                if (active != null) {
                    active.setLastMentionTurn(s.getTurnIndex());
                }
                continue;
            }
            // Already in stack  promote to active (trim after it)
            int idx = indexInStack(s, name);
            if (idx >= 0) {
                while (s.getEntityStack().size() > idx + 1) {
                    s.getEntityStack().remove(s.getEntityStack().size() - 1);
                }
                s.getEntityStack().get(idx).setLastMentionTurn(s.getTurnIndex());
                s.setActiveTopic(name);
                s.setTopicDepth(Math.max(s.getTopicDepth(), idx + 1));
                continue;
            }
            // New entity — push as child if we have room and not exhausted switch rules
            String parent = s.activeEntityName();
            int depth = s.getEntityStack().isEmpty() ? 0 : s.getEntityStack().size();
            if (s.getEntityStack().size() >= MAX_ENTITY_STACK) {
                // pop root-ward
                s.getEntityStack().remove(0);
                depth = s.getEntityStack().size();
            }
            // If depth rule: can introduce sibling/child freely under MIN; after deep, prefer child of active
            DialogueEntity node = new DialogueEntity(
                    guessEntityType(name), name, parent.isBlank() ? null : parent, depth, s.getTurnIndex());
            s.getEntityStack().add(node);
            s.setActiveTopic(name);
            s.setTopicDepth(Math.max(1, s.getTopicDepth() + 1));
            s.setExploredNodes(s.getExploredNodes() + 1);
            s.setTopicExhausted(false);
        }

        if (s.getTopicDepth() >= EXHAUST_AT_DEPTH) {
            s.setTopicExhausted(true);
        }
    }

    private void touchEntity(DialogueEngineState s, String name, String type) {
        if (name == null || name.isBlank()) {
            return;
        }
        int idx = indexInStack(s, name);
        if (idx >= 0) {
            s.getEntityStack().get(idx).setLastMentionTurn(s.getTurnIndex());
            while (s.getEntityStack().size() > idx + 1) {
                s.getEntityStack().remove(s.getEntityStack().size() - 1);
            }
            s.setActiveTopic(name);
            return;
        }
        String parent = s.activeEntityName();
        s.getEntityStack().add(new DialogueEntity(
                type, name, parent.isBlank() ? null : parent, s.getEntityStack().size(), s.getTurnIndex()));
        s.setActiveTopic(name);
        s.getMentionedEntities().add(name);
    }

    private int indexInStack(DialogueEngineState s, String name) {
        for (int i = 0; i < s.getEntityStack().size(); i++) {
            DialogueEntity e = s.getEntityStack().get(i);
            if (e.getName() != null && e.getName().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String guessEntityType(String name) {
        if (name == null) {
            return "topic";
        }
        if (name.endsWith("くん") || name.endsWith("さん") || name.endsWith("ちゃん")
                || name.endsWith("君") || name.length() <= 4) {
            return "character";
        }
        return "topic";
    }

    private List<String> extractEntities(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = ENTITY_TOKEN.matcher(text);
        while (m.find()) {
            String w = m.group();
            if (w.length() < 2) {
                continue;
            }
            if (STOP.contains(w) || STOP.contains(w.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // skip pure hiragana function-ish short words already filtered
            if (!out.contains(w)) {
                out.add(w);
            }
            if (out.size() >= 5) {
                break;
            }
        }
        return out;
    }

    // ─── Pronoun resolution ────────────────────────────────────────

    private static final class PronounHit {
        String pronoun;
        String referent;
        double confidence;
        List<String> candidates = new ArrayList<>();
    }

    private PronounHit resolvePronoun(String msg, DialogueEngineState s, List<ConversationMessage> hist) {
        if (msg == null || msg.isBlank()) {
            return null;
        }
        Matcher m = PRONOUN.matcher(msg);
        if (!m.find()) {
            return null;
        }
        PronounHit hit = new PronounHit();
        hit.pronoun = m.group(1);

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        // Prefer entity stack (leaf first)
        for (int i = s.getEntityStack().size() - 1; i >= 0; i--) {
            DialogueEntity e = s.getEntityStack().get(i);
            if (e.getName() != null && !e.getName().isBlank()) {
                candidates.add(e.getName());
            }
        }
        // Recent messages entities
        List<ConversationMessage> tail = hist.size() > 10 ? hist.subList(hist.size() - 10, hist.size()) : hist;
        for (int i = tail.size() - 1; i >= 0; i--) {
            for (String e : extractEntities(tail.get(i).getContent())) {
                candidates.add(e);
            }
        }
        hit.candidates = new ArrayList<>(candidates);
        if (hit.candidates.isEmpty()) {
            hit.confidence = 0.35;
            hit.referent = null;
            return hit;
        }
        // 彼/彼女  prefer character-typed or shorter person-like names
        if ("彼".equals(hit.pronoun) || "彼女".equals(hit.pronoun) || hit.pronoun.contains("人")) {
            String best = null;
            for (String c : hit.candidates) {
                if (c.endsWith("くん") || c.endsWith("さん") || c.endsWith("ちゃん") || c.length() <= 5) {
                    best = c;
                    break;
                }
            }
            hit.referent = best != null ? best : hit.candidates.get(0);
            hit.confidence = best != null ? 0.88 : 0.55;
        } else {
            // これ/それ/あれ  most recent active entity
            hit.referent = hit.candidates.get(0);
            hit.confidence = hit.candidates.size() == 1 ? 0.92 : 0.72;
            if (hit.candidates.size() >= 3) {
                hit.confidence = 0.55;
            }
        }
        return hit;
    }

    private String buildPronounClarify(PronounHit p) {
        String a = p.candidates.get(0);
        String b = p.candidates.size() > 1 ? p.candidates.get(1) : "別のもの";
        return "ごめんなさい、確認させてください。「" + p.pronoun + "」は"
                + a + "のことですか？それとも" + b + "のことですか？";
    }

    private String buildLowConfidenceClarify(String msg, DialogueEngineState s) {
        if (isOpaqueFragment(msg)) {
            return "すみません、「" + msg.replaceAll("[\\s　?？!！.。]+", "")
                    + "」はどういう意味ですか？もう少し教えてください。";
        }
        if (!s.activeEntityName().isBlank()) {
            return "ごめんなさい、確認させてください。"
                    + s.activeEntityName() + "の話で合っていますか？";
        }
        return "すみません、もう少し具体的に教えてください。何について話していますか？";
    }

    private static boolean isOpaqueFragment(String msg) {
        if (msg == null) {
            return false;
        }
        String t = msg.replaceAll("[\\s　?？!！.。…]+", "");
        return t.length() >= 1 && t.length() <= 4
                && !SHORT_ACK.matcher(msg).matches()
                && !GREETING.matcher(msg).find();
    }

    private static boolean isShortReply(String msg) {
        if (msg == null || msg.isBlank()) {
            return true;
        }
        if (SHORT_ACK.matcher(msg).matches()) {
            return true;
        }
        String t = msg.replaceAll("[\\s　!！.。?？]+", "");
        return t.length() <= 2;
    }

    // ─── Question diversity ────────────────────────────────────────

    private QuestionType recommendQuestionType(DialogueEngineState s, boolean shortAck) {
        if (shortAck && s.getShortReplyCount() >= SHORT_REPLY_RECOVERY) {
            // Recovery flow: reaction  share  story  question  suggest  change
            return switch (s.getRecoveryStep()) {
                case 0, 1 -> QuestionType.REACTION;
                case 2 -> QuestionType.SHARE;
                case 3 -> QuestionType.OPINION;
                case 4 -> QuestionType.FEELING;
                default -> QuestionType.RECOMMENDATION;
            };
        }
        List<String> recent = s.getLastQuestionTypes();
        List<QuestionType> rotation = List.of(
                QuestionType.FACT,
                QuestionType.FEELING,
                QuestionType.CHOICE,
                QuestionType.HYPOTHETICAL,
                QuestionType.MEMORY,
                QuestionType.OPINION,
                QuestionType.COMPARISON,
                QuestionType.RECOMMENDATION);
        // Avoid repeating last type; avoid 3x same family
        String last = recent.isEmpty() ? "" : recent.get(recent.size() - 1);
        for (QuestionType t : rotation) {
            if (!t.name().equals(last)) {
                // skip if last 2 were both this
                if (recent.size() >= 2
                        && t.name().equals(recent.get(recent.size() - 1))
                        && t.name().equals(recent.get(recent.size() - 2))) {
                    continue;
                }
                return t;
            }
        }
        return QuestionType.FEELING;
    }

    private QuestionType detectQuestionType(String reply) {
        if (reply.contains("どっち") || reply.contains("どちら")) {
            return QuestionType.CHOICE;
        }
        if (reply.contains("もし") || reply.contains("だったら")) {
            return QuestionType.HYPOTHETICAL;
        }
        if (reply.contains("覚えて") || reply.contains("子どもの") || reply.contains("初めて")) {
            return QuestionType.MEMORY;
        }
        if (reply.contains("おすすめ") || reply.contains("勧める")) {
            return QuestionType.RECOMMENDATION;
        }
        if (reply.contains("どうして") || reply.contains("なぜ") || reply.contains("理由")) {
            return QuestionType.OPINION;
        }
        if (reply.contains("気持ち") || reply.contains("感じ") || reply.contains("好き")) {
            return QuestionType.FEELING;
        }
        if (reply.contains("と") && (reply.contains("どっち") || reply.contains("比べ"))) {
            return QuestionType.COMPARISON;
        }
        if (reply.contains("？") || reply.contains("?")) {
            return QuestionType.FACT;
        }
        return QuestionType.REACTION;
    }

    // ─── Topic transition ──────────────────────────────────────────

    private String buildTransitionHint(DialogueEngineState s, FreeChatSettings settings) {
        if (s.getCurrentPhase() != DialoguePhase.TOPIC_TRANSITION
                && s.getCurrentPhase() != DialoguePhase.WRAP_UP
                && !s.isTopicExhausted()
                && s.getTopicDepth() < MIN_DEPTH_BEFORE_SWITCH) {
            return "Stay on current entity/topic. Depth=" + s.getTopicDepth()
                    + " (<" + MIN_DEPTH_BEFORE_SWITCH + "): do NOT change topic.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Topic transition priority:\n");
        sb.append("1) Current entity: ").append(blank(s.activeEntityName(), "(none)")).append('\n');
        sb.append("2) Sibling / parent: ").append(blank(s.parentEntityName(), "(none)")).append('\n');
        if (settings != null && settings.getFavoriteTopics() != null && !settings.getFavoriteTopics().isEmpty()) {
            sb.append("3) Favorite topics: ").append(String.join(", ", settings.getFavoriteTopics())).append('\n');
        } else {
            sb.append("3) Favorite topics: (none selected)\n");
        }
        sb.append("4) Related everyday topic only after related ones are exhausted.\n");
        sb.append("Use natural phrases: そういえば… / ちなみに… / ところで… / それで思い出したんですが…\n");
        sb.append("Never jump to an unrelated domain (e.g. Horimiya  Travel) without bridge.");
        return sb.toString();
    }

    // ─── Prompt block ──────────────────────────────────────────────

    private String buildPromptBlock(
            DialogueEngineState s,
            QuestionType nextQ,
            String transitionHint,
            boolean shortAck,
            double confidence,
            PronounHit pronoun) {

        // Compact snapshot for token limits (rules live in FreeChatPromptComposer compact blocks)
        StringBuilder sb = new StringBuilder();
        sb.append("# Engine\n");
        sb.append("phase=").append(s.getCurrentPhase())
                .append(" turn=").append(s.getTurnIndex())
                .append(" conf=").append(String.format(Locale.ROOT, "%.0f%%", confidence * 100))
                .append('\n');
        sb.append(phaseGoal(s.getCurrentPhase())).append('\n');
        sb.append("entity=").append(blank(s.activeEntityName(), "-"))
                .append(" parent=").append(blank(s.parentEntityName(), "-"))
                .append(" depth=").append(s.getTopicDepth())
                .append(" exhausted=").append(s.isTopicExhausted())
                .append('\n');
        if (!s.getEntityStack().isEmpty()) {
            sb.append("stack=");
            for (int i = 0; i < s.getEntityStack().size(); i++) {
                if (i > 0) sb.append('>');
                sb.append(s.getEntityStack().get(i).getName());
            }
            sb.append('\n');
        }
        if (pronoun != null && pronoun.referent != null) {
            sb.append("pronoun ").append(pronoun.pronoun).append("").append(pronoun.referent)
                    .append(" (").append(String.format(Locale.ROOT, "%.0f%%", pronoun.confidence * 100)).append(")\n");
        }
        if (shortAck || s.getShortReplyCount() >= SHORT_REPLY_RECOVERY) {
            sb.append("recovery shortReplies=").append(s.getShortReplyCount())
                    .append(" step=").append(s.getRecoveryStep())
                    .append("  react/share more, fewer questions\n");
        }
        sb.append("nextMove=").append(nextQ.name()).append(" | ").append(questionTypeGuide(nextQ)).append('\n');
        if (s.getTopicDepth() < MIN_DEPTH_BEFORE_SWITCH && !s.isTopicExhausted()) {
            sb.append("RULE: depth<").append(MIN_DEPTH_BEFORE_SWITCH).append(" stay on entity\n");
        } else if (transitionHint != null && !transitionHint.isBlank()) {
            // one-line transition only
            String one = transitionHint.replace('\n', ' ');
            if (one.length() > 180) one = one.substring(0, 180);
            sb.append(one).append('\n');
        }
        sb.append("No invent meanings/facts. Clarify if unsure.\n");
        return sb.toString();
    }

    private static String phaseGoal(DialoguePhase phase) {
        return switch (phase) {
            case GREETING -> "Goal: warm greeting only. Do not jump into deep topics yet.";
            case ICE_BREAKING -> "Goal: light small talk; open a door to interests.";
            case TOPIC_DISCOVERY -> "Goal: find a concrete interest/entity the user wants to talk about.";
            case TOPIC_EXPLORATION -> "Goal: deepen the current topic. Do not introduce unrelated topics. Avoid ending.";
            case DEEP_DISCUSSION -> "Goal: go deeper (feelings, scenes, reasons) on the active entity.";
            case TOPIC_TRANSITION -> "Goal: leave exhausted topic via RELATED bridge; use natural transition phrases.";
            case WRAP_UP -> "Goal: gently wrap up; soft closing possible; do not force new deep topics.";
            case ENDING -> "Goal: say goodbye naturally. Short warm closing.";
        };
    }

    private static String questionTypeGuide(QuestionType t) {
        return switch (t) {
            case FACT -> "Ask one concrete fact question (what/who/when).";
            case OPINION -> "Ask for a simple opinion/reason (どうして？).";
            case FEELING -> "Ask about feeling / favorite part (どんなところが好き？).";
            case CHOICE -> "Offer a concrete A/B choice (どっち？).";
            case MEMORY -> "Ask a light memory question (初めて見たのは？).";
            case COMPARISON -> "Compare two concrete things from context.";
            case HYPOTHETICAL -> "One light hypothetical only if level allows.";
            case RECOMMENDATION -> "Ask or give a soft recommendation.";
            case REACTION -> "React only — no question required this turn.";
            case SHARE -> "Share a short opinion/experience, then optionally one soft follow-up.";
        };
    }

    private static String blank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
