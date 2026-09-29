package com.jlas.shadowing.util;

import com.jlas.shadowing.dto.SubtitleDto;

import java.util.ArrayList;
import java.util.List;

/** Splits oversized subtitle cues into study-friendly timed segments. */
public final class SubtitleSegmenter {

    static final int MAX_CODE_POINTS = 42;
    private static final int MIN_BALANCED_PART = 8;

    private SubtitleSegmenter() {
    }

    public static List<SubtitleDto> splitLongCues(List<SubtitleDto> cues) {
        if (cues == null || cues.isEmpty()) return List.of();

        List<SubtitleDto> result = new ArrayList<>();
        for (SubtitleDto cue : cues) {
            if (cue != null) result.addAll(splitCue(cue));
        }
        return result;
    }

    private static List<SubtitleDto> splitCue(SubtitleDto cue) {
        String text = cue.getText() == null ? "" : cue.getText().trim();
        if (codePointLength(text) <= MAX_CODE_POINTS || !hasValidTiming(cue)) {
            return List.of(copyCue(cue, text, cue.getStartTime(), cue.getEndTime()));
        }

        List<String> parts = splitText(text);
        if (parts.size() <= 1) {
            return List.of(copyCue(cue, text, cue.getStartTime(), cue.getEndTime()));
        }

        int totalWeight = parts.stream().mapToInt(SubtitleSegmenter::codePointLength).sum();
        double start = cue.getStartTime();
        double end = cue.getEndTime();
        double duration = end - start;
        int consumedWeight = 0;
        List<SubtitleDto> result = new ArrayList<>(parts.size());

        for (int index = 0; index < parts.size(); index++) {
            String part = parts.get(index);
            double partStart = index == 0
                    ? start
                    : start + duration * consumedWeight / totalWeight;
            consumedWeight += codePointLength(part);
            double partEnd = index == parts.size() - 1
                    ? end
                    : start + duration * consumedWeight / totalWeight;
            result.add(copyCue(cue, part, partStart, partEnd));
        }
        return result;
    }

    private static List<String> splitText(String text) {
        List<String> sentences = splitAtSentenceEnd(text);
        List<String> result = new ArrayList<>();
        for (String sentence : sentences) {
            result.addAll(splitOversizedPart(sentence));
        }
        return result;
    }

    private static List<String> splitAtSentenceEnd(String text) {
        List<String> result = new ArrayList<>();
        int partStart = 0;
        int offset = 0;

        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!isSentenceEnd(text, offset, codePoint)) continue;

            while (offset < text.length()) {
                int next = text.codePointAt(offset);
                if (!isClosingMark(next)) break;
                offset += Character.charCount(next);
            }
            addTrimmed(result, text.substring(partStart, offset));
            partStart = offset;
        }
        addTrimmed(result, text.substring(partStart));
        return result;
    }

    private static List<String> splitOversizedPart(String text) {
        List<String> result = new ArrayList<>();
        String remaining = text.trim();

        while (codePointLength(remaining) > MAX_CODE_POINTS) {
            int length = codePointLength(remaining);
            int desired = length - MAX_CODE_POINTS <= MIN_BALANCED_PART
                    ? (length + 1) / 2
                    : MAX_CODE_POINTS;
            int splitAt = preferredBreak(remaining, desired);
            addTrimmed(result, substringByCodePoints(remaining, 0, splitAt));
            remaining = substringByCodePoints(remaining, splitAt, length).trim();
        }
        addTrimmed(result, remaining);
        return result;
    }

    private static int preferredBreak(String text, int desired) {
        int length = codePointLength(text);
        int lower = Math.max(MIN_BALANCED_PART, desired - 14);
        int upper = Math.min(MAX_CODE_POINTS, length - MIN_BALANCED_PART);
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;

        for (int position = lower; position <= upper; position++) {
            int previous = text.codePointBefore(text.offsetByCodePoints(0, position));
            if (!isSoftBreak(previous)) continue;
            int distance = Math.abs(position - desired);
            if (distance < bestDistance || distance == bestDistance && position < best) {
                best = position;
                bestDistance = distance;
            }
        }
        return best > 0 ? best : desired;
    }

    private static boolean hasValidTiming(SubtitleDto cue) {
        return cue.getStartTime() != null && cue.getEndTime() != null
                && Double.isFinite(cue.getStartTime()) && Double.isFinite(cue.getEndTime())
                && cue.getEndTime() > cue.getStartTime();
    }

    private static boolean isSentenceEnd(String text, int nextOffset, int codePoint) {
        if (codePoint == '。' || codePoint == '！' || codePoint == '？'
                || codePoint == '!' || codePoint == '?') {
            return true;
        }
        if (codePoint != '.') return false;
        int previous = nextOffset - Character.charCount(codePoint) - 1;
        boolean digitBefore = previous >= 0 && Character.isDigit(text.charAt(previous));
        boolean digitAfter = nextOffset < text.length() && Character.isDigit(text.charAt(nextOffset));
        return !(digitBefore && digitAfter);
    }

    private static boolean isClosingMark(int codePoint) {
        return codePoint == '」' || codePoint == '』' || codePoint == '）'
                || codePoint == '】' || codePoint == '”' || codePoint == '’';
    }

    private static boolean isSoftBreak(int codePoint) {
        return Character.isWhitespace(codePoint) || codePoint == '、' || codePoint == '，'
                || codePoint == ',' || codePoint == '；' || codePoint == ';'
                || codePoint == '：' || codePoint == ':';
    }

    private static SubtitleDto copyCue(SubtitleDto source, String text, Double start, Double end) {
        SubtitleDto copy = new SubtitleDto();
        copy.setText(text);
        copy.setFurigana(source.getFurigana());
        copy.setTranslation(source.getTranslation());
        copy.setStartTime(start);
        copy.setEndTime(end);
        return copy;
    }

    private static String substringByCodePoints(String value, int start, int end) {
        int startOffset = value.offsetByCodePoints(0, start);
        int endOffset = value.offsetByCodePoints(0, end);
        return value.substring(startOffset, endOffset);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static void addTrimmed(List<String> result, String value) {
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) result.add(trimmed);
    }
}
