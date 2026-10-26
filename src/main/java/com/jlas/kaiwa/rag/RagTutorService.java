package com.jlas.kaiwa.rag;

import com.jlas.kaiwa.service.ai.LocalLlmClient;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Trả lời một câu hỏi có nguồn từ giáo trình J-LAS, bằng model trên máy chủ AI.
 * Dùng cho chatbot gia sư của người học và cho API đánh giá của ADMIN.
 */
@Service
public class RagTutorService {

    private static final int MAX_ANSWER_TOKENS = 700;
    /** Câu hỏi ngắn sau một lượt trước (ví dụ "cho thêm ví dụ") được tìm nguồn kèm câu hỏi trước đó. */
    private static final int FOLLOW_UP_CHARS = 40;
    private static final Pattern GRAMMAR_QUESTION = Pattern.compile("\\b(ngu phap|mau|cau truc|cach dung|grammar|bunpou?)\\b");
    private static final Pattern KANJI_QUESTION = Pattern.compile("\\b(han viet|kanji|chu han|tung chu|am on|am kun|onyomi|kunyomi|bo thu)\\b");

    /** Không có nguồn nào: trả lời cố định, không gọi model để model không tự bịa. */
    static final String NO_SOURCE = "Tài liệu J-LAS chưa có nội dung cho câu hỏi này, nên mình không trả lời để tránh nói sai. "
            + "Bạn thử hỏi về bài đang học, hoặc viết từ hay mẫu ngữ pháp bằng chữ Nhật nhé.";

    private final RagRetriever retriever;
    private final LocalLlmClient llm;

    public RagTutorService(RagRetriever retriever, LocalLlmClient llm) {
        this.retriever = retriever;
        this.llm = llm;
    }

    public record Answer(String answer, List<RagRetriever.Passage> passages, RagLexiconLookup.Result lexicon,
                         List<RagPromptComposer.Citation> citations, long retrievalMs, long generationMs) { }

    public Answer ask(String question) {
        return ask(question, List.of());
    }

    /** @param history các lượt trước trong cùng cuộc trò chuyện (role user/assistant), đã được giới hạn độ dài */
    public Answer ask(String question, List<Map<String, String>> history) {
        long started = System.nanoTime();
        List<RomajiKana.Hint> hints = RomajiKana.hints(question);
        RagRetriever.Context context = withoutCoveredKanji(retriever.retrieve(retrievalQuery(question, hints, history), null), question);
        long retrieved = System.nanoTime();
        long retrievalMs = (retrieved - started) / 1_000_000;
        String fixed = context.isEmpty() ? NO_SOURCE
                : !hints.isEmpty() && isGrammarQuestion(question) && notInSources(hints, context) ? unknownPattern(hints) : null;
        if (fixed != null) return new Answer(fixed, context.passages(), context.lexicon(), List.of(), retrievalMs, 0);
        String answer = llm.chat(RagPromptComposer.systemPrompt(context, hints), history, question, MAX_ANSWER_TOKENS);
        long done = System.nanoTime();
        return new Answer(answer, context.passages(), context.lexicon(), RagPromptComposer.citations(context),
                retrievalMs, (done - retrieved) / 1_000_000);
    }

    /** Câu hỏi kèm chữ kana đổi từ romaji; câu hỏi nối tiếp ngắn thì kèm câu hỏi trước của người học. */
    static String retrievalQuery(String question, List<RomajiKana.Hint> hints, List<Map<String, String>> history) {
        List<String> parts = new ArrayList<>();
        if (question.codePointCount(0, question.length()) < FOLLOW_UP_CHARS) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).get("role"))) {
                    parts.add(history.get(i).get("content"));
                    break;
                }
            }
        }
        parts.add(question);
        hints.forEach(h -> parts.add(h.kana()));
        return String.join("\n", parts);
    }

    static boolean isGrammarQuestion(String question) {
        return GRAMMAR_QUESTION.matcher(plain(question)).find();
    }

    /**
     * Bỏ kanji đã nằm trong một từ tìm được, trừ khi người học hỏi về chính chữ kanji (Hán Việt, On/Kun).
     * Model 4B đã suy nghĩa của 学校 từ một nghĩa riêng của 校 dù nguồn có sẵn từ 学校.
     */
    static RagRetriever.Context withoutCoveredKanji(RagRetriever.Context context, String question) {
        RagLexiconLookup.Result lexicon = context.lexicon();
        if (lexicon.words().isEmpty() || KANJI_QUESTION.matcher(plain(question)).find()) return context;
        List<RagLexiconLookup.KanjiEntry> kanji = lexicon.kanji().stream()
                .filter(k -> lexicon.words().stream().noneMatch(w -> w.expression().contains(k.character()))).toList();
        return kanji.size() == lexicon.kanji().size() ? context
                : new RagRetriever.Context(context.passages(), new RagLexiconLookup.Result(lexicon.words(), kanji));
    }

    private static String plain(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd');
    }

    /** Không chữ kana nào đổi từ romaji xuất hiện trong nguồn tìm được: giáo trình không nói về mẫu đó. */
    static boolean notInSources(List<RomajiKana.Hint> hints, RagRetriever.Context context) {
        return hints.stream().map(RomajiKana.Hint::kana).noneMatch(kana ->
                context.passages().stream().anyMatch(p -> p.text().contains(kana) || p.title().contains(kana))
                        || context.lexicon().words().stream().anyMatch(w -> w.expression().contains(kana)
                        || (w.reading() != null && w.reading().contains(kana))));
    }

    static String unknownPattern(List<RomajiKana.Hint> hints) {
        String read = hints.stream().map(h -> "\"" + h.romaji() + "\" là 「" + h.kana() + "」").collect(Collectors.joining(", "));
        return "Tài liệu J-LAS chưa có mẫu này. Mình đọc " + read + " nhưng không thấy trong giáo trình, nên mình không giải thích"
                + " để tránh nói sai. Bạn kiểm tra lại cách gõ, hoặc viết mẫu bằng chữ Nhật (ví dụ: ～てもいい) rồi hỏi lại nhé.";
    }
}
