package com.jlas.learning.service.impl.text;

import com.atilika.kuromoji.ipadic.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Day 2 (24/10): the shared dictionary has no word type for almost every path word (875/878 N5, 1,064/1,066 N4, 1,855/1,855
 * N3), and no source in the repo carries one for Minna/Sekai. Learning derives a label from the IPADIC part of speech of the
 * study expression instead, shown as generated and unreviewed (OD-06). It never writes the shared dictionary.
 * <p>One content word (verb, adjective, noun, compound noun …) gets its class, with the verb group from the conjugation
 * type; anything longer is "Cụm từ". A leading ～ marks a suffix or counter (～本, ～まい).
 */
public final class GeneratedWordType {
    static final String PHRASE = "Cụm từ";
    private static final Set<String> TRAILING = Set.of("助動詞", "助詞");

    private GeneratedWordType() { }

    /** Vietnamese word-type label for a study expression, or null when there is nothing to label. */
    public static String labelFor(String expression) {
        if (expression == null || expression.isBlank()) return null;
        String text = expression.strip();
        if (text.startsWith("～") || text.startsWith("〜") || text.startsWith("~")) return "Hậu tố";
        // Optional parts printed in brackets (げんき［な］, （お）なまえ) are not part of the word.
        text = text.replaceAll("［[^］]*］|\\[[^\\]]*]|（[^）]*）|\\([^)]*\\)", "").strip();
        if (text.isEmpty()) return null;
        List<Token> content = new ArrayList<>();
        for (Token token : JapaneseTokenizer.tokenize(text)) {
            if (!"記号".equals(token.getPartOfSpeechLevel1())) content.add(token);
        }
        if (content.isEmpty()) return null;
        if (content.stream().allMatch(t -> "名詞".equals(t.getPartOfSpeechLevel1()))
                && !"形容動詞語幹".equals(content.getLast().getPartOfSpeechLevel2())
                && !"代名詞".equals(content.getFirst().getPartOfSpeechLevel2())) {
            return content.size() == 1 && "接尾".equals(content.getFirst().getPartOfSpeechLevel2()) ? "Hậu tố" : "Danh từ";
        }
        Token head = content.getFirst();
        boolean suruNoun = content.size() > 1 && "名詞".equals(head.getPartOfSpeechLevel1())
                && "する".equals(content.get(1).getBaseForm());
        for (Token t : content.subList(suruNoun ? 2 : 1, content.size())) {
            boolean trailing = TRAILING.contains(t.getPartOfSpeechLevel1())
                    || "動詞".equals(t.getPartOfSpeechLevel1()) && "非自立".equals(t.getPartOfSpeechLevel2());
            if (!trailing) return PHRASE;
        }
        if (suruNoun) return "Động từ nhóm III";
        String form = head.getConjugationType();
        return switch (head.getPartOfSpeechLevel1()) {
            case "動詞" -> form.startsWith("五段") ? "Động từ nhóm I"
                    : form.startsWith("一段") ? "Động từ nhóm II"
                    : form.startsWith("カ変") || form.startsWith("サ変") ? "Động từ nhóm III" : "Động từ";
            case "形容詞" -> "Tính từ đuôi い";
            case "名詞" -> "形容動詞語幹".equals(head.getPartOfSpeechLevel2()) ? "Tính từ đuôi な"
                    : "代名詞".equals(head.getPartOfSpeechLevel2()) ? "Đại từ" : "Danh từ";
            case "副詞" -> "Phó từ";
            case "連体詞" -> "Liên thể từ";
            case "接続詞" -> "Liên từ";
            case "感動詞", "フィラー" -> content.size() == 1 ? "Thán từ" : PHRASE;
            case "接頭詞" -> "Tiền tố";
            case "助詞" -> content.size() == 1 ? "Trợ từ" : PHRASE;
            case "助動詞" -> "Trợ động từ";
            default -> null;
        };
    }
}
