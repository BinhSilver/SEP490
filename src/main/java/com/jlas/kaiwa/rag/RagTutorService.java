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
    private final TutorAntonymStore antonyms;

    public RagTutorService(RagRetriever retriever, LocalLlmClient llm, TutorAntonymStore antonyms) {
        this.retriever = retriever;
        this.llm = llm;
        this.antonyms = antonyms;
    }

    public record Answer(String answer, List<RagRetriever.Passage> passages, RagLexiconLookup.Result lexicon,
                         List<RagPromptComposer.Citation> citations, long retrievalMs, long generationMs) { }

    public Answer ask(String question) {
        return ask(question, List.of());
    }

    /** @param history các lượt trước trong cùng cuộc trò chuyện (role user/assistant), đã được giới hạn độ dài */
    public Answer ask(String question, List<Map<String, String>> history) {
        return ask(question, history, null);
    }

    /** @param onDelta khác null thì nhận từng mẩu chữ của model khi nó viết; câu trả lời cố định không qua đây */
    public Answer ask(String question, List<Map<String, String>> history, java.util.function.Consumer<String> onDelta) {
        long started = System.nanoTime();
        List<RomajiKana.Hint> hints = RomajiKana.hints(question);
        RagRetriever.Context context = kanjiOnlyWhenNeeded(retriever.retrieve(retrievalQuery(question, hints, history), null), question);
        long retrieved = System.nanoTime();
        long retrievalMs = (retrieved - started) / 1_000_000;
        if (!context.lexicon().kanji().isEmpty() && HAN_VIET_QUESTION.matcher(plain(question)).find()) {
            return new Answer(hanVietAnswer(context.lexicon()), context.passages(), context.lexicon(),
                    RagPromptComposer.citations(context), retrievalMs, 0);
        }
        RagLexiconLookup.WordEntry antonymOf = antonymTarget(question, context.lexicon());
        if (antonymOf != null) {
            // Đã có người hỏi từ này thì dùng lại gợi ý đã lưu: ai hỏi cũng nhận cùng một đáp án, và người duyệt sửa được.
            TutorAntonymStore.Saved saved = antonyms.find(antonymOf.id()).orElse(null);
            List<RagLexiconLookup.WordEntry> verified;
            if (saved == null) {
                verified = verifiedAntonyms(antonymOf);
                antonyms.save(antonymOf.id(), verified.isEmpty() ? null : verified.getFirst().id(), llm.model());
            } else {
                verified = saved.rejected() || saved.antonym() == null ? List.of() : List.of(saved.antonym());
            }
            boolean approved = saved != null && saved.approved();
            RagRetriever.Context shown = new RagRetriever.Context(List.of(), new RagLexiconLookup.Result(
                    java.util.stream.Stream.concat(java.util.stream.Stream.of(antonymOf), verified.stream()).toList(), List.of()));
            return new Answer(antonymAnswer(antonymOf, verified, approved), List.of(), shown.lexicon(),
                    verified.isEmpty() ? List.of() : RagPromptComposer.citations(shown), retrievalMs, (System.nanoTime() - retrieved) / 1_000_000);
        }
        String fixed = context.isEmpty() ? NO_SOURCE + (HEALTH_QUESTION.matcher(plain(question)).find() ? SEE_A_DOCTOR : "")
                : !hints.isEmpty() && isGrammarQuestion(question) && notInSources(hints, context) ? unknownPattern(hints) : null;
        if (fixed != null) return new Answer(fixed, context.passages(), context.lexicon(), List.of(), retrievalMs, 0);
        String system = RagPromptComposer.systemPrompt(context, hints);
        String answer = stripRomaji(onDelta == null ? llm.chat(system, history, question, MAX_ANSWER_TOKENS)
                : llm.chatStream(system, history, question, MAX_ANSWER_TOKENS, onDelta));
        long done = System.nanoTime();
        return new Answer(answer, context.passages(), context.lexicon(), RagPromptComposer.citations(context),
                retrievalMs, (done - retrieved) / 1_000_000);
    }

    private static final Pattern ANTONYM_QUESTION = Pattern.compile("\\b(trai nghia|nguoc nghia|doi nghia|antonym)\\b|反対語|対義語|反意語");
    private static final Pattern JAPANESE_WORD = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々]{2,}");
    /** Đo ngày 2026-11-16 trên 34 từ cơ bản với qwen3.5:9b: gợi ý đầu tiên đúng gần hết, gợi ý thứ hai và thứ ba hay sai. */
    static final int MAX_ANTONYMS = 1;
    static final String ANTONYM_PROMPT = """
            Bạn là từ điển tiếng Nhật. Người dùng đưa một từ tiếng Nhật kèm cách đọc và nghĩa tiếng Việt.
            Hãy viết tối đa 3 từ tiếng Nhật TRÁI NGHĨA với từ đó, cùng loại từ (tính từ với tính từ, động từ với động từ),
            ở dạng từ điển và cách viết thường gặp nhất, cách nhau bằng dấu phẩy. Không giải thích, không viết cách đọc.
            Nếu từ đó không có từ trái nghĩa (ví dụ tên riêng, tên đồ vật, 誕生日), chỉ viết: なし
            """;

    /** Từ đang được hỏi từ trái nghĩa mà từ điển J-LAS chưa ghi; null khi không phải câu hỏi đó hoặc từ điển đã có. */
    static RagLexiconLookup.WordEntry antonymTarget(String question, RagLexiconLookup.Result lexicon) {
        if (lexicon.words().isEmpty() || !ANTONYM_QUESTION.matcher(plain(question) + question).find()) return null;
        RagLexiconLookup.WordEntry word = lexicon.words().getFirst();
        return word.extras().stream().anyMatch(e -> e.startsWith("Trái nghĩa")) ? null : word;
    }

    /**
     * Model chỉ ĐỀ XUẤT từ; chỉ giữ từ nào có đúng mục trong từ điển J-LAS, và nghĩa, cách đọc hiển thị lấy từ mục đó.
     * ponytail: không kiểm được hai từ có thật sự trái nghĩa hay không, chỉ kiểm từ có thật; gợi ý được lưu ở trạng thái
     * PENDING ({@link TutorAntonymStore}) cho người duyệt. Chưa có màn hình duyệt: đổi status bằng SQL.
     */
    private List<RagLexiconLookup.WordEntry> verifiedAntonyms(RagLexiconLookup.WordEntry word) {
        String raw = llm.chat(ANTONYM_PROMPT, List.of(), word.expression() + " (" + word.reading() + "): " + word.meaning(), 40);
        List<RagLexiconLookup.WordEntry> verified = new ArrayList<>();
        java.util.regex.Matcher m = JAPANESE_WORD.matcher(raw);
        while (m.find() && verified.size() < MAX_ANTONYMS) {
            String candidate = m.group();
            if (candidate.equals("なし") || candidate.equals(word.expression())
                    || verified.stream().anyMatch(v -> v.expression().equals(candidate))) continue;
            retriever.words(candidate).stream().filter(w -> w.expression().equals(candidate)).findFirst().ifPresent(verified::add);
        }
        org.slf4j.LoggerFactory.getLogger(RagTutorService.class).info("Tutor antonym suggestion word={} proposed={} verified={}",
                word.expression(), raw.replaceAll("\\s+", " "), verified.stream().map(RagLexiconLookup.WordEntry::expression).toList());
        return verified;
    }

    /** @param approved gợi ý đã được người duyệt: hiển thị như dữ liệu thường, không kèm dòng cảnh báo */
    static String antonymAnswer(RagLexiconLookup.WordEntry word, List<RagLexiconLookup.WordEntry> verified, boolean approved) {
        if (verified.isEmpty()) {
            return "Từ điển J-LAS chưa ghi từ trái nghĩa của " + word.expression()
                    + ", và mình chưa tìm được gợi ý nào đối chiếu được với từ điển.";
        }
        if (approved) {
            RagLexiconLookup.WordEntry v = verified.getFirst();
            return "Từ trái nghĩa của " + word.expression() + " [D1]:\n- " + v.expression() + " (" + v.reading() + "): "
                    + shortMeaning(v.meaning()) + " [D2]";
        }
        StringBuilder out = new StringBuilder("Từ điển J-LAS chưa ghi từ trái nghĩa của ").append(word.expression())
                .append(" [D1]. Gợi ý của AI, đã đối chiếu với từ điển J-LAS:\n");
        int d = 2;
        for (RagLexiconLookup.WordEntry v : verified) {
            out.append("- ").append(v.expression()).append(" (").append(v.reading()).append("): ")
                    .append(shortMeaning(v.meaning())).append(" [D").append(d++).append("]\n");
        }
        return out.append("Đây là gợi ý do AI chọn, chưa được người duyệt; cách đọc và nghĩa lấy từ từ điển J-LAS.").toString();
    }

    /** Mục từ điển có khi liệt kê hàng chục nghĩa; chỉ lấy vài nghĩa đầu cho dòng gợi ý. */
    private static String shortMeaning(String meaning) {
        if (meaning == null || meaning.isBlank()) return "-";
        String first = String.join("; ", java.util.Arrays.stream(meaning.split("[;,]")).map(String::strip).limit(3).toList());
        return first.length() > 80 ? first.substring(0, 80) + "…" : first;
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
        return answer.lines().map(line -> JAPANESE_TEXT.matcher(line).find()
                        ? SHORT_ROMAJI.matcher(ROMAJI_PARENS.matcher(line).replaceAll("")).replaceAll("$1") : line)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Romaji ngắn ngay sau chữ Nhật, ví dụ "高い (takai)" (đo được trên model 9B). Chỉ bắt ngoặc toàn chữ thường từ 2 ký tự
     * đứng liền sau chữ Nhật, nên "(N + まで)", "(BrSE)", "(JLPT N2)" được giữ.
     * ponytail: một từ tiếng Anh viết thường ngay sau chữ Nhật, ví dụ "レビュー (review)", cũng bị bỏ.
     */
    private static final Pattern SHORT_ROMAJI = Pattern.compile(
            "([\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}ー][*」』\"]*)\\s*[(（]\\s*\\*?[a-zāīūēōâîûêô][a-zāīūēōâîûêô' \\-]+\\*?\\s*[)）]");

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
