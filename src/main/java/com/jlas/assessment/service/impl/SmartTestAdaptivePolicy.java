package com.jlas.assessment.service.impl;

import java.util.List;

/** Quyết định level tiếp theo cho một kỹ năng sau mỗi batch 5 câu. */
final class SmartTestAdaptivePolicy {

    static final int BATCH_SIZE = 5;
    static final int MAX_QUESTIONS_PER_SKILL = 25;
    static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");

    Decision decide(List<BatchScore> history) {
        return decide(history, "N1");
    }

    /**
     * [JLAS-W0108] Owner 19/11: a skill never moves above {@code ceilingLevel} (the learner's self-reported level);
     * passing a batch at the ceiling closes the skill there with {@code LEVEL_BOUNDARY_REACHED}.
     */
    Decision decide(List<BatchScore> history, String ceilingLevel) {
        int ceiling = LEVELS.indexOf(ceilingLevel);
        if (ceiling < 0) throw new IllegalArgumentException("Unknown JLPT level: " + ceilingLevel);
        if (history.isEmpty()) throw new IllegalArgumentException("Batch history must not be empty");

        BatchScore current = history.get(history.size() - 1);
        int confirmationIndex = confirmationIndex(history);
        if (confirmationIndex >= 0 && history.size() - 1 > confirmationIndex) {
            BatchScore confirmation = history.get(confirmationIndex);
            if (LEVELS.indexOf(current.level()) < LEVELS.indexOf(confirmation.level())) {
                // A downward probe closes the skill at the tested lower level.
                // Never infer another, untested lower level after confirmation.
                return Decision.finish(current.level(), "POST_CONFIRMATION_DOWNWARD_PROBE_FINISHED");
            }
            // A higher-level probe must be clearly passed. Three correct answers
            // still describe strong mastery of the proven lower level, not enough
            // evidence to award the harder level.
            if (current.correctAnswers() >= 4) {
                return Decision.finish(current.level(), "POST_CONFIRMATION_PROBE_PASSED");
            }
            return Decision.finish(confirmation.level(), "POST_CONFIRMATION_PROBE_FELL_BACK");
        }

        boolean confirmationBatch = history.size() >= 2
                && history.get(history.size() - 2).level().equals(current.level())
                && history.get(history.size() - 2).correctAnswers() == 3;

        if (confirmationBatch && current.correctAnswers() >= 2 && current.correctAnswers() <= 3) {
            return Decision.finish(current.level(), "CONFIRMED_SAME_LEVEL");
        }

        int direction = direction(current.correctAnswers());
        if (direction == 0) {
            if (history.size() * BATCH_SIZE >= MAX_QUESTIONS_PER_SKILL) {
                return Decision.finish(current.level(), "MAX_QUESTIONS_REACHED");
            }
            return Decision.continueAt(current.level(), "NEEDS_SAME_LEVEL_CONFIRMATION");
        }

        String targetLevel = shift(current.level(), direction, ceiling);
        if (targetLevel.equals(current.level())) {
            return Decision.finish(current.level(), "LEVEL_BOUNDARY_REACHED");
        }

        int lastMovement = lastLevelMovement(history);
        if (lastMovement != 0 && direction == -lastMovement) {
            return Decision.finish(easierOf(current.level(), targetLevel), "ABILITY_RANGE_STABLE");
        }
        if (history.size() * BATCH_SIZE >= MAX_QUESTIONS_PER_SKILL) {
            // Do not award a level that has not actually been tested.
            return Decision.finish(current.level(), "MAX_QUESTIONS_REACHED");
        }
        return Decision.continueAt(targetLevel, direction > 0 ? "MOVE_UP" : "MOVE_DOWN");
    }

    private static int direction(int correctAnswers) {
        if (correctAnswers <= 2) return -1;
        if (correctAnswers == 3) return 0;
        return 1;
    }

    private static int confirmationIndex(List<BatchScore> history) {
        for (int index = 1; index < history.size(); index++) {
            BatchScore first = history.get(index - 1);
            BatchScore confirmation = history.get(index);
            if (first.level().equals(confirmation.level()) && first.correctAnswers() == 3) {
                return index;
            }
        }
        return -1;
    }

    private static String shift(String level, int direction, int ceiling) {
        int index = LEVELS.indexOf(level);
        if (index < 0) throw new IllegalArgumentException("Unknown JLPT level: " + level);
        int top = Math.max(index, Math.min(LEVELS.size() - 1, ceiling));
        return LEVELS.get(Math.max(0, Math.min(top, index + direction)));
    }

    private static String easierOf(String first, String second) {
        int firstIndex = LEVELS.indexOf(first);
        int secondIndex = LEVELS.indexOf(second);
        if (firstIndex < 0 || secondIndex < 0) {
            throw new IllegalArgumentException("Unknown JLPT level");
        }
        return LEVELS.get(Math.min(firstIndex, secondIndex));
    }

    private static int lastLevelMovement(List<BatchScore> history) {
        int movement = 0;
        for (int index = 1; index < history.size(); index++) {
            int previous = LEVELS.indexOf(history.get(index - 1).level());
            int current = LEVELS.indexOf(history.get(index).level());
            if (current != previous) movement = Integer.compare(current, previous);
        }
        return movement;
    }

    record BatchScore(int batchNumber, String level, int correctAnswers) { }

    record Decision(boolean finished, String estimatedLevel, String nextLevel, String reason) {
        static Decision finish(String estimatedLevel, String reason) {
            return new Decision(true, estimatedLevel, null, reason);
        }

        static Decision continueAt(String nextLevel, String reason) {
            return new Decision(false, null, nextLevel, reason);
        }
    }
}
