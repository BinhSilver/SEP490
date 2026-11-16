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
    private static final Pattern KANJI_QUESTION =
            Pattern.compile("\\b(han viet|kanji|chu han|tung chu|am on|am kun|on ?/? ?kun|kun|onyomi|kunyomi|bo thu)\\b");
    private static final Pattern HAN_VIET_QUESTION = Pattern.compile("\\bhan viet\\b");
    private static final Pattern HEALTH_QUESTION = Pattern.compile(
            "\\b(bi dau|dau dau|dau bung|dau hong|uong thuoc|thuoc gi|bi sot|bi benh|bi om|bi ho|cam cum|bac si|duoc si|kham benh)\\b");

    /** Không có nguồn nào: trả lời cố định, không gọi model để model không tự bịa. */
    static final String NO_SOURCE = "Tài liệu J-LAS chưa có nội dung cho câu hỏi này, nên mình không trả lời để tránh nói sai. "
            + "Bạn thử hỏi về bài đang học, hoặc viết từ hay mẫu ngữ pháp bằng chữ Nhật nhé.";
    static final String SEE_A_DOCTOR = " Nếu bạn đang không khỏe, hãy hỏi bác sĩ hoặc dược sĩ nhé.";

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
        RagRetriever.Context context = kanjiOnlyWhenNeeded(retriever.retrieve(retrievalQuery(question, hints, history), null), question);
        long retrieved = System.nanoTime();
        long retrievalMs = (retrieved - started) / 1_000_000;
        if (!context.lexicon().kanji().isEmpty() && HAN_VIET_QUESTION.matcher(plain(question)).find()) {
            return new Answer(hanVietAnswer(context.lexicon()), context.passages(), context.lexicon(),
                    RagPromptComposer.citations(context), retrievalMs, 0);
        }
        String fixed = context.isEmpty() ? NO_SOURCE + (HEALTH_QUESTION.matcher(plain(question)).find() ? SEE_A_DOCTOR : "")
                : !hints.isEmpty() && isGrammarQuestion(question) && notInSources(hints, context) ? unknownPattern(hints) : null;
        if (fixed != null) return new Answer(fixed, context.passages(), context.lexicon(), List.of(), retrievalMs, 0);
        String answer = stripRomaji(llm.chat(RagPromptComposer.systemPrompt(context, hints), history, question, MAX_ANSWER_TOKENS));
        long done = System.nanoTime();
        return new Answer(answer, context.passages(), context.lexicon(), RagPromptComposer.citations(context),
                retrievalMs, (done - retrieved) / 1_000_000);
    }

    private static final Pattern JAPANESE_TEXT = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    private static final Pattern ROMAJI_PARENS = Pattern.compile(
            "\\s*[(（]\\s*\\*?(?:(?:Hiragana|Romaji|Phiên âm|Đọc là)\\s*:\\s*)?\\*?[A-Za-zāīūēōâîûêôĀĪŪĒŌ][A-Za-zāīūēōâîûêôĀĪŪĒŌ' .,\\-]{5,}\\*?\\s*[)）]");

    /**
     * Bỏ phần phiên âm romaji trong ngoặc mà model 4B vẫn tự thêm dù prompt cấm (đo được: "owawarete", "Hát-pa-tô").
     * ponytail: chỉ bắt ngoặc toàn chữ Latin không dấu tiếng Việt, dài từ 6 ký tự, trên dòng có chữ Nhật; romaji ngoài ngoặc
     * không bắt được, cần thì đổi sang model lớn hơn.
     */
    static String stripRomaji(String answer) {
        if (answer == null) return null;
        return answer.lines().map(line -> JAPANESE_TEXT.matcher(line).find() ? ROMAJI_PARENS.matcher(line).replaceAll("") : line)
                .collect(Collectors.joining("\n"));
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
     * Đã tìm được từ thì bỏ các dòng kanji, trừ khi người học hỏi về chính chữ kanji (Hán Việt, On/Kun).
     * Trên máy thật, model 4B suy nghĩa của 学校 từ một nghĩa riêng của 校, và từ dòng kanji 行 đoán người học dùng nhầm 行う.
     */
    static RagRetriever.Context kanjiOnlyWhenNeeded(RagRetriever.Context context, String question) {
        RagLexiconLookup.Result lexicon = context.lexicon();
        if (lexicon.words().isEmpty() || lexicon.kanji().isEmpty() || KANJI_QUESTION.matcher(plain(question)).find()) return context;
        return new RagRetriever.Context(context.passages(), new RagLexiconLookup.Result(lexicon.words(), List.of()));
    }

    /** Âm Hán Việt chép thẳng từ từ điển J-LAS, cùng mã [D] với danh sách nguồn; model 4B đã được đo là bịa âm Hán Việt. */
    static String hanVietAnswer(RagLexiconLookup.Result lexicon) {
        StringBuilder out = new StringBuilder("Âm Hán Việt theo từ điển J-LAS:\n");
        int d = 1;
        for (RagLexiconLookup.KanjiEntry kanji : lexicon.kanji()) {
            String hanViet = kanji.hanViet() == null || kanji.hanViet().isBlank() ? "chưa có âm Hán Việt" : kanji.hanViet();
            out.append("- ").append(kanji.character()).append(": ").append(hanViet).append(" [D").append(d++).append("]\n");
        }
        for (RagLexiconLookup.WordEntry word : lexicon.words()) {
            if (word.meaning() != null && !word.meaning().isBlank()) {
                out.append('\n').append(word.expression()).append(" (").append(word.reading()).append(") nghĩa là ")
                        .append(word.meaning()).append(" [D").append(d).append("]\n");
            }
            d++;
        }
        return out.toString().strip();
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
