package com.jlas.learning.service.impl;

import com.jlas.learning.dto.InteractiveLearningActivityResponse;
import com.jlas.learning.dto.InteractiveLearningSessionResponse;
import com.jlas.learning.dto.ReferenceVocabularyResponse;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.enums.LearningSessionState;
import com.jlas.learning.enums.LearningSessionType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure projection: it never reads persistence and accepts state/actions only from the server's activity rows. */
public final class InteractiveSessionProjector {
    private static final List<LearningSessionType> ORDER = List.of(
            LearningSessionType.VOCABULARY, LearningSessionType.KANJI_GRAMMAR, LearningSessionType.DIALOGUE);
    private static final Set<LearningActivityState> TERMINAL = Set.of(
            LearningActivityState.PASSED, LearningActivityState.COMPLETED);

    public List<InteractiveLearningSessionResponse> project(LearningPackageStatus packageStatus,
            String packageLockReason, List<InteractiveLearningActivityResponse> activities,
            Map<LearningMemberType, Integer> memberCounts, List<ReferenceVocabularyResponse> references,
            int packageMinutes) {
        List<InteractiveLearningSessionResponse> result = new ArrayList<>();
        boolean previousComplete = true;
        int groupCount = (int) ORDER.stream().filter(type -> !group(type, activities).isEmpty()).count();
        int perGroupMinutes = groupCount == 0 ? 0 : Math.max(1, packageMinutes / groupCount);

        for (LearningSessionType type : ORDER) {
            List<InteractiveLearningActivityResponse> children = group(type, activities);
            if (children.isEmpty()) continue;

            boolean complete = children.stream().allMatch(this::terminal);
            boolean anyStarted = children.stream().anyMatch(child -> child.state() != null
                    && child.state() != LearningActivityState.LOCKED && !TERMINAL.contains(child.state()));
            LearningSessionState state;
            String blockedReason = null;
            if (complete) {
                state = LearningSessionState.COMPLETED;
            } else if (packageStatus == LearningPackageStatus.LOCKED) {
                state = LearningSessionState.LOCKED;
                blockedReason = packageLockReason;
            } else if (!previousComplete) {
                state = LearningSessionState.LOCKED;
                blockedReason = "Hoàn thành phiên học trước để mở khóa phần này.";
            } else if (packageStatus == LearningPackageStatus.COMPLETED) {
                state = LearningSessionState.LOCKED;
                blockedReason = "Trạng thái hoạt động chưa đồng bộ với package đã hoàn thành.";
            } else if (anyStarted) {
                state = LearningSessionState.IN_PROGRESS;
            } else {
                state = LearningSessionState.AVAILABLE;
            }

            Map<String, Integer> counts = countsFor(type, memberCounts, references);
            int contentCount = counts.values().stream().mapToInt(Integer::intValue).sum();
            List<ReferenceVocabularyResponse> referenceWords = type == LearningSessionType.KANJI_GRAMMAR
                    ? safe(references) : List.of();
            List<String> actions = switch (state) {
                case LOCKED -> List.of();
                case AVAILABLE -> List.of("START");
                case IN_PROGRESS -> List.of("CONTINUE");
                case COMPLETED -> List.of("REVIEW", "PRACTICE");
            };
            result.add(new InteractiveLearningSessionResponse(type, state, children, actions, counts,
                    contentCount, perGroupMinutes, referenceWords, blockedReason));
            previousComplete = complete;
        }
        return List.copyOf(result);
    }

    private Map<String, Integer> countsFor(LearningSessionType type,
            Map<LearningMemberType, Integer> memberCounts, List<ReferenceVocabularyResponse> references) {
        Map<LearningMemberType, Integer> counts = memberCounts == null ? Map.of() : memberCounts;
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        if (type == LearningSessionType.VOCABULARY) {
            result.put("vocabulary", count(counts, LearningMemberType.VOCABULARY_ENTRY));
        } else if (type == LearningSessionType.KANJI_GRAMMAR) {
            result.put("vocabulary", safe(references).size());
            result.put("kanji", count(counts, LearningMemberType.KANJI));
            result.put("grammar", count(counts, LearningMemberType.GRAMMAR_POINT));
        } else {
            result.put("dialogue", count(counts, LearningMemberType.DIALOGUE_LINE));
        }
        return result;
    }

    private int count(Map<LearningMemberType, Integer> counts, LearningMemberType type) {
        return Math.max(0, counts.getOrDefault(type, 0));
    }

    private List<InteractiveLearningActivityResponse> group(LearningSessionType type,
            List<InteractiveLearningActivityResponse> activities) {
        if (activities == null) return List.of();
        return activities.stream().filter(activity -> activity.type() != null && switch (type) {
            case VOCABULARY -> activity.type() == LearningActivityType.VOCABULARY;
            case KANJI_GRAMMAR -> activity.type() == LearningActivityType.KANJI
                    || activity.type() == LearningActivityType.GRAMMAR;
            case DIALOGUE -> activity.type() == LearningActivityType.DIALOGUE;
        }).toList();
    }

    private boolean terminal(InteractiveLearningActivityResponse activity) {
        if (activity.state() == null) return false;
        if (activity.type() == LearningActivityType.DIALOGUE) return activity.state() == LearningActivityState.COMPLETED;
        return activity.state() == LearningActivityState.PASSED;
    }

    private List<ReferenceVocabularyResponse> safe(List<ReferenceVocabularyResponse> references) {
        return references == null ? List.of() : references;
    }
}
