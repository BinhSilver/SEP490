package com.jlas.common.learning;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [JLAS-W0108] D3-04 (owner 2026-10-24): the J-LAS compiled book of a level — one Learning-owned textbook that merges
 * every book of the level per skill ({@code LP-OWNED-JLAS-N5-COMPILED}, chapters {@code …-COMPILED-C01}). When a level
 * has one, the JLPT path reads only it; the source books are provenance in the data, never a second source.
 *
 * <p>Its Kanji are the book's own per-chapter list ({@code lesson_kanji}), and its Reading/Listening questions are the
 * checked Minna/Speed Master groups copied under the chapter code {@code LP-OWNED-JLAS-N5-C01-READ-P1-…}.
 */
public final class CompiledBookPolicy {
    private static final Pattern BOOK = Pattern.compile("^LP-OWNED-JLAS-(N[1-5])-COMPILED$");
    private static final Pattern CHAPTER = Pattern.compile("^LP-OWNED-JLAS-(N[1-5])-COMPILED-C(\\d{2})$");
    /** A copied question: its group (chapter or store pool, skill, passage/clip) is everything before the source's own
     * suffix. §2.32: a store passage not placed in any chapter is coded {@code LP-OWNED-JLAS-N4-POOL-READ-P12-…}. */
    private static final Pattern QUESTION_GROUP =
            Pattern.compile("^(LP-OWNED-JLAS-(N[1-5])-(?:C\\d{2}|POOL)-(READ|LISTEN)-P\\d+)-.+$");
    /** §2.32: only these store reviews may enter the required part of a path; DRAFT goes to extra practice, HOLD nowhere. */
    public static final java.util.Set<String> REQUIRED_REVIEWS = java.util.Set.of("ACCEPTED", "REVIEWED");
    private static final Pattern QUESTION_CHAPTER = Pattern.compile("^LP-OWNED-JLAS-N[1-5]-C(\\d{2})-(?:READ|LISTEN)-P\\d+-");

    private CompiledBookPolicy() {
    }

    public static boolean isCompiledBook(String textbookSourceCode) {
        return textbookSourceCode != null && BOOK.matcher(textbookSourceCode).matches();
    }

    /** Chapter number of a compiled-book lesson, from its source code; empty for any other lesson. */
    public static Optional<Integer> chapterOf(String lessonSourceCode) {
        if (lessonSourceCode == null) return Optional.empty();
        Matcher matcher = CHAPTER.matcher(lessonSourceCode);
        return matcher.matches() ? Optional.of(Integer.parseInt(matcher.group(2))) : Optional.empty();
    }

    /** Source-code prefix of the questions a compiled chapter carries for READING or LISTENING. */
    public static String questionPrefix(String level, int chapter, String skill) {
        return "LP-OWNED-JLAS-" + level + "-C" + String.format(Locale.ROOT, "%02d", chapter)
                + ("READING".equals(skill) ? "-READ-" : "-LISTEN-");
    }

    /** §2.32: source-code prefix of the store passages/clips of a level that sit in no chapter (extra practice, gap fill). */
    public static String poolPrefix(String level, String skill) {
        return "LP-OWNED-JLAS-" + level + "-POOL" + ("READING".equals(skill) ? "-READ-" : "-LISTEN-");
    }

    /** The passage/clip group of a copied question at {@code level} for {@code skill}; null for any other code. */
    public static String questionGroup(String questionSourceCode, String skill, String level) {
        if (questionSourceCode == null) return null;
        Matcher matcher = QUESTION_GROUP.matcher(questionSourceCode);
        if (!matcher.matches() || !matcher.group(2).equals(level)) return null;
        return matcher.group(3).equals("READING".equals(skill) ? "READ" : "LISTEN") ? matcher.group(1) : null;
    }

    /** §2.32: the chapter a copied question was placed in ({@code …-C06-READ-…} → 6); empty for a store pool row or any
     * other code. */
    public static Optional<Integer> chapterOfQuestion(String questionSourceCode) {
        if (questionSourceCode == null) return Optional.empty();
        Matcher matcher = QUESTION_CHAPTER.matcher(questionSourceCode);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    /** §2.32: level of a copied question or store row ({@code LP-OWNED-JLAS-N4-…} → N4); null for any other code. */
    public static String levelOfQuestion(String questionSourceCode) {
        if (questionSourceCode == null) return null;
        Matcher matcher = QUESTION_GROUP.matcher(questionSourceCode);
        return matcher.matches() ? matcher.group(2) : null;
    }

    /**
     * The N3 compiled book copies its dialogues from the owner-opened DRAFT N3 book, which has no recorded audio yet;
     * like that book (§2.28), its lines use the labelled synthesized voice instead of blocking the chapter.
     */
    public static boolean synthesizesMissingDialogueAudio(String textbookSourceCode, String jlptLevel) {
        return isCompiledBook(textbookSourceCode) && jlptLevel != null
                && DraftStudySourcePolicy.OPEN_DRAFT_LEVELS.contains(jlptLevel);
    }
}
