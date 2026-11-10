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
 */
public final class LibrarySearch {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー]");

    private static final int PHRASE_TITLE = 100, PHRASE_TAG = 60, PHRASE_TEXT = 40;
    private static final int TERM_FOUND = 20, TERM_TITLE = 10, TERM_TAG = 6, TERM_TEXT = 3, TEXT_LINES_CAP = 5;

    private final boolean exactMarks;
    private final List<String> terms;
    private final String phrase;

    private LibrarySearch(String query) {
        exactMarks = hasMarks(query);
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (String raw : query.trim().split("\\s+")) {
            String word = trimPunctuation(normalize(raw));
            if (!word.isEmpty()) words.add(word);
        }
        terms = List.copyOf(words);
        phrase = terms.size() > 1 ? String.join(" ", terms) : null;
    }

    /** Null when there is nothing to search for. */
    public static LibrarySearch of(String query) {
        if (query == null || query.isBlank()) return null;
        LibrarySearch search = new LibrarySearch(query);
        return search.terms.isEmpty() ? null : search;
    }

    /** Words to pre-filter subtitle rows with (SQL LIKE); the exact rule is applied afterwards by {@link #contains}. */
    public List<String> terms() { return terms; }

    /**
     * Relevance of one item; 0 means it does not match. {@code texts} are subtitle lines (or a description) that
     * already passed the SQL pre-filter.
     */
    public int score(String title, Collection<String> tags, Collection<String> texts) {
        String t = normalize(title);
        List<String> tagList = new ArrayList<>();
        if (tags != null) tags.forEach(tag -> tagList.add(normalize(tag)));
        List<String> textList = new ArrayList<>();
        if (texts != null) texts.forEach(text -> textList.add(normalize(text)));

        int score = 0, found = 0;
        for (String term : terms) {
            boolean inTitle = contains(t, term);
            boolean inTag = tagList.stream().anyMatch(tag -> contains(tag, term));
            long lines = textList.stream().filter(text -> contains(text, term)).count();
            if (!inTitle && !inTag && lines == 0) continue;
            found++;
            score += TERM_FOUND + (inTitle ? TERM_TITLE : 0) + (inTag ? TERM_TAG : 0)
                    + (lines > 0 ? TERM_TEXT + (int) Math.min(lines, TEXT_LINES_CAP) : 0);
        }
        if (found == 0) return 0;
        if (phrase != null) {
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
        String text = Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (exactMarks) return text;
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("").replace('đ', 'd');
    }

    private static boolean hasMarks(String query) {
        String nfd = Normalizer.normalize(query, Normalizer.Form.NFD);
        return MARKS.matcher(nfd).find() || query.indexOf('đ') >= 0 || query.indexOf('Đ') >= 0;
    }

    private static String trimPunctuation(String word) {
        int start = 0, end = word.length();
        while (start < end && !Character.isLetterOrDigit(word.codePointAt(start))) start += Character.charCount(word.codePointAt(start));
        while (end > start && !Character.isLetterOrDigit(word.codePointBefore(end))) end -= Character.charCount(word.codePointBefore(end));
        return word.substring(start, end);
    }
}
