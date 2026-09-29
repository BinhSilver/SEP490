package com.jlas.kaiwa.service.understanding;

import com.jlas.kaiwa.model.ConversationMessage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Pre-LLM Conversation Understanding layer (rule-based).
 * Runs before Prompt Builder so free-chat does not rely on the model alone
 * for ambiguity and vocabulary-help context.
 *
 * <pre>
 * STT / user text
 *    Intent / Ambiguity / Vocab-help / Context resolver
 *    Prompt Builder
 *    LLM
 * </pre>
 */
@Service
public class ConversationUnderstandingService {

    private static final int RECENT_AI_LIMIT = 5;

    /** 〇〇は何ですか / って何 / nghĩa là gì / what is */
    private static final Pattern VOCAB_Q = Pattern.compile(
            "(?i)^[\\s　]*"
                    + "(.+?)"
                    + "(?:\\s*(?:は|って|とは|というの|っていうの))?"
                    + "\\s*(?:"
                    + "何(?:ですか|でしょう|なの|だ)?"
                    + "|なに(?:ですか|でしょう)?"
                    + "|意味(?:は)?(?:何|なに|ですか)?"
                    + "|nghĩa\\s*là\\s*gì"
                    + "|là\\s*gì"
                    + "|what\\s+is"
                    + "|是什么意思"
                    + "|是什麼意思"
                    + ")"
                    + "[\\s　\\?？!！.。]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Short echo: シーン？ / シーン */
    private static final Pattern SHORT_ECHO = Pattern.compile(
            "^[\\s　]*([\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}A-Za-zー]{1,24})[\\s　\\?？!！.。]*$");

    /** Preference ambiguity after どっち question */
    private static final Pattern PREF_AMBIG = Pattern.compile(
            "^[\\s　]*(?:俺|おれ|僕|ぼく|私|わたし|あたし)?(?:が|は)?好き(?:です|だ|ですよ)?[\\s　!！.。]*$");

    private static final Pattern CHOICE_Q = Pattern.compile("どっち|どちら");

    private static final Pattern GREETING_LINE = Pattern.compile(
            "^[\\s　]*(こんにちは|こんばんは|おはよう(ございます)?|はじめまして|"
                    + "ありがとう(ございます)?|さようなら|hello|hi)[\\s　!！.。]*$",
            Pattern.CASE_INSENSITIVE);

    /** AとB (だったら / 、) */
    private static final Pattern A_TO_B = Pattern.compile(
            "([\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}A-Za-zー々〆〤]{1,20}?)と"
                    + "([\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}A-Za-zー々〆〤]{1,20}?)"
                    + "(?:だったら|なら|、|の|どっち|どちら)");

    public ConversationUnderstanding analyze(String userMessage, List<ConversationMessage> history) {
        ConversationUnderstanding u = new ConversationUnderstanding();
        String msg = userMessage == null ? "" : userMessage.trim();
        List<ConversationMessage> hist = history != null ? history : List.of();

        String lastAi = lastAssistant(hist);
        u.setLastAssistantMessage(lastAi);
        List<String> recentAiTexts = recentAssistantTexts(hist, RECENT_AI_LIMIT);
        u.setRecentAiTerms(extractTerms(recentAiTexts));

        // 1) Vocabulary help + context resolver (highest priority)
        VocabHit vocab = detectVocabHelp(msg, recentAiTexts);
        if (vocab != null) {
            u.setIntent(ConversationUnderstanding.Intent.VOCAB_HELP);
            u.setVocabTarget(vocab.word);
            u.setVocabFromRecentAi(vocab.fromRecentAi);
            u.setVocabContextSnippet(vocab.snippet);
            u.setConfidence(vocab.fromRecentAi ? 0.95 : 0.7);
            u.setPromptBlock(buildVocabHelpBlock(vocab));
            return u;
        }

        // 2) Ambiguity: vague "I like" after A/B choice question
        AmbiguityHit amb = detectPreferenceAmbiguity(msg, lastAi);
        if (amb != null) {
            u.setIntent(ConversationUnderstanding.Intent.AMBIGUOUS);
            u.setConfidence(amb.confidence);
            u.setAmbiguityOptions(amb.options);
            u.setForceClarify(true);
            u.setClarificationReply(amb.clarificationJa);
            u.setPromptBlock(buildAmbiguityBlock(amb));
            return u;
        }

        // 3) Short ack
        if (isShortAck(msg)) {
            u.setIntent(ConversationUnderstanding.Intent.SHORT_ACK);
            u.setConfidence(0.9);
            u.setPromptBlock("""
                    # Understanding: Short acknowledgment

                    User gave a very short reply (はい/うん/そうです/…).
                    Do not interrogate. React lightly or share, then one soft concrete follow-up
                    on the CURRENT topic/entity only.
                    """);
            return u;
        }

        // 4) Default chat — still pass recent AI context for resolver
        u.setIntent(ConversationUnderstanding.Intent.CHAT);
        u.setConfidence(0.85);
        u.setPromptBlock(buildDefaultContextBlock(lastAi, u.getRecentAiTerms()));
        return u;
    }

    private static final Set<String> NOT_VOCAB = Set.of(
            "こんにちは", "こんばんは", "おはよう", "おはようございます", "はじめまして",
            "ありがとう", "ありがとうございます", "さようなら", "バイバイ",
            "元気ですか", "元気です", "はい", "うん", "ええ", "そうです", "そうですね",
            "分かりません", "わかりません", "知らない", "hello", "hi", "ok", "okay", "yes",
            "こんにち", "こんばん", "おはようござい");

    private VocabHit detectVocabHelp(String msg, List<String> recentAiTexts) {
        if (msg.isBlank()) return null;

        // Full greetings / short social replies are never vocabulary questions
        String bare = msg.replaceAll("[\\s　!！.。?？…]+", "");
        if (NOT_VOCAB.contains(bare) || NOT_VOCAB.contains(bare.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (GREETING_LINE.matcher(msg).matches()) {
            return null;
        }

        String candidate = null;
        boolean explicitAsk = false;
        Matcher m = VOCAB_Q.matcher(msg);
        if (m.matches()) {
            candidate = cleanWord(m.group(1));
            explicitAsk = true;
        } else {
            // Short echo only if user asks with ？ and token is short content word (not a greeting phrase)
            Matcher echo = SHORT_ECHO.matcher(msg);
            if (echo.matches() && (msg.contains("？") || msg.contains("?"))) {
                String w = cleanWord(echo.group(1));
                if (w != null && w.length() <= 8 && !NOT_VOCAB.contains(w)
                        && appearsIn(w, recentAiTexts)) {
                    candidate = w;
                }
            }
        }
        if (candidate == null || candidate.isBlank()) return null;
        if (NOT_VOCAB.contains(candidate)) return null;
        // Ignore ultra-generic
        if (candidate.length() == 1 && !isKanjiOrKanaWord(candidate)) return null;
        if (List.of("これ", "それ", "あれ", "何", "なに", "誰", "どこ").contains(candidate)) {
            // only when explicit 〜は何ですか style
            if (!explicitAsk) return null;
            String fromAi = lastContentWord(recentAiTexts);
            if (fromAi != null) {
                VocabHit hit = new VocabHit();
                hit.word = fromAi;
                hit.fromRecentAi = true;
                hit.snippet = findSnippet(fromAi, recentAiTexts);
                return hit;
            }
        }

        VocabHit hit = new VocabHit();
        hit.word = candidate;
        hit.fromRecentAi = appearsIn(candidate, recentAiTexts);
        hit.snippet = findSnippet(candidate, recentAiTexts);
        return hit;
    }

    private AmbiguityHit detectPreferenceAmbiguity(String msg, String lastAi) {
        if (msg.isBlank() || lastAi == null || lastAi.isBlank()) return null;
        if (!CHOICE_Q.matcher(lastAi).find()) return null;
        if (!PREF_AMBIG.matcher(msg).matches()) return null;

        // User did not name either option explicitly
        List<String> options = extractChoiceOptions(lastAi);
        if (options.size() < 2) return null;
        for (String opt : options) {
            if (msg.contains(opt.replace("くん", "").replace("さん", "").replace("ちゃん", ""))) {
                return null; // sufficiently specific
            }
        }

        AmbiguityHit hit = new AmbiguityHit();
        hit.confidence = 0.42;
        hit.options = options;
        String a = options.get(0);
        String b = options.get(1);
        hit.clarificationJa = "ごめんなさい、確認させてください。"
                + a + "が好き、という意味ですか？"
                + "それとも" + b + "が好き、という意味ですか？"
                + "それとも「自分が好き」という意味ですか？";
        return hit;
    }

    private List<String> extractChoiceOptions(String lastAi) {
        List<String> opts = new ArrayList<>();
        Matcher m = A_TO_B.matcher(lastAi);
        if (m.find()) {
            String a = cleanWord(m.group(1));
            String b = cleanWord(m.group(2));
            if (a != null) opts.add(a);
            if (b != null) opts.add(b);
        }
        return opts;
    }

    private String buildVocabHelpBlock(VocabHit vocab) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Conversation Understanding  VOCABULARY HELP\n\n");
        sb.append("Detected intent: user is asking the meaning of 「").append(vocab.word).append("」.\n");
        sb.append("Confidence: ").append(vocab.fromRecentAi ? "0.95 (from recent AI speech)" : "0.70").append("\n\n");
        if (vocab.fromRecentAi) {
            sb.append("CRITICAL CONTEXT RESOLVER:\n");
            sb.append("This word appeared in YOUR recent reply. User is asking about YOUR usage.\n");
            sb.append("Do NOT invent unrelated dictionary senses (e.g. car brand \"Sedan\" for シーン).\n");
            if (vocab.snippet != null && !vocab.snippet.isBlank()) {
                sb.append("Recent AI context: ").append(vocab.snippet).append("\n");
            }
            sb.append("\n");
        }
        sb.append("""
                Required behavior:
                1) Explain 「%s」 simply in the correction fields:
                   - hasError can be false (not a user mistake)
                   - Still set originalWrong = the word
                   - corrected = short Vietnamese meaning
                   - grammarReason = Vietnamese example + tip
                   - mistakeType = "vocab_help"
                2) reply = natural Japanese: give a very short JP paraphrase if needed,
                   then CONTINUE the previous conversation topic with one concrete question.
                3) Never change topic to an unrelated meaning of the word.
                """.formatted(vocab.word));
        return sb.toString();
    }

    private String buildAmbiguityBlock(AmbiguityHit amb) {
        return """
                # Conversation Understanding  AMBIGUITY (LOW CONFIDENCE)

                Confidence about user intent is only %.0f%% (< 60%% threshold).

                Possible interpretations include:
                %s

                HARD RULE:
                - Do NOT assume one meaning and continue the story.
                - Ask a short polite clarification in Japanese (one question).
                - Offer the likely options explicitly.
                - hasError=false unless the sentence form itself is also broken.

                Preferred clarification style:
                ごめんなさい、確認させてください。Aという意味ですか？それともB？
                """.formatted(
                amb.confidence * 100,
                String.join("\n", amb.options.stream().map(o -> "- " + o).toList()));
    }

    private String buildDefaultContextBlock(String lastAi, List<String> terms) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Conversation Understanding  CHAT\n\n");
        sb.append("Context resolver: prefer immediate conversation context over general knowledge.\n");
        if (lastAi != null && !lastAi.isBlank()) {
            sb.append("Last AI message: ").append(truncate(lastAi, 160)).append("\n");
        }
        if (terms != null && !terms.isEmpty()) {
            sb.append("Recent AI keywords: ").append(String.join(", ", terms.stream().limit(12).toList())).append("\n");
        }
        sb.append("""

                Ambiguity rule (always):
                If multiple interpretations of the user message are plausible and you are not highly confident,
                ask a brief clarification instead of guessing.
                Only auto-infer meaning when highly confident (e.g. clear collocation fix like ケーキを飲みたい  食べたい).
                """);
        return sb.toString();
    }

    private static boolean isShortAck(String msg) {
        String t = msg.replaceAll("[\\s　!！.。?？]+", "");
        return List.of("はい", "うん", "ええ", "そうです", "そう", "わかりません", "分かりません",
                "知らない", "わからない", "分からない", "yes", "ok", "okay").contains(t.toLowerCase(Locale.ROOT));
    }

    private static String lastAssistant(List<ConversationMessage> hist) {
        for (int i = hist.size() - 1; i >= 0; i--) {
            ConversationMessage m = hist.get(i);
            if (m != null && "assistant".equals(m.getRole()) && m.getContent() != null) {
                return m.getContent();
            }
        }
        return "";
    }

    private static List<String> recentAssistantTexts(List<ConversationMessage> hist, int limit) {
        List<String> all = new ArrayList<>();
        for (ConversationMessage m : hist) {
            if (m != null && "assistant".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                all.add(m.getContent());
            }
        }
        if (all.size() <= limit) return all;
        return all.subList(all.size() - limit, all.size());
    }

    private static boolean appearsIn(String word, List<String> texts) {
        if (word == null || word.isBlank()) return false;
        for (String t : texts) {
            if (t != null && t.contains(word)) return true;
        }
        return false;
    }

    private static String findSnippet(String word, List<String> texts) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            String t = texts.get(i);
            if (t != null && t.contains(word)) {
                return truncate(t, 120);
            }
        }
        return "";
    }

    private static List<String> extractTerms(List<String> texts) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Pattern p = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}ー]{2,12}");
        for (String t : texts) {
            if (t == null) continue;
            Matcher m = p.matcher(t);
            while (m.find()) {
                String w = m.group();
                if (w.length() >= 2) terms.add(w);
            }
        }
        return new ArrayList<>(terms);
    }

    private static String lastContentWord(List<String> texts) {
        if (texts == null || texts.isEmpty()) return null;
        String last = texts.get(texts.size() - 1);
        List<String> terms = extractTerms(List.of(last));
        // prefer longer content-like tokens near the end
        for (int i = terms.size() - 1; i >= 0; i--) {
            String w = terms.get(i);
            if (!List.of("です", "ます", "でした", "ですか", "ましょう").contains(w)) {
                return w;
            }
        }
        return terms.isEmpty() ? null : terms.get(terms.size() - 1);
    }

    private static String cleanWord(String raw) {
        if (raw == null) return null;
        String w = raw.trim()
                .replaceAll("^[\\s　「『\"']+", "")
                .replaceAll("[\\s　」』\"'。.?？!！]+$", "");
        // strip trailing particles often glued
        w = w.replaceAll("(は|が|を|に|で|と|も|の)$", "");
        return w.isBlank() ? null : w;
    }

    private static boolean isKanjiOrKanaWord(String w) {
        return w.codePoints().anyMatch(cp -> {
            Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
            return b == Character.UnicodeBlock.HIRAGANA
                    || b == Character.UnicodeBlock.KATAKANA
                    || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS;
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static final class VocabHit {
        String word;
        boolean fromRecentAi;
        String snippet;
    }

    private static final class AmbiguityHit {
        double confidence;
        List<String> options = new ArrayList<>();
        String clarificationJa;
    }
}
