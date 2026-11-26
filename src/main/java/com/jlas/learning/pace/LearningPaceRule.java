package com.jlas.learning.pace;

/** [JLAS-W0108] Shared decisions for the initial and weekly pace of both learning paths. */
public final class LearningPaceRule {
    private LearningPaceRule() {}

    public enum Direction { INCREASE, KEEP, DECREASE }

    public static int initialJlptWords(int baseline, Integer vocab, Integer kanji, Integer grammar) {
        if (vocab == null || kanji == null || grammar == null) return clamp(baseline, 5, 50);
        double mean = (vocab + kanji + grammar) / 3.0;
        return clamp(baseline + (mean >= 80 ? 2 : mean < 50 ? -2 : 0), 5, 50);
    }

    public static int initialCommSituations(int baseline, String selfLevel) {
        int offset = switch (selfLevel == null ? "" : selfLevel) {
            case "BEGINNER" -> -1;
            case "INTERMEDIATE" -> 1;
            default -> 0;
        };
        return clamp(baseline + offset, 1, 4);
    }

    public static Direction weekly(int firstScore, boolean weekComplete) {
        if (!weekComplete) return Direction.KEEP;
        if (firstScore >= 90) return Direction.INCREASE;
        if (firstScore < 80) return Direction.DECREASE;
        return Direction.KEEP;
    }

    public static int proposedJlptWords(int current, Direction direction) {
        return clamp(current + delta(direction, 2), 5, 50);
    }

    public static int proposedCommSituations(int current, Direction direction) {
        return clamp(current + delta(direction, 1), 1, 4);
    }

    private static int delta(Direction direction, int step) {
        return direction == Direction.INCREASE ? step : direction == Direction.DECREASE ? -step : 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
