package com.jlas.shadowing.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches Japanese answers without forcing the learner to use one exact mix of kanji and kana.
 * Only readings explicitly attached to the expected kanji are accepted, so an unrelated
 * homophonous kanji is not treated as correct.
 */
final class JapaneseAnswerMatcher {
    private static final Pattern FURIGANA_ANNOTATION = Pattern.compile(
            "([\\p{IsHan}\\u3005\\u3006\\u30F5\\u30F6]+)[\\uFF08(]([^\\uFF09)]+)[\\uFF09)]");

    private JapaneseAnswerMatcher() {
    }

    static MatchResult matchSentence(String expected, String furigana, String actual) {
        List<Segment> segments = sentenceSegments(expected, furigana);
        return match(expected, actual, segments);
    }

    static MatchResult matchCloze(String sentence, String furigana, String expected,
                                   String expectedReading, int occurrence, String actual) {
        List<Segment> segments = clozeSegments(sentence, furigana, expected, occurrence);
        if (segments.isEmpty()) {
            segments = singleSegment(expected, expectedReading);
        } else if (isUsableReading(expectedReading)) {
            String normalizedActual = normalizeKana(actual);
            if (normalizeKana(expectedReading).equals(normalizedActual)) {
                return result(expected, actual, MatchKind.EQUIVALENT, segments);
            }
        }
        return match(expected, actual, segments);
    }

    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).codePoints()
                .filter(cp -> !isIgnored(cp))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private static MatchResult match(String expected, String actual, List<Segment> segments) {
        String normalizedExpected = normalize(expected);
        String normalizedActual = normalize(actual);
        if (normalizedExpected.equals(normalizedActual)) {
            return result(expected, actual, MatchKind.EXACT_SURFACE, segments);
        }
        if (matchesSegments(segments, normalizeKana(actual))) {
            return result(expected, actual, MatchKind.EQUIVALENT, segments);
        }
        return result(expected, actual, MatchKind.NONE, segments);
    }

    private static MatchResult result(String expected, String actual, MatchKind kind, List<Segment> segments) {
        int score = kind == MatchKind.NONE
                ? similarityScore(segments, normalizeKana(actual))
                : 100;
        return new MatchResult(kind, normalize(expected), normalize(actual), score);
    }

    private static List<Segment> sentenceSegments(String expected, String furigana) {
        ParsedSentence parsed = parse(furigana);
        if (parsed.segments().isEmpty() || !normalize(parsed.surface()).equals(normalize(expected))) {
            return singleSegment(expected, "");
        }
        return parsed.segments();
    }

    private static List<Segment> clozeSegments(String sentence, String furigana,
                                               String expected, int occurrence) {
        ParsedSentence parsed = parse(furigana);
        if (parsed.segments().isEmpty() || !parsed.surface().equals(sentence)) return List.of();

        int start = nthIndexOf(sentence, expected, occurrence);
        if (start < 0) return List.of();
        int end = start + expected.length();
        List<Segment> selected = new ArrayList<>();
        int cursor = 0;
        for (Segment segment : parsed.segments()) {
            int segmentStart = cursor;
            int segmentEnd = cursor + segment.surface().length();
            cursor = segmentEnd;
            if (segmentEnd <= start || segmentStart >= end) continue;

            int localStart = Math.max(start, segmentStart) - segmentStart;
            int localEnd = Math.min(end, segmentEnd) - segmentStart;
            if (localStart == 0 && localEnd == segment.surface().length()) {
                selected.add(segment);
            } else {
                selected.add(new Segment(segment.surface().substring(localStart, localEnd), List.of()));
            }
        }
        String selectedSurface = selected.stream().map(Segment::surface)
                .collect(java.util.stream.Collectors.joining());
        return selectedSurface.equals(expected) ? selected : List.of();
    }

    private static ParsedSentence parse(String annotated) {
        if (annotated == null || annotated.isBlank()) return new ParsedSentence("", List.of());
        Matcher matcher = FURIGANA_ANNOTATION.matcher(annotated);
        List<Segment> segments = new ArrayList<>();
        StringBuilder surface = new StringBuilder();
        int cursor = 0;
        boolean foundAnnotation = false;
        while (matcher.find()) {
            addLiteral(segments, surface, annotated.substring(cursor, matcher.start()));
            String kanji = matcher.group(1);
            String reading = matcher.group(2);
            segments.add(new Segment(kanji, List.of(reading)));
            surface.append(kanji);
            cursor = matcher.end();
            foundAnnotation = true;
        }
        addLiteral(segments, surface, annotated.substring(cursor));
        return foundAnnotation ? new ParsedSentence(surface.toString(), segments)
                : new ParsedSentence(annotated, List.of());
    }

    private static void addLiteral(List<Segment> segments, StringBuilder surface, String literal) {
        if (literal.isEmpty()) return;
        segments.add(new Segment(literal, List.of()));
        surface.append(literal);
    }

    private static List<Segment> singleSegment(String expected, String reading) {
        List<String> readings = isUsableReading(reading) ? List.of(reading) : List.of();
        return List.of(new Segment(expected == null ? "" : expected, readings));
    }

    private static boolean isUsableReading(String reading) {
        return reading != null && !normalize(reading).isBlank()
                && normalize(reading).codePoints()
                .noneMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private static boolean matchesSegments(List<Segment> segments, String actual) {
        int[] actualCodePoints = actual.codePoints().toArray();
        Set<Integer> positions = new LinkedHashSet<>();
        positions.add(0);
        for (Segment segment : segments) {
            if (segment.options().isEmpty()) continue;
            Set<Integer> next = new LinkedHashSet<>();
            for (String option : segment.options()) {
                int[] optionCodePoints = option.codePoints().toArray();
                for (int position : positions) {
                    if (startsWith(actualCodePoints, position, optionCodePoints)) {
                        next.add(position + optionCodePoints.length);
                    }
                }
            }
            if (next.isEmpty()) return false;
            positions = next;
        }
        return positions.contains(actualCodePoints.length);
    }

    private static boolean startsWith(int[] value, int offset, int[] prefix) {
        if (offset + prefix.length > value.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[offset + index] != prefix[index]) return false;
        }
        return true;
    }

    private static int similarityScore(List<Segment> segments, String actual) {
        int[] actualCodePoints = actual.codePoints().toArray();
        int[] row = new int[actualCodePoints.length + 1];
        for (int index = 0; index < row.length; index++) row[index] = index;
        int expectedLength = 0;

        for (Segment segment : segments) {
            int[] best = null;
            int shortestOption = Integer.MAX_VALUE;
            for (String option : segment.options()) {
                int[] optionCodePoints = option.codePoints().toArray();
                shortestOption = Math.min(shortestOption, optionCodePoints.length);
                int[] candidate = appendLevenshtein(row, optionCodePoints, actualCodePoints);
                if (best == null) {
                    best = candidate;
                } else {
                    for (int index = 0; index < best.length; index++) {
                        best[index] = Math.min(best[index], candidate[index]);
                    }
                }
            }
            row = best == null ? row : best;
            expectedLength += shortestOption == Integer.MAX_VALUE ? 0 : shortestOption;
        }

        int length = Math.max(1, Math.max(expectedLength, actualCodePoints.length));
        return clampScore((int) Math.round((1.0 - row[actualCodePoints.length] / (double) length) * 100));
    }

    private static int[] appendLevenshtein(int[] startingRow, int[] option, int[] actual) {
        int[] previous = startingRow.clone();
        for (int expected : option) {
            int[] current = new int[actual.length + 1];
            current[0] = previous[0] + 1;
            for (int index = 1; index <= actual.length; index++) {
                int replaceCost = expected == actual[index - 1] ? 0 : 1;
                current[index] = Math.min(current[index - 1] + 1,
                        Math.min(previous[index] + 1, previous[index - 1] + replaceCost));
            }
            previous = current;
        }
        return previous;
    }

    private static int nthIndexOf(String text, String needle, int occurrence) {
        if (text == null || needle == null || needle.isEmpty()) return -1;
        int offset = 0;
        for (int current = 1; current <= Math.max(1, occurrence); current++) {
            offset = text.indexOf(needle, offset);
            if (offset < 0) return -1;
            if (current == occurrence) return offset;
            offset += needle.length();
        }
        return -1;
    }

    private static String normalizeKana(String value) {
        return normalize(value).codePoints()
                .map(JapaneseAnswerMatcher::katakanaToHiragana)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private static int katakanaToHiragana(int codePoint) {
        if ((codePoint >= 0x30A1 && codePoint <= 0x30F6)
                || codePoint == 0x30FD || codePoint == 0x30FE) {
            return codePoint - 0x60;
        }
        return codePoint;
    }

    private static boolean isIgnored(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint) || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION;
    }

    private static int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    enum MatchKind {
        EXACT_SURFACE,
        EQUIVALENT,
        NONE
    }

    record MatchResult(MatchKind kind, String normalizedExpected, String normalizedActual, int score) {
        boolean isCorrect() {
            return kind != MatchKind.NONE;
        }

        boolean usesEquivalentWriting() {
            return kind == MatchKind.EQUIVALENT;
        }
    }

    private record ParsedSentence(String surface, List<Segment> segments) {
    }

    private record Segment(String surface, List<String> readings) {
        List<String> options() {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            String normalizedSurface = normalizeKana(surface);
            if (!normalizedSurface.isEmpty()) result.add(normalizedSurface);
            readings.stream().map(JapaneseAnswerMatcher::normalizeKana)
                    .filter(value -> !value.isEmpty()).forEach(result::add);
            return List.copyOf(result);
        }
    }
}
