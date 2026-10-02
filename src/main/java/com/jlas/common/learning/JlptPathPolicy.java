package com.jlas.common.learning;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Server-owned target matrix shared by Smart Test and Learning Path. */
public final class JlptPathPolicy {

    public static final List<String> TARGET_LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    private static final Map<String, Integer> RANK = Map.of(
            "BELOW_N5", 0, "N5", 1, "N4", 2, "N3", 3, "N2", 4, "N1", 5);

    public TargetEligibility evaluate(String assessedLevel, String requestedTarget) {
        String assessed = normalizeAssessed(assessedLevel);
        String requested = normalizeTarget(requestedTarget);
        if ("UNKNOWN".equals(assessed)) {
            return new TargetEligibility(true, requested, requested, List.of(requested),
                    "PENDING_SMART_TEST", false);
        }

        int assessedRank = RANK.get(assessed);
        int targetRank = RANK.get(requested);
        boolean specialBeginnerN4 = assessedRank == 0 && targetRank == 2;
        boolean allowed = targetRank == assessedRank || targetRank == assessedRank + 1
                || (assessedRank == 0 && targetRank == 1) || specialBeginnerN4;
        if (allowed) {
            return new TargetEligibility(true, requested, requested, stages(assessed, requested),
                    null, false);
        }

        int recommendedRank = assessedRank == 0 ? 2 : Math.min(5, assessedRank + 1);
        String recommended = levelAt(recommendedRank);
        return new TargetEligibility(false, requested, recommended, List.of(),
                targetRank < assessedRank ? "TARGET_BELOW_ASSESSED_LEVEL" : "TARGET_TOO_FAR",
                true);
    }

    public List<TargetEligibility> options(String assessedLevel) {
        return TARGET_LEVELS.stream().map(target -> evaluate(assessedLevel, target)).toList();
    }

    /** Immediate next target after a completed JLPT path; N1 is the terminal level. */
    public Optional<String> nextTargetAfter(String completedTarget) {
        String completed = normalizeTarget(completedTarget);
        int index = TARGET_LEVELS.indexOf(completed);
        return index == TARGET_LEVELS.size() - 1
                ? Optional.empty()
                : Optional.of(TARGET_LEVELS.get(index + 1));
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
