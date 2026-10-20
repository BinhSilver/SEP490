package com.jlas.shadowing.util;

import com.jlas.shadowing.dto.SubtitleDto;

import java.util.ArrayList;
import java.util.List;

/** Splits oversized subtitle cues into study-friendly timed segments. */
public final class SubtitleSegmenter {

    static final int MAX_CODE_POINTS = 42;
    private static final int MIN_BALANCED_PART = 8;
    /** Shorter clips cut the speech off when a learner replays one line. */
    static final double MIN_CUE_SECONDS = 1.0;
    /** A short line held on screen through music or silence; beyond this its end is re-estimated. */
    static final double MAX_CUE_SECONDS = 15.0;
    private static final double MAX_MERGED_SECONDS = 8.0;
    private static final double SECONDS_PER_CODE_POINT = 0.25;
    private static final double SPEECH_TAIL_SECONDS = 1.5;

    private SubtitleSegmenter() {
    }

    /** Splits long lines, then keeps every line between {@link #MIN_CUE_SECONDS} and {@link #MAX_CUE_SECONDS}. */
    public static List<SubtitleDto> splitLongCues(List<SubtitleDto> cues) {
        if (cues == null || cues.isEmpty()) return List.of();

        List<SubtitleDto> result = new ArrayList<>();
        for (SubtitleDto cue : cues) {
            if (cue != null) result.addAll(splitCue(cue));
        }
        return ensureMinimumDuration(trimHeldCues(result));
    }

    /**
     * Rolling captions show a line when it is spoken, so a short line lasting more than 15 s is mostly
     * music or silence: end it after the time its text takes to say.
     */
    private static List<SubtitleDto> trimHeldCues(List<SubtitleDto> cues) {
        for (SubtitleDto cue : cues) {
            if (!hasValidTiming(cue) || cue.getEndTime() - cue.getStartTime() <= MAX_CUE_SECONDS) continue;
            double spoken = codePointLength(cue.getText() == null ? "" : cue.getText()) * SECONDS_PER_CODE_POINT
                    + SPEECH_TAIL_SECONDS;
            cue.setEndTime(cue.getStartTime() + Math.min(MAX_CUE_SECONDS, Math.max(MIN_CUE_SECONDS, spoken)));
        }
        return cues;
    }

    /**
     * Stretches a line shorter than {@link #MIN_CUE_SECONDS} into the silence around it; when there is no room,
     * merges it into the shorter neighbour if the result stays a readable line.
     */
    static List<SubtitleDto> ensureMinimumDuration(List<SubtitleDto> cues) {
        List<SubtitleDto> result = new ArrayList<>(cues);
        for (int index = 0; index < result.size(); index++) {
            SubtitleDto cue = result.get(index);
            if (!hasValidTiming(cue)) continue;
            double missing = MIN_CUE_SECONDS - (cue.getEndTime() - cue.getStartTime());
            if (missing <= 0) continue;

            SubtitleDto previous = index > 0 ? result.get(index - 1) : null;
            SubtitleDto next = index + 1 < result.size() ? result.get(index + 1) : null;
            double roomAfter = next != null && hasValidTiming(next)
                    ? Math.max(0, next.getStartTime() - cue.getEndTime()) : missing;
            double grow = Math.min(missing, roomAfter);
            cue.setEndTime(cue.getEndTime() + grow);
            missing -= grow;
            double roomBefore = previous != null && hasValidTiming(previous)
                    ? Math.max(0, cue.getStartTime() - previous.getEndTime()) : cue.getStartTime();
            grow = Math.min(missing, Math.max(0, roomBefore));
            cue.setStartTime(cue.getStartTime() - grow);
            missing -= grow;
            if (missing <= 1e-9) continue;

            SubtitleDto neighbour = shorterMergeable(cue, previous, next);
            if (neighbour == null) continue;
            boolean intoPrevious = neighbour == previous;
            SubtitleDto first = intoPrevious ? previous : cue;
            SubtitleDto second = intoPrevious ? cue : next;
            SubtitleDto merged = copyCue(first, first.getText().trim() + second.getText().trim(),
                    first.getStartTime(), second.getEndTime());
            merged.setFurigana(null);
            merged.setTranslation(null);
            int firstIndex = intoPrevious ? index - 1 : index;
            result.set(firstIndex, merged);
            result.remove(firstIndex + 1);
            index = firstIndex - 1;
        }
        return result;
    }

    private static SubtitleDto shorterMergeable(SubtitleDto cue, SubtitleDto previous, SubtitleDto next) {
        SubtitleDto best = null;
        for (SubtitleDto candidate : new SubtitleDto[] {previous, next}) {
            if (candidate == null || !hasValidTiming(candidate)) continue;
            double start = Math.min(cue.getStartTime(), candidate.getStartTime());
            double end = Math.max(cue.getEndTime(), candidate.getEndTime());
            int length = codePointLength(cue.getText() == null ? "" : cue.getText().trim())
                    + codePointLength(candidate.getText() == null ? "" : candidate.getText().trim());
            if (end - start > MAX_MERGED_SECONDS || length > MAX_CODE_POINTS) continue;
            if (best == null || candidate.getEndTime() - candidate.getStartTime()
                    < best.getEndTime() - best.getStartTime()) {
                best = candidate;
            }
        }
        return best;
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
            // A split part covers only some of the sentence: drop the whole-cue furigana/translation
            // so AI fills them per part instead of repeating the full sentence on every fragment.
            SubtitleDto piece = copyCue(cue, part, partStart, partEnd);
            piece.setFurigana(null);
            piece.setTranslation(null);
            result.add(piece);
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
