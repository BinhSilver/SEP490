package com.jlas.common.learning;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Server-owned target matrix shared by Smart Test and Learning Path. */
public final class JlptPathPolicy {

    public enum Choice { ACCEPT_RECOMMENDATION, DECLINE_RECOMMENDATION }

    public static final List<String> TARGET_LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    /** Targets whose whole route has releasable content today. N2/N1 join only together with their data and the
     * readiness gate (spec §2.1; owner 2026-10-19 Q6 approved the N5–N1 matrix itself). */
    private static final List<String> DEFAULT_RELEASED_TARGETS = List.of("N5", "N4", "N3");
    private static final Map<String, Integer> RANK = Map.of(
            "BELOW_N5", 0, "N5", 1, "N4", 2, "N3", 3, "N2", 4, "N1", 5);

    private final List<String> releasedTargets;

    public JlptPathPolicy() {
        this(DEFAULT_RELEASED_TARGETS);
    }

    private JlptPathPolicy(List<String> releasedTargets) {
        List<String> ordered = TARGET_LEVELS.subList(0, releasedTargets.size());
        if (releasedTargets.isEmpty() || !ordered.equals(releasedTargets)) {
            throw new IllegalArgumentException("Released JLPT targets must run contiguously from N5: " + releasedTargets);
        }
        this.releasedTargets = List.copyOf(releasedTargets);
    }

    /** The same matrix with N5 up to the given targets released, e.g. {@code releasing("N5","N4","N3","N2","N1")}. */
    public static JlptPathPolicy releasing(String... targets) {
        return new JlptPathPolicy(java.util.Arrays.stream(targets).map(JlptPathPolicy::normalizeTarget).toList());
    }

    public List<String> releasedTargets() {
        return releasedTargets;
    }

    public TargetEligibility evaluate(String assessedLevel, String requestedTarget) {
        String assessed = normalizeAssessed(assessedLevel);
        String requested = normalizeTarget(requestedTarget);
        String highest = releasedTargets.get(releasedTargets.size() - 1);
        int highestRank = RANK.get(highest);
        if (!releasedTargets.contains(requested)
                || (!"UNKNOWN".equals(assessed) && RANK.get(assessed) > highestRank)) {
            return new TargetEligibility(false, requested, highest, List.of(),
                    "PATH_NOT_SUPPORTED", true);
        }
        if ("UNKNOWN".equals(assessed)) {
            return new TargetEligibility(true, requested, requested, List.of(requested),
                    "PENDING_SMART_TEST", false);
        }

        int assessedRank = RANK.get(assessed);
        int targetRank = RANK.get(requested);
        // From zero: N5 or N4 only. Otherwise the same level or the adjacent next level — never skipping one.
        boolean allowed = assessedRank == 0
                ? (targetRank == 1 || targetRank == 2)
                : (targetRank == assessedRank || targetRank == assessedRank + 1);
        if (allowed) {
            return new TargetEligibility(true, requested, requested, stages(assessed, requested),
                    null, false);
        }

        int recommendedRank = assessedRank == 0 ? 2
                : targetRank < assessedRank ? assessedRank : Math.min(highestRank, assessedRank + 1);
        String recommended = levelAt(recommendedRank);
        return new TargetEligibility(false, requested, recommended, List.of(),
                targetRank < assessedRank ? "TARGET_BELOW_ASSESSED_LEVEL" : "TARGET_TOO_FAR",
                true);
    }

    public List<TargetEligibility> options(String assessedLevel) {
        return TARGET_LEVELS.stream().map(target -> evaluate(assessedLevel, target)).toList();
    }

    /** Full stage sequence after a learner explicitly resolves a lower-assessment discrepancy.
     * Accepting starts at the assessed level and walks adjacent stages to the target; declining keeps the
     * ordinary survey route (owner 2026-10-19). This does not change the directly selectable survey routes. */
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
        if (choice == Choice.DECLINE_RECOMMENDATION) {
            return evaluate(survey, target).stageLevels();
        }
        String start = assessed;
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

    /**
     * Builds a review route only after the learner explicitly selects a starting level
     * below a higher Smart Test result. This does not broaden the ordinary target matrix
     * and never changes the stored assessment level.
     *
     * <p>The Smart Test result offers this choice whenever the assessed level is above the
     * target, so any survey the target matrix accepted (same level, upgrade, UNKNOWN,
     * FROM_ZERO) qualifies; a known self-reported level above the target never does.
     */
    public List<String> confirmedHigherAssessmentReviewStages(String surveyLevel,
            String assessedLevel, String requestedTarget, String reviewStartLevel) {
        String survey = normalizeAssessed(surveyLevel);
        String assessed = normalizeAssessed(assessedLevel);
        String target = normalizeTarget(requestedTarget);
        String start = normalizeAssessed(reviewStartLevel);
        if (!releasedTargets.contains(target)
                || (!"UNKNOWN".equals(survey) && RANK.get(survey) > RANK.get(target))
                || "UNKNOWN".equals(assessed)
                || !RANK.containsKey(start)
                || RANK.get(start) < RANK.get("N5")
                || RANK.get(assessed) <= RANK.get(target)
                || RANK.get(start) > RANK.get(target)) {
            throw new IllegalArgumentException("No supported higher-assessment review route for this selection.");
        }

        int first = RANK.get(start);
        int last = RANK.get(target);
        List<String> stages = new java.util.ArrayList<>();
        for (int rank = first; rank <= last; rank++) {
            String level = levelAt(rank);
            if (!stages.isEmpty() && !evaluate(levelAt(rank - 1), level).allowed()) {
                throw new IllegalArgumentException("An intermediate JLPT review stage is not supported.");
            }
            stages.add(level);
        }
        return List.copyOf(stages);
    }

    /** Immediate next target among the supported JLPT learning routes. */
    public Optional<String> nextTargetAfter(String completedTarget) {
        String completed = normalizeTarget(completedTarget);
        int index = releasedTargets.indexOf(completed);
        return index < 0 || index == releasedTargets.size() - 1
                ? Optional.empty() : Optional.of(releasedTargets.get(index + 1));
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
