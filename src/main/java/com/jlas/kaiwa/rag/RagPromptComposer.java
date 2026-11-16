package com.jlas.kaiwa.rag;

import java.util.List;
import java.util.stream.Collectors;

/** Dựng prompt gia sư có nguồn: mỗi nguồn được đánh số [D1], [S1] để model trích lại. */
public final class RagPromptComposer {

    static final String BASE = """
            Bạn là gia sư tiếng Nhật của J-LAS cho người Việt. Giải thích bằng tiếng Việt, ví dụ bằng tiếng Nhật.
            Phạm vi: CHỈ trả lời câu hỏi về học tiếng Nhật (từ vựng, kanji, ngữ pháp, hội thoại, bài trong giáo trình J-LAS). Câu hỏi về ngôn ngữ khác, lập trình, toán, tin tức, sức khỏe, hay bất cứ việc gì không phải học tiếng Nhật: chỉ trả lời đúng một câu "Mình chỉ hỗ trợ học tiếng Nhật trên J-LAS, nên không trả lời câu hỏi này được." và không nói gì thêm.
            Quy tắc về nguồn:
            - Âm Hán Việt, cách đọc On/Kun, nghĩa của từ, mẫu ngữ pháp và nội dung giáo trình J-LAS: CHỈ lấy từ phần NGUỒN bên dưới và ghi mã nguồn, ví dụ [D1] hoặc [S2].
            - Nếu NGUỒN không có thông tin cần thiết, chỉ trả lời "Tài liệu J-LAS chưa có thông tin này." rồi gợi ý người học hỏi cụ thể hơn hoặc viết từ, mẫu bằng chữ Nhật. Không tự giải thích bằng hiểu biết ngoài NGUỒN.
            - Khi hỏi âm Hán Việt của một từ, nêu TỪNG kanji có trong nguồn [D] cùng đủ các âm Hán Việt của nó, không bỏ chữ nào.
            - On/Kun chỉ nói cho từng chữ có dòng [D] Kanji. Cách đọc của một từ là phần trong ngoặc ở dòng [D] Từ; không gọi cách đọc của từ là On hay Kun.
            - Cách đọc chỉ viết bằng hiragana/katakana chép từ nguồn: phần trong ngoặc ngay sau kanji, ví dụ 駅(えき), hoặc phần trong ngoặc ở dòng [D] Từ. Nguồn không ghi cách đọc thì KHÔNG thêm cách đọc. Tuyệt đối không viết romaji (ví dụ "sura", "hayai ka") và không phiên âm kiểu tiếng Việt (ví dụ "Hát-pa-tô").
            - Cách chia (động từ thể gì, danh từ, tính từ) chỉ chép từ dòng "Cách chia" của nguồn; nguồn không ghi thì không tự nêu cách chia.
            - Nếu không có đoạn [S] nào nói về đúng mẫu ngữ pháp được hỏi (đoạn chỉ giống chữ cũng không tính), chỉ trả lời "Tài liệu J-LAS chưa có mẫu này." rồi hỏi lại người học muốn hỏi mẫu nào. Không giải thích mẫu đó, không chuyển sang mẫu khác.
            - Nghĩa của một từ chỉ lấy từ dòng [D] Từ của chính từ đó; không suy ra nghĩa của từ ghép từ nghĩa từng kanji.
            - Khi sửa câu người học viết, chỉ nêu lỗi mà nguồn hoặc quy tắc ngữ pháp chắc chắn chỉ ra, rồi viết câu đúng; không chắc thì nói không chắc. Không gán nghĩa khác cho từ để câu sai thành đúng.
            - Câu ví dụ chỉ chép từ đoạn [S] nói về đúng mẫu đang hỏi, và ghi mã của chính đoạn đó.
            - Trả lời ngắn gọn bằng đoạn văn hoặc gạch đầu dòng; không dùng tiêu đề (#) và không dùng bảng.
            - Không bịa số trang, tên bài hay giáo trình. Không tự nhận đã chấm điểm hay cập nhật tiến độ học.
            """;

    private RagPromptComposer() { }

    public static String systemPrompt(RagRetriever.Context context) {
        return systemPrompt(context, List.of());
    }

    /** @param romaji các từ người học gõ bằng romaji, đã đổi sang kana, để model không hiểu sang từ hay mẫu khác */
    public static String systemPrompt(RagRetriever.Context context, List<RomajiKana.Hint> romaji) {
        StringBuilder prompt = new StringBuilder(BASE);
        if (!romaji.isEmpty()) {
            prompt.append("\nNgười học gõ romaji (có thể gõ sai): ")
                    .append(romaji.stream().map(h -> "\"" + h.romaji() + "\" = " + h.kana()).collect(Collectors.joining(", ")))
                    .append(". Chỉ trả lời về đúng chữ này; NGUỒN không có chữ này thì nói chưa có và hỏi lại người học, không đoán sang từ hay mẫu khác.\n");
        }
        prompt.append("\nNGUỒN:\n");
        if (context.isEmpty()) return prompt.append("(không có nguồn phù hợp)\n").toString();
        int d = 1;
        for (RagLexiconLookup.KanjiEntry kanji : context.lexicon().kanji()) {
            prompt.append("[D").append(d++).append("] Kanji ").append(kanji.character())
                    .append(" | Hán Việt: ").append(orDash(kanji.hanViet()))
                    .append(" | On: ").append(join(kanji.onyomi()))
                    .append(" | Kun: ").append(join(kanji.kunyomi()));
            if (kanji.meaningVi() != null && !kanji.meaningVi().isBlank()) prompt.append(" | Nghĩa: ").append(kanji.meaningVi());
            if (kanji.jlptLevel() != null) prompt.append(" | ").append(kanji.jlptLevel());
            prompt.append('\n');
        }
        for (RagLexiconLookup.WordEntry word : context.lexicon().words()) {
            prompt.append("[D").append(d++).append("] Từ ").append(word.expression()).append(" (").append(word.reading()).append(")")
                    .append(" | Nghĩa: ").append(orDash(word.meaning()));
            if (word.wordType() != null) prompt.append(" | Loại: ").append(word.wordType());
            if (word.jlptLevel() != null) prompt.append(" | ").append(word.jlptLevel());
            prompt.append('\n');
        }
        if (context.passages().isEmpty()) prompt.append("[S] (không có đoạn giáo trình nào khớp câu hỏi)\n");
        int s = 1;
        for (RagRetriever.Passage passage : context.passages()) {
            prompt.append("[S").append(s++).append("] ").append(sourceLabel(passage)).append('\n')
                    .append(passage.text()).append('\n');
        }
        return prompt.toString();
    }

    /** Một nguồn hiển thị cho người học, cùng mã [D1]/[S1] với prompt. */
    public record Citation(String code, String kind, String title, String detail) { }

    /** Danh sách nguồn theo đúng thứ tự đánh mã của {@link #systemPrompt}. */
    public static List<Citation> citations(RagRetriever.Context context) {
        List<Citation> out = new java.util.ArrayList<>();
        int d = 1;
        for (RagLexiconLookup.KanjiEntry kanji : context.lexicon().kanji()) {
            out.add(new Citation("D" + d++, "KANJI", kanji.character(), "Hán Việt: " + orDash(kanji.hanViet())));
        }
        for (RagLexiconLookup.WordEntry word : context.lexicon().words()) {
            out.add(new Citation("D" + d++, "WORD", word.expression() + " (" + word.reading() + ")", orDash(word.meaning())));
        }
        int s = 1;
        for (RagRetriever.Passage passage : context.passages()) {
            out.add(new Citation("S" + s++, passage.sourceType(), passage.title(), sourceLabel(passage)));
        }
        return out;
    }

    private static String sourceLabel(RagRetriever.Passage passage) {
        return switch (passage.sourceType()) {
            case "JLPT_GRAMMAR" -> "Lộ trình JLPT " + passage.citation().getOrDefault("level", "") + " · Ngữ pháp";
            case "CONFUSABLE" -> "Lộ trình JLPT " + passage.citation().getOrDefault("level", "") + " · Phân biệt";
            case "COMM_DIALOGUE" -> "Giao tiếp · " + passage.citation().getOrDefault("situation", passage.title());
            default -> passage.citation().getOrDefault("textbook", "") + " › "
                    + passage.citation().getOrDefault("lesson", "");
        };
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? "-" : values.stream().collect(Collectors.joining(", "));
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
