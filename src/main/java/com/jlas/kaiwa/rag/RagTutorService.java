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
    private final TutorAiAnswerStore aiAnswers;

    public RagTutorService(RagRetriever retriever, LocalLlmClient llm, TutorAntonymStore antonyms, TutorAiAnswerStore aiAnswers) {
        this.retriever = retriever;
        this.llm = llm;
        this.antonyms = antonyms;
        this.aiAnswers = aiAnswers;
    }

    /** Tầng của câu trả lời: có nguồn J-LAS, AI tổng hợp chưa duyệt, hoặc AI tổng hợp đã được người duyệt. */
    public static final String TIER_JLAS = "JLAS", TIER_AI = "AI", TIER_AI_REVIEWED = "AI_REVIEWED";

    /**
     * @param tier              {@link #TIER_JLAS}, {@link #TIER_AI} hoặc {@link #TIER_AI_REVIEWED}
     * @param fallbackEligible  J-LAS không trả lời được một câu hỏi tiếng Nhật: được phép nhờ tầng 2 ({@link #aiFallback})
     */
    public record Answer(String answer, List<RagRetriever.Passage> passages, RagLexiconLookup.Result lexicon,
                         List<RagPromptComposer.Citation> citations, long retrievalMs, long generationMs,
                         String tier, boolean fallbackEligible) {
        public Answer(String answer, List<RagRetriever.Passage> passages, RagLexiconLookup.Result lexicon,
                      List<RagPromptComposer.Citation> citations, long retrievalMs, long generationMs) {
            this(answer, passages, lexicon, citations, retrievalMs, generationMs, TIER_JLAS, false);
        }

        Answer eligible(boolean value) {
            return new Answer(answer, passages, lexicon, citations, retrievalMs, generationMs, tier, value);
        }
    }

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
        if (fixed != null) {
            return new Answer(fixed, context.passages(), context.lexicon(), List.of(), retrievalMs, 0)
                    .eligible(context.isEmpty() && fallbackAllowed(question) && !HEALTH_QUESTION.matcher(plain(question)).find());
        }
        String system = RagPromptComposer.systemPrompt(context, hints);
        String answer = stripRomaji(onDelta == null ? llm.chat(system, history, question, MAX_ANSWER_TOKENS)
                : llm.chatStream(system, history, question, MAX_ANSWER_TOKENS, onDelta));
        long done = System.nanoTime();
        return new Answer(answer, context.passages(), context.lexicon(), RagPromptComposer.citations(context),
                retrievalMs, (done - retrieved) / 1_000_000).eligible(answer.contains(MISSING_MARK) && fallbackAllowed(question));
    }

    // ---------------------------------------------------------------------
    // Tầng 2: J-LAS thiếu dữ liệu cho một câu hỏi tiếng Nhật
    // ---------------------------------------------------------------------

    /** Phần đầu cố định của {@link #NO_SOURCE}: tầng 1 không có nguồn nào. */
    public static final String NO_SOURCE_PREFIX = "Tài liệu J-LAS chưa có nội dung cho câu hỏi này";

    /** Tầng 2 chỉ chạy với model qua API (đã đo chính xác hơn); model nhỏ trên máy hay bịa khi không có nguồn. */
    public boolean usesApiModel() {
        return llm.isApi();
    }

    /** Câu model tầng 1 dùng khi nguồn không có thông tin (prompt bắt viết đúng câu này). */
    static final String MISSING_MARK = "Tài liệu J-LAS chưa có";
    /** Cách đọc, âm Hán Việt, cấp JLPT: chỉ lấy từ dữ liệu J-LAS, không bao giờ để AI tự nói. */
    private static final Pattern DATA_ONLY_QUESTION = Pattern.compile(
            "\\b(doc la|doc the nao|doc sao|cach doc|phat am|han viet|am on|am kun|onyomi|kunyomi|cap jlpt|cap do|trinh do|n[1-5] hay)\\b");
    private static final Pattern JAPANESE_IN_QUESTION = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    static final int MAX_FALLBACK_TOKENS = 500;
    static final String AI_PART = "Phần dưới do AI tổng hợp, chưa được người duyệt, bạn nên đối chiếu thêm với giáo viên hoặc từ điển:";
    static final String AI_REVIEWED_PART = "Phần dưới do AI tổng hợp và đã được người duyệt:";
    static final String NOT_IN_JLAS = "Tài liệu J-LAS chưa có nội dung này. ";
    static final String FALLBACK_PROMPT = """
            Bạn là gia sư tiếng Nhật cho người Việt. Tài liệu J-LAS chưa có nội dung cho câu hỏi này, nên hãy trả lời bằng
            kiến thức tiếng Nhật chuẩn mà bạn chắc chắn đúng.
            - Chỉ trả lời về tiếng Nhật. Câu hỏi không phải về học tiếng Nhật thì chỉ viết: なし
            - KHÔNG viết cách đọc, furigana, romaji, âm Hán Việt hay cấp JLPT: hệ thống tự thêm những thứ này từ từ điển J-LAS.
            - Câu ví dụ tiếng Nhật ngắn, đơn giản, đúng ngữ pháp, mỗi câu kèm nghĩa tiếng Việt.
            - Điều gì không chắc thì nói rõ là không chắc; không bịa nguồn, sách hay số liệu.
            - Trả lời bằng tiếng Việt, ngắn gọn (dưới 150 chữ), dùng gạch đầu dòng nếu cần; không tiêu đề, không bảng.
            """;

    /** Câu hỏi có chữ Nhật (tự đủ nghĩa, lưu lại được) và không hỏi thứ chỉ được lấy từ dữ liệu. */
    static boolean fallbackAllowed(String question) {
        return JAPANESE_IN_QUESTION.matcher(question).find() && !DATA_ONLY_QUESTION.matcher(plain(question)).find();
    }

    /**
     * Tầng 2: dùng lại bản đã lưu cho câu hỏi này, hoặc nhờ AI tổng hợp rồi lưu lại ở trạng thái chờ duyệt.
     * Mọi từ tiếng Nhật trong câu trả lời có trong từ điển J-LAS được liệt kê kèm cách đọc và nghĩa lấy từ từ điển.
     *
     * @param standalone   true khi tầng 1 không có nguồn nào (câu trả lời thay hẳn tầng 1); false khi nối sau câu trả lời
     *                     tầng 1 đã có nguồn. Danh sách từ không đánh mã [D] để không trùng mã nguồn của tầng 1.
     * @param mayCallModel gọi ngay trước khi hỏi AI (kiểm hạn mức); false thì không hỏi
     * @return câu trả lời tầng 2, hoặc null khi bản đã lưu bị người duyệt từ chối, hết hạn mức, hoặc AI không trả lời được
     */
    public Answer aiFallback(String question, boolean standalone, java.util.function.Consumer<String> onDelta,
                             java.util.function.BooleanSupplier mayCallModel) {
        long started = System.nanoTime();
        TutorAiAnswerStore.Saved saved = aiAnswers.find(question).orElse(null);
        String body;
        boolean reviewed;
        if (saved != null) {
            if (saved.rejected()) return null;
            body = saved.answer();
            reviewed = saved.approved();
        } else {
            if (!mayCallModel.getAsBoolean()) return null;
            if (onDelta != null) onDelta.accept((standalone ? NOT_IN_JLAS : "\n\n") + AI_PART + "\n\n");
            String raw = stripRomaji(onDelta == null ? llm.chat(FALLBACK_PROMPT, List.of(), question, MAX_FALLBACK_TOKENS)
                    : llm.chatStream(FALLBACK_PROMPT, List.of(), question, MAX_FALLBACK_TOKENS, onDelta)).strip();
            if (raw.isEmpty() || raw.equals("なし") || raw.startsWith("なし")) return null;
            body = raw;
            reviewed = false;
            aiAnswers.save(question, body, llm.model());
        }
        return withDictionary((standalone ? NOT_IN_JLAS : "") + (reviewed ? AI_REVIEWED_PART : AI_PART), body, question,
                reviewed ? TIER_AI_REVIEWED : TIER_AI, started);
    }

    /** Câu trả lời tầng 2 kèm danh sách từ tiếng Nhật có trong từ điển: cách đọc và nghĩa luôn là của J-LAS. */
    private Answer withDictionary(String label, String body, String question, String tier, long started) {
        List<RagLexiconLookup.WordEntry> words = new ArrayList<>();
        for (RagLexiconLookup.WordEntry w : retriever.words(question + "\n" + body)) {
            if (words.size() < RagLexiconLookup.MAX_WORDS && words.stream().noneMatch(x -> x.expression().equals(w.expression()))) words.add(w);
        }
        StringBuilder out = new StringBuilder(label).append("\n\n").append(body);
        if (!words.isEmpty()) {
            out.append("\n\nTừ trong câu trả lời, cách đọc và nghĩa theo từ điển J-LAS:");
            for (RagLexiconLookup.WordEntry w : words) {
                out.append("\n- ").append(w.expression()).append(" (").append(w.reading()).append("): ").append(shortMeaning(w.meaning()));
            }
        }
        return new Answer(out.toString(), List.of(), new RagLexiconLookup.Result(words, List.of()), List.of(), 0,
                (System.nanoTime() - started) / 1_000_000, tier, false);
    }

    // ---------------------------------------------------------------------
    // Dịch câu (tầng 2, không lưu)
    // ---------------------------------------------------------------------

    public static final String TIER_AI_TRANSLATION = "AI_TRANSLATION";
    private static final Pattern TRANSLATION_QUESTION = Pattern.compile("\\b(dich|translate|phien dich)\\b");
    static final String TRANSLATION_PART = "Bản dịch do AI thực hiện, chưa được người duyệt, bạn nên đối chiếu thêm với giáo viên hoặc từ điển:";
    static final String TRANSLATE_PROMPT = """
            Bạn là gia sư tiếng Nhật cho người Việt. Người học nhờ dịch một câu hoặc đoạn ngắn giữa tiếng Nhật và tiếng Việt.
            - Câu tiếng Nhật thì dịch sang tiếng Việt tự nhiên; câu tiếng Việt thì dịch sang tiếng Nhật chuẩn, lịch sự (thể です/ます)
              trừ khi người học yêu cầu thể thường.
            - Viết bản dịch trước, sau đó tối đa 3 gạch đầu dòng giải thích ngắn các từ hoặc mẫu ngữ pháp chính.
            - KHÔNG viết cách đọc, furigana, romaji, âm Hán Việt hay cấp JLPT: hệ thống tự thêm từ từ điển J-LAS.
            - Không dịch sang hay từ ngôn ngữ khác ngoài tiếng Nhật và tiếng Việt; không phải yêu cầu dịch như vậy thì chỉ viết: なし
            - Chỗ nào không chắc thì nói rõ là không chắc. Không tiêu đề, không bảng.
            """;

    /** Người học nhờ dịch một câu (Nhật → Việt hoặc Việt → Nhật). */
    public static boolean isTranslationRequest(String question) {
        return TRANSLATION_QUESTION.matcher(plain(question)).find();
    }

    /**
     * Dịch bằng AI, gắn nhãn chưa duyệt, kèm cách đọc và nghĩa từ điển. KHÔNG lưu: câu cần dịch hầu như không lặp lại,
     * và người học có thể dán nội dung riêng tư.
     *
     * @param mayCallModel gọi ngay trước khi hỏi AI (kiểm hạn mức); false thì không dịch
     * @return bản dịch, hoặc null khi hết hạn mức hay AI cho rằng đây không phải yêu cầu dịch tiếng Nhật
     */
    public Answer aiTranslate(String question, java.util.function.Consumer<String> onDelta, java.util.function.BooleanSupplier mayCallModel) {
        long started = System.nanoTime();
        if (!mayCallModel.getAsBoolean()) return null;
        if (onDelta != null) onDelta.accept(TRANSLATION_PART + "\n\n");
        String raw = stripRomaji(onDelta == null ? llm.chat(TRANSLATE_PROMPT, List.of(), question, MAX_FALLBACK_TOKENS)
                : llm.chatStream(TRANSLATE_PROMPT, List.of(), question, MAX_FALLBACK_TOKENS, onDelta)).strip();
        if (raw.isEmpty() || raw.startsWith("なし")) return null;
        return withDictionary(TRANSLATION_PART, raw, question, TIER_AI_TRANSLATION, started);
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
