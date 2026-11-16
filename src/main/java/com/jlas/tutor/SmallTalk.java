package com.jlas.tutor;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Câu chào, cảm ơn và câu hỏi về chính gia sư được đáp ngắn, cố định. Đưa qua model thì model 4B biến lời chào thành
 * bài giảng はじめまして và tự nhận sai về mình. Chỉ khớp cả câu, để câu như "bạn là ai tiếng Nhật nói thế nào" vẫn được trả lời.
 */
final class SmallTalk {

    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    private static final String POLITE = "( a| nhe| nha| nhieu| ban| gia su| bot| ad| admin| moi nguoi| em| anh| chi)*";
    private static final Pattern GREETING = Pattern.compile("((xin )?chao|hello|helo|hi|hey|alo)" + POLITE);
    private static final Pattern THANKS = Pattern.compile("((ok|oke|okay|da|vang) )?(cam on|thanks?( you)?|tks|thank)" + POLITE);
    private static final Pattern IDENTITY = Pattern.compile(
            "((ban|may|em) (la ai|la gi|ten (la )?gi)( vay| the| a)? ?)+((ban )?dung (model|mo hinh) gi( vay| the)?)?"
                    + "|(ban )?dung (model|mo hinh) gi( vay| the)?|ai tao ra ban|who are you|what model are you");

    /** Hỏi về ngôn ngữ khác mà không nhắc gì tới tiếng Nhật: ngoài phạm vi của J-LAS, không đưa qua model. */
    private static final Pattern OTHER_LANGUAGE = Pattern.compile(
            "\\b(tieng (anh|han|trung|hoa|phap|duc|nga|thai|tay ban nha|y)|english|korean|chinese|french|german|spanish)\\b");
    private static final Pattern ABOUT_JAPANESE = Pattern.compile("\\b(nhat|japan(ese)?|nihongo|kanji|hiragana|katakana|jlpt)\\b");

    static final String OUT_OF_SCOPE_REPLY = "Mình chỉ hỗ trợ học tiếng Nhật trên J-LAS, nên không trả lời câu hỏi này được."
            + " Bạn muốn biết từ hay mẫu câu này trong tiếng Nhật thì hỏi mình nhé.";
    static final String GREETING_REPLY = "Chào bạn! Mình là gia sư AI của J-LAS. Bạn muốn hỏi từ vựng, kanji hay mẫu ngữ pháp nào trong giáo trình?";
    static final String THANKS_REPLY = "Không có gì! Bạn cứ hỏi tiếp nhé.";
    static final String IDENTITY_REPLY = "Mình là gia sư AI của J-LAS. Mình chỉ trả lời dựa trên giáo trình"
            + " và từ điển J-LAS, không tra Internet. Bạn muốn hỏi từ vựng, kanji hay mẫu ngữ pháp nào?";

    private SmallTalk() { }

    /** Câu đáp cố định, hoặc {@code null} khi đây là một câu hỏi thật. */
    static String reply(String question) {
        if (JAPANESE.matcher(question).find()) return null;
        String plain = Normalizer.normalize(question.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replaceAll("[^a-z0-9]+", " ").strip();
        if (GREETING.matcher(plain).matches()) return GREETING_REPLY;
        if (THANKS.matcher(plain).matches()) return THANKS_REPLY;
        if (IDENTITY.matcher(plain).matches()) return IDENTITY_REPLY;
        if (OTHER_LANGUAGE.matcher(plain).find() && !ABOUT_JAPANESE.matcher(plain).find()) return OUT_OF_SCOPE_REPLY;
        return null;
    }
}
