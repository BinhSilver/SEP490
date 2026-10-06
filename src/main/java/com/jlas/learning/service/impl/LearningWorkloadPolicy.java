package com.jlas.learning.service.impl;

import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Central planning estimate for mandatory study, flashcards, and one required quiz pass. */
public final class LearningWorkloadPolicy {
    private static final int QUIZ_BATCH_SIZE = 10;
    private static final int QUIZ_MINUTES_PER_BATCH = 3;
    private static final int MAX_FLASHCARD_PART_SIZE = 15;

    public record ActivityPlan(LearningActivityType activityType, LearningMemberType memberType,
            List<Long> memberIds, int estimatedMinutes) {
        public ActivityPlan {
            if (activityType == null || memberType == null || memberIds == null || memberIds.isEmpty()
                    || estimatedMinutes <= 0) {
                throw new IllegalArgumentException("An activity plan needs a type, members, and a positive estimate.");
            }
            memberIds = List.copyOf(memberIds);
        }
    }

    /** Round-up estimates: words/kanji/kana 30/45/30 sec per member, grammar 60 sec, dialogue 30 sec;
     * each micro-check batch of up to ten questions reserves three minutes for a required attempt. */
    public int estimateMinutes(LearningActivityType type, int memberCount) {
        if (type == null || memberCount < 0) {
            throw new IllegalArgumentException("Activity type and non-negative member count are required.");
        }
        if (memberCount == 0) return 0;
        int secondsPerMember = switch (type) {
            case VOCABULARY, FOUNDATION, DIALOGUE -> 30;
            case KANJI -> 45;
            case GRAMMAR -> 60;
            case READING, LISTENING -> throw new IllegalArgumentException(
                    "Comprehension is estimated from its own source and cannot use curriculum member timing.");
        };
        int quizBatches = switch (type) {
            case VOCABULARY, KANJI, GRAMMAR, FOUNDATION -> divideCeiling(memberCount, QUIZ_BATCH_SIZE);
            case DIALOGUE -> 0;
            case READING, LISTENING -> throw new IllegalArgumentException(
                    "Comprehension is estimated from its own source and cannot use curriculum member timing.");
        };
        int totalSeconds = Math.addExact(Math.multiplyExact(memberCount, secondsPerMember),
                Math.multiplyExact(quizBatches, QUIZ_MINUTES_PER_BATCH * 60));
        return divideCeiling(totalSeconds, 60);
    }

    public int maxMembersPerActivity(LearningActivityType type, int dailyMinutes) {
        if (type == null || dailyMinutes <= 0) {
            throw new IllegalArgumentException("Activity type and positive daily budget are required.");
        }
        int maximum = switch (type) {
            case FOUNDATION, VOCABULARY, KANJI, GRAMMAR -> MAX_FLASHCARD_PART_SIZE;
            case DIALOGUE -> Math.multiplyExact(dailyMinutes, 2);
            case READING, LISTENING -> throw new IllegalArgumentException(
                    "Comprehension membership is split by its source-specific planner.");
        };
        for (int count = maximum; count > 0; count--) {
            if (estimateMinutes(type, count) <= dailyMinutes) return count;
        }
        throw new IllegalArgumentException("The daily budget is too small for one " + type + " study-and-quiz unit.");
    }

    public List<List<Long>> partition(LearningActivityType type, List<Long> canonicalIds, int dailyMinutes) {
        if (canonicalIds == null) throw new IllegalArgumentException("Canonical member ids are required.");
        int maxMembers = maxMembersPerActivity(type, dailyMinutes);
        LinkedHashSet<Long> distinct = new LinkedHashSet<>(canonicalIds);
        if (distinct.contains(null)) throw new IllegalArgumentException("Canonical member ids cannot contain null.");
        List<Long> ids = List.copyOf(distinct);
        if (ids.isEmpty()) return List.of();
        int groupCount = divideCeiling(ids.size(), maxMembers);
        List<List<Long>> result = new ArrayList<>(groupCount);
        int offset = 0;
        for (int group = 0; group < groupCount; group++) {
            int size = ids.size() / groupCount + (group < ids.size() % groupCount ? 1 : 0);
            result.add(List.copyOf(ids.subList(offset, offset + size)));
            offset += size;
        }
        return List.copyOf(result);
    }

    private static int divideCeiling(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
