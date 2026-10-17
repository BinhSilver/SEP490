package com.jlas.common.learning;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Server-owned target matrix shared by Smart Test and Learning Path. */
public final class JlptPathPolicy {

    public enum Choice { ACCEPT_RECOMMENDATION, DECLINE_RECOMMENDATION }

    public static final List<String> TARGET_LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    private static final List<String> PUBLISHED_PATH_TARGETS = List.of("N5", "N4", "N3");
    private static final Map<String, Integer> RANK = Map.of(
            "BELOW_N5", 0, "N5", 1, "N4", 2, "N3", 3, "N2", 4, "N1", 5);

    public TargetEligibility evaluate(String assessedLevel, String requestedTarget) {
        String assessed = normalizeAssessed(assessedLevel);
        String requested = normalizeTarget(requestedTarget);
        if (!PUBLISHED_PATH_TARGETS.contains(requested)
                || (!"UNKNOWN".equals(assessed) && RANK.get(assessed) > RANK.get("N3"))) {
            return new TargetEligibility(false, requested, "N3", List.of(),
                    "PATH_NOT_SUPPORTED", true);
        }
        if ("UNKNOWN".equals(assessed)) {
            return new TargetEligibility(true, requested, requested, List.of(requested),
                    "PENDING_SMART_TEST", false);
        }

        int assessedRank = RANK.get(assessed);
        int targetRank = RANK.get(requested);
        boolean allowed = (assessedRank == 0 && (targetRank == 1 || targetRank == 2))
                || (assessedRank == 1 && (targetRank == 1 || targetRank == 2))
                || (assessedRank == 2 && (targetRank == 2 || targetRank == 3))
                || (assessedRank == 3 && targetRank == 3);
        if (allowed) {
            return new TargetEligibility(true, requested, requested, stages(assessed, requested),
                    null, false);
        }

        int recommendedRank = assessedRank == 0 ? 2
                : targetRank < assessedRank ? assessedRank : Math.min(3, assessedRank + 1);
        String recommended = levelAt(recommendedRank);
        return new TargetEligibility(false, requested, recommended, List.of(),
                targetRank < assessedRank ? "TARGET_BELOW_ASSESSED_LEVEL" : "TARGET_TOO_FAR",
                true);
    }

    public List<TargetEligibility> options(String assessedLevel) {
        return TARGET_LEVELS.stream().map(target -> evaluate(assessedLevel, target)).toList();
    }

    /** Full stage sequence after a learner explicitly resolves a lower-assessment discrepancy.
     * This does not change the seven directly selectable survey routes. */
    public List<String> confirmedStages(String surveyLevel, String assessedLevel,
            String requestedTarget, Choice choice) {
        String survey = normalizeAssessed(surveyLevel);
        String assessed = normalizeAssessed(assessedLevel);
        String target = normalizeTarget(requestedTarget);
        if (choice == null || "UNKNOWN".equals(survey) || "UNKNOWN".equals(assessed)
                || !RANK.containsKey(survey) || !RANK.containsKey(assessed)
                || RANK.get(assessed) >= RANK.get(survey)
                || !evaluate(survey, target).allowed()) {
            throw new IllegalArgumentException("No supported lower-assessment decision for this survey and target.");
        }
        String start = choice == Choice.ACCEPT_RECOMMENDATION ? assessed : survey;
        if ("BELOW_N5".equals(start) && "N3".equals(target)) {
            throw new IllegalArgumentException("A from-zero to N3 path is not supported.");
        }
        int first = RANK.get(start);
        int last = RANK.get(target);
        List<String> stages = new java.util.ArrayList<>();
        if (first == 0) stages.add("FOUNDATION");
        for (int rank = Math.max(1, first); rank <= last; rank++) {
            String level = levelAt(rank);
            if (rank > Math.max(1, first) && !evaluate(levelAt(rank - 1), level).allowed()) {
                throw new IllegalArgumentException("An intermediate JLPT stage is not supported.");
            }
            stages.add(level);
        }
        return List.copyOf(stages);
    }

    /** Immediate next target among the supported JLPT learning routes. */
    public Optional<String> nextTargetAfter(String completedTarget) {
        String completed = normalizeTarget(completedTarget);
        int index = PUBLISHED_PATH_TARGETS.indexOf(completed);
        return index < 0 || index == PUBLISHED_PATH_TARGETS.size() - 1
                ? Optional.empty() : Optional.of(PUBLISHED_PATH_TARGETS.get(index + 1));
    }

    private static List<String> stages(String assessed, String target) {
        if ("BELOW_N5".equals(assessed)) {
            return "N4".equals(target) ? List.of("FOUNDATION", "N5", "N4")
                    : List.of("FOUNDATION", "N5");
        }
        return List.of(target);
    }

    private static String normalizeAssessed(String value) {
        String normalized = normalize(value);
        if ("FROM_ZERO".equals(normalized)) return "BELOW_N5";
        if ("UNKNOWN".equals(normalized) || RANK.containsKey(normalized)) return normalized;
        throw new IllegalArgumentException("Unsupported assessed JLPT level: " + value);
    }

    private static String normalizeTarget(String value) {
        String normalized = normalize(value);
        if (!TARGET_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported JLPT target: " + value);
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String levelAt(int rank) {
        return rank == 0 ? "N5" : TARGET_LEVELS.get(rank - 1);
    }
}
