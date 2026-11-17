package com.jlas.tutor;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Câu chào, cảm ơn, câu hỏi về chính gia sư, và lời tâm sự buồn được đáp ngắn, cố định, không qua model.
 * Chỉ khớp cả câu (trừ lời tâm sự), để câu như "bạn là ai tiếng Nhật nói thế nào" vẫn được trả lời.
 */
final class SmallTalk {

    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    private static final String POLITE = "( a| nhe| nha| nhieu| ban| gia su| bot| ad| admin| moi nguoi| em| anh| chi)*";
    private static final Pattern GREETING = Pattern.compile("((xin )?chao|hello|helo|hi|hey|alo)" + POLITE);
    private static final Pattern THANKS = Pattern.compile("((ok|oke|okay|da|vang) )?(cam on|thanks?( you)?|tks|thank)" + POLITE);
    private static final Pattern IDENTITY = Pattern.compile(
            "((ban|may|em) (la ai|la gi|ten (la )?gi)( vay| the| a)? ?)+((ban )?dung (model|mo hinh) gi( vay| the)?)?"
                    + "|(ban )?dung (model|mo hinh) gi( vay| the)?|ai tao ra ban|who are you|what model are you");
    /** "Bạn có thể làm gì?", "bạn giúp được gì", "gia sư có chức năng gì". */
    private static final Pattern CAPABILITIES = Pattern.compile(
            "((ban|gia su|bot|em|may|cau) )?((co the|biet) )?(lam|giup|ho tro|tra loi)( duoc)?( cho (minh|toi|em))?"
                    + " (gi|nhung gi|viec gi|nhung viec gi)( cho (minh|toi|em))?( vay| the| a| khong| nao| nhi)*"
                    + "|((ban|gia su|bot) )?co (nhung )?(chuc nang|tinh nang) gi( vay| the| a)*"
                    + "|what can you do|help");
    private static final Pattern ABOUT_JLAS = Pattern.compile(
            "(j ?las|web (nay|nay la)|trang (web )?nay)( la gi| la web gi| dung de lam gi| co gi| lam duoc gi)( vay| the| a)?");

    /** Hỏi về ngôn ngữ khác mà không nhắc gì tới tiếng Nhật: ngoài phạm vi của J-LAS, không đưa qua model. */
    private static final Pattern OTHER_LANGUAGE = Pattern.compile(
            "\b(tieng (anh|han|trung|hoa|phap|duc|nga|thai|tay ban nha|y)|english|korean|chinese|french|german|spanish)\b");
    private static final Pattern ABOUT_JAPANESE = Pattern.compile("\b(nhat|japan(ese)?|nihongo|kanji|hiragana|katakana|jlpt)\b");

    /** Ý nghĩ tự tử, tự hại: luôn đáp trước mọi thứ khác, kể cả khi câu có chữ Nhật. */
    private static final Pattern CRISIS = Pattern.compile(
            "\\b(khong muon song|chang muon song|het muon song|muon chet|(muon|dinh|se|nghi den|nghi toi|tinh) tu (tu|sat)|tu sat|ket lieu|tu lam hai|chan song"
                    + "|khong thiet song|song lam gi|khong con ly do de song|bien mat khoi the gian|kill myself|suicide)\\b");
    private static final Pattern CRISIS_JA = Pattern.compile("死にたい|消えたい|自殺|生きたくない|生きる意味がない");
    /** Buồn, nản vì học hay thi: động viên rồi mời học tiếp. */
    private static final Pattern SAD = Pattern.compile(
            "\\b(thi truot|truot (n[1-5]|jlpt|ky thi|bai thi)|thi rot|rot (n[1-5]|jlpt)|chan qua|chan that|buon qua|buon that|nan qua"
                    + "|nan that|that vong|ap luc qua|met moi qua|muon bo cuoc|muon bo hoc|hoc mai khong vao|khong hoc noi)\\b");

    static final String OUT_OF_SCOPE_REPLY = com.jlas.kaiwa.rag.RagTutorService.OUT_OF_SCOPE_REPLY;
    static final String GREETING_REPLY = "Chào bạn! Mình là gia sư AI của J-LAS. Bạn muốn hỏi từ vựng, kanji hay mẫu ngữ pháp nào trong giáo trình?";
    static final String THANKS_REPLY = "Không có gì! Bạn cứ hỏi tiếp nhé.";
    static final String IDENTITY_REPLY = "Mình là gia sư AI của J-LAS. Mình trả lời dựa trên giáo trình và từ điển J-LAS;"
            + " phần nào J-LAS chưa có thì mình ghi rõ là AI tổng hợp. Bạn muốn hỏi từ vựng, kanji hay mẫu ngữ pháp nào?";
    static final String CAPABILITIES_REPLY = "Mình là gia sư tiếng Nhật của J-LAS. Mình có thể giúp bạn:\n"
            + "- Tra từ vựng: nghĩa, cách đọc, cấp JLPT, câu ví dụ, mẹo nhớ, từ trái nghĩa.\n"
            + "- Kanji: âm Hán Việt, âm On/Kun, nghĩa của từng chữ.\n"
            + "- Ngữ pháp: giải thích mẫu câu (ví dụ ～にとって), phân biệt các mẫu dễ nhầm, kính ngữ.\n"
            + "- Liệt kê từ theo cấp và chủ đề, ví dụ \"5 từ N5 về đồ ăn\".\n"
            + "- Đặt câu với một từ, chuyển câu sang thể lịch sự hay thể thường, dịch câu Nhật–Việt.\n"
            + "- Sửa câu tiếng Nhật bạn viết, gợi ý mẹo học.\n\n"
            + "Mình trả lời dựa trên giáo trình và từ điển J-LAS; phần nào J-LAS chưa có thì mình ghi rõ là AI tổng hợp."
            + " Bạn cứ gõ từ hoặc câu hỏi, bằng chữ Nhật hay tiếng Việt đều được.";
    static final String ABOUT_JLAS_REPLY = "J-LAS là nền tảng học tiếng Nhật cho người Việt: giáo trình theo bài, lộ trình JLPT"
            + " từ N5 đến N1, từ điển Nhật–Việt, đề thi thử JLPT, luyện hội thoại với AI, shadowing và các trò chơi luyện kana, kanji."
            + " Mình là gia sư AI trong đó: bạn hỏi mình về từ vựng, kanji, ngữ pháp hay nhờ đặt câu, dịch câu nhé.";
    static final String CRISIS_REPLY = "Mình rất tiếc khi bạn đang thấy nặng nề đến vậy. Cảm giác của bạn là thật và đáng được"
            + " lắng nghe, bạn không phải chịu một mình. Một kỳ thi không quyết định giá trị của bạn.\n\n"
            + "Nếu bạn đang nghĩ tới việc làm hại bản thân, hãy nói ngay với một người bạn tin tưởng (gia đình, bạn bè, thầy cô),"
            + " hoặc gọi đường dây hỗ trợ tâm lý Ngày Mai 096 306 1414. Nếu bạn đang gặp nguy hiểm, gọi cấp cứu 115.\n\n"
            + "Khi bạn thấy ổn hơn, mình vẫn ở đây để cùng bạn ôn lại từng chút một.";
    static final String SAD_REPLY = "Mình hiểu, trượt hay học mãi chưa vào thì rất buồn và mệt. Bạn đã bỏ công sức ra học,"
            + " và lần này chưa được không có nghĩa là bạn không làm được. Nghỉ ngơi một chút cũng được nhé.\n\n"
            + "Khi bạn sẵn sàng, mình có thể giúp: ôn lại từ vựng, kanji hay mẫu ngữ pháp bạn hay sai, giải thích câu bạn chưa hiểu,"
            + " hoặc gợi ý cách chia nhỏ việc ôn tập. Bạn muốn bắt đầu từ phần nào?";

    private SmallTalk() { }

    /** Câu đáp cố định, hoặc {@code null} khi đây là một câu hỏi thật. */
    static String reply(String question) {
        String plain = Normalizer.normalize(question.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replaceAll("[^a-z0-9\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]+", " ").strip();
        if (CRISIS.matcher(plain).find() || CRISIS_JA.matcher(question).find()) return CRISIS_REPLY;
        if (JAPANESE.matcher(question).find()) return null;
        if (SAD.matcher(plain).find()) return SAD_REPLY;
        if (GREETING.matcher(plain).matches()) return GREETING_REPLY;
        if (THANKS.matcher(plain).matches()) return THANKS_REPLY;
        if (IDENTITY.matcher(plain).matches()) return IDENTITY_REPLY;
        if (ABOUT_JLAS.matcher(plain).matches()) return ABOUT_JLAS_REPLY;
        if (CAPABILITIES.matcher(plain).matches()) return CAPABILITIES_REPLY;
        if (OTHER_LANGUAGE.matcher(plain).find() && !ABOUT_JAPANESE.matcher(plain).find()) return OUT_OF_SCOPE_REPLY;
        return null;
    }
}
