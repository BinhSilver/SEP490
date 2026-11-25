package com.jlas.shadowing.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Library keyword search: a word only counts as a whole word ("ga" does not match "gặp" or "ngắn"), the whole phrase
 * ranks above scattered words, and the score orders the results. Japanese has no spaces, so a term in Japanese script
 * still matches inside a sentence. A query typed without any Vietnamese diacritic also matches text that has them
 * ("ga tau" finds "ga tàu"); a query typed with diacritics matches them exactly ("ga" does not find "gà").
 * <p>
 * Ways of writing the same Japanese word are treated as one: katakana matches hiragana (コーヒー = こーひー), and a word
 * typed in romaji also searches its kana ("arigatou" finds ありがとう). Diacritics are only folded on Latin letters, so
 * the dakuten of が is never stripped to か.
 */
public final class LibrarySearch {
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー]");
    /**
     * Short words ("toi", "noi", "hai") are valid romaji but far more often Vietnamese typed without diacritics, so only
     * words of 4+ letters are also searched as kana.
     */
    private static final int ROMAJI_MIN_LETTERS = 4, ROMAJI_MIN_KANA = 2;

    private static final int PHRASE_TITLE = 100, PHRASE_TAG = 60, PHRASE_TEXT = 40;
    private static final int TERM_FOUND = 20, TERM_TITLE = 10, TERM_TAG = 6, TERM_TEXT = 3, TEXT_LINES_CAP = 5;

    private final boolean exactMarks;
    /** One entry per typed word: its normalized form, then its kana form when it was romaji. */
    private final List<List<String>> words;
    private final List<String> phrases;

    private LibrarySearch(String query) {
        exactMarks = hasLatinMarks(query);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<List<String>> parsed = new ArrayList<>();
        for (String raw : query.trim().split("\\s+")) {
            String word = trimPunctuation(normalize(raw));
            if (word.isEmpty() || !seen.add(word)) continue;
            List<String> forms = new ArrayList<>();
            forms.add(word);
            String kana = romajiKana(trimPunctuation(raw));
            if (kana != null && !kana.equals(word)) forms.add(kana);
            parsed.add(List.copyOf(forms));
        }
        words = List.copyOf(parsed);
        List<String> phraseForms = new ArrayList<>();
        if (words.size() > 1) {
            phraseForms.add(String.join(" ", words.stream().map(forms -> forms.get(0)).toList()));
            // "arigatou gozaimasu" also as one kana phrase ありがとうございます (Japanese has no spaces).
            if (words.stream().allMatch(forms -> forms.size() > 1)) {
                phraseForms.add(String.join("", words.stream().map(forms -> forms.get(1)).toList()));
            }
        }
        phrases = List.copyOf(phraseForms);
    }

    /** Null when there is nothing to search for. */
    public static LibrarySearch of(String query) {
        if (query == null || query.isBlank()) return null;
        LibrarySearch search = new LibrarySearch(query);
        return search.words.isEmpty() ? null : search;
    }

    /**
     * Words to pre-filter subtitle rows with (SQL LIKE), every written form included; the exact rule is applied
     * afterwards by {@link #contains}.
     */
    public List<String> terms() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        words.forEach(all::addAll);
        return List.copyOf(all);
    }

    /**
     * Relevance of one item; 0 means it does not match. {@code texts} are subtitle lines and their translations (or a
     * description) that already passed the SQL pre-filter.
     */
    public int score(String title, Collection<String> tags, Collection<String> texts) {
        String t = normalize(title);
        List<String> tagList = new ArrayList<>();
        if (tags != null) tags.forEach(tag -> tagList.add(normalize(tag)));
        List<String> textList = new ArrayList<>();
        if (texts != null) texts.forEach(text -> textList.add(normalize(text)));

        int score = 0, found = 0;
        for (List<String> forms : words) {
            boolean inTitle = forms.stream().anyMatch(form -> contains(t, form));
            boolean inTag = tagList.stream().anyMatch(tag -> forms.stream().anyMatch(form -> contains(tag, form)));
            long lines = textList.stream().filter(text -> forms.stream().anyMatch(form -> contains(text, form))).count();
            if (!inTitle && !inTag && lines == 0) continue;
            found++;
            score += TERM_FOUND + (inTitle ? TERM_TITLE : 0) + (inTag ? TERM_TAG : 0)
                    + (lines > 0 ? TERM_TEXT + (int) Math.min(lines, TEXT_LINES_CAP) : 0);
        }
        if (found == 0) return 0;
        for (String phrase : phrases) {
            if (contains(t, phrase)) score += PHRASE_TITLE;
            if (tagList.stream().anyMatch(tag -> contains(tag, phrase))) score += PHRASE_TAG;
            if (textList.stream().anyMatch(text -> contains(text, phrase))) score += PHRASE_TEXT;
        }
        return score;
    }

    /** {@code text} and {@code needle} must already be normalized by this search. */
    boolean contains(String text, String needle) {
        if (text.isEmpty() || needle.isEmpty()) return false;
        if (CJK.matcher(needle).find()) return text.contains(needle);
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            int end = at + needle.length();
            boolean startsWord = at == 0 || !Character.isLetterOrDigit(text.codePointBefore(at));
            boolean endsWord = end == text.length() || !Character.isLetterOrDigit(text.codePointAt(end));
            if (startsWord && endsWord) return true;
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) return "";
        String text = toHiragana(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll("\\s+", " ").trim();
        return exactMarks ? text : foldLatinMarks(text);
    }

    /** Romaji words of a useful length searched as kana too; null for anything else. */
    private static String romajiKana(String raw) {
        if (raw.length() < ROMAJI_MIN_LETTERS || !raw.chars().allMatch(c -> c < 128 && Character.isLetter(c))) return null;
        String kana = RomajiKana.toHiragana(raw);
        // No Japanese word starts with ん: "nhau", "nhieu" are Vietnamese, not romaji.
        return kana != null && kana.length() >= ROMAJI_MIN_KANA && kana.charAt(0) != 'ん' ? kana : null;
    }

    /** Katakana to hiragana, so either script finds the other. The long-vowel mark ー is kept. */
    static String toHiragana(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> out.appendCodePoint(cp >= 0x30A1 && cp <= 0x30F6 ? cp - 0x60 : cp));
        return out.toString();
    }

    /** Removes Vietnamese diacritics from Latin letters only (đ → d); marks on kana such as dakuten stay. */
    private static String foldLatinMarks(String text) {
        String nfd = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(nfd.length());
        int base = -1;
        for (int i = 0; i < nfd.length(); ) {
            int cp = nfd.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.NON_SPACING_MARK) {
                if (base >= 0 && Character.UnicodeScript.of(base) == Character.UnicodeScript.LATIN) continue;
            } else {
                base = cp;
            }
            out.appendCodePoint(cp);
        }
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC).replace('đ', 'd');
    }

    /** True when the query has a Vietnamese diacritic (a mark on a Latin letter, or đ). */
    private static boolean hasLatinMarks(String query) {
        if (query.indexOf('đ') >= 0 || query.indexOf('Đ') >= 0) return true;
        String nfd = Normalizer.normalize(query, Normalizer.Form.NFD);
        int base = -1;
        for (int i = 0; i < nfd.length(); ) {
            int cp = nfd.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.NON_SPACING_MARK) {
                if (base >= 0 && Character.UnicodeScript.of(base) == Character.UnicodeScript.LATIN) return true;
            } else {
                base = cp;
            }
        }
        return false;
    }

    private static String trimPunctuation(String word) {
        int start = 0, end = word.length();
        while (start < end && !Character.isLetterOrDigit(word.codePointAt(start))) start += Character.charCount(word.codePointAt(start));
        while (end > start && !Character.isLetterOrDigit(word.codePointBefore(end))) end -= Character.charCount(word.codePointBefore(end));
        return word.substring(start, end);
    }
}
