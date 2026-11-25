package com.jlas.dictionary.util;

import com.jlas.jlptpath.entity.JpathGrammar;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text operations shared by grammar search and textbook matching. */
public final class GrammarText {
    private static final Pattern FURIGANA = Pattern.compile("([\\p{IsHan}々〆ヶ]+)\\(([^()]+)\\)");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)(?:<[^>]+>|\\{[^}]+}|\\[(?:N|V|A)[^]]*]"
            + "|(?<![A-Za-z])(?:N|V|A)(?:[1-9]|る|た|て|ない|ます)?(?=$|[+＋〜～~…\\sぁ-んァ-ン])"
            + "|(?:名詞|動詞|い形容詞|な形容詞|形容詞|辞書形|普通形)(?=$|[+＋〜～~…\\sぁ-んァ-ン]))");
    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHan}々ぁ-んァ-ンー]+", Pattern.UNICODE_CHARACTER_CLASS);

    private GrammarText() {}

    public static String normalize(String text) {
        if (text == null) return "";
        return PLACEHOLDER.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC))
                .replaceAll("").replaceAll("[\\s\\u3000〜～~…・+＋]", "")
                .toLowerCase(Locale.ROOT);
    }

    public static String core(String pattern, String familyKey) {
        String best = "";
        for (String source : List.of(pattern == null ? "" : pattern, familyKey == null ? "" : familyKey)) {
            Matcher matcher = JAPANESE.matcher(normalize(source));
            while (matcher.find()) if (matcher.group().length() > best.length()) best = matcher.group();
        }
        return best;
    }

    public static int rank(JpathGrammar grammar, List<String> variants, String query) {
        String q = normalize(query);
        if (q.isEmpty()) return Integer.MAX_VALUE;
        List<String> patterns = new ArrayList<>();
        patterns.add(normalize(grammar.getPattern()));
        patterns.add(normalize(grammar.getFamilyKey()));
        variants.forEach(value -> patterns.add(normalize(value)));
        if (patterns.stream().anyMatch(p -> p.equals(q))) return 0;
        if (patterns.stream().anyMatch(p -> p.startsWith(q))) return 1;
        if (patterns.stream().anyMatch(p -> p.contains(q))) return 2;
        return normalize(grammar.getMeaningVi()).contains(q) ? 3 : Integer.MAX_VALUE;
    }

    public record Token(String text, String reading) {}

    public static List<Token> furiganaTokens(String marked, String plain) {
        String source = marked == null || marked.isBlank() ? plain : marked;
        if (source == null || source.isEmpty()) return List.of();
        List<Token> result = new ArrayList<>();
        Matcher matcher = FURIGANA.matcher(source);
        int previous = 0;
        while (matcher.find()) {
            if (matcher.start() > previous) result.add(new Token(source.substring(previous, matcher.start()), null));
            result.add(new Token(matcher.group(1), matcher.group(2)));
            previous = matcher.end();
        }
        if (previous < source.length()) result.add(new Token(source.substring(previous), null));
        return result;
    }
}
