package com.jlas.learning.service.impl;

import com.jlas.learning.service.impl.LearningWorkloadPolicy.ActivityPlan;
import com.jlas.learning.enums.LearningActivityType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Packs already split, curriculum-ordered activities into daily package segments without dropping membership. */
public final class LearningDailyWorkloadPacker {
    public record PackageSegment(List<ActivityPlan> activities, int estimatedMinutes) {
        public PackageSegment {
            if (activities == null || activities.isEmpty() || estimatedMinutes <= 0) {
                throw new IllegalArgumentException("A package segment must contain required learning work.");
            }
            activities = List.copyOf(activities);
        }
    }

    public List<PackageSegment> pack(List<ActivityPlan> activities, int dailyMinutes) {
        return pack(activities, dailyMinutes, Map.of());
    }

    public List<PackageSegment> pack(List<ActivityPlan> activities, int dailyMinutes,
            Map<LearningActivityType, Integer> dailyMemberLimits) {
        return pack(activities, dailyMinutes, dailyMemberLimits, Map.of());
    }

    /** {@code dailyActivityLimits} caps how many activities of a type one day holds (e.g. one reading exercise). */
    public List<PackageSegment> pack(List<ActivityPlan> activities, int dailyMinutes,
            Map<LearningActivityType, Integer> dailyMemberLimits,
            Map<LearningActivityType, Integer> dailyActivityLimits) {
        if (activities == null || dailyMinutes <= 0) {
            throw new IllegalArgumentException("Activities and a positive daily budget are required.");
        }
        Map<LearningActivityType, Integer> limits = dailyMemberLimits == null
                ? Map.of() : dailyMemberLimits;
        Map<LearningActivityType, Integer> activityLimits = dailyActivityLimits == null
                ? Map.of() : dailyActivityLimits;
        if (activities.isEmpty()) return List.of();
        List<PackageSegment> segments = new ArrayList<>();
        List<ActivityPlan> current = new ArrayList<>();
        Map<LearningActivityType, Integer> memberCounts = new EnumMap<>(LearningActivityType.class);
        Map<LearningActivityType, Integer> activityCounts = new EnumMap<>(LearningActivityType.class);
        int currentMinutes = 0;
        for (ActivityPlan activity : activities) {
            if (activity == null || activity.estimatedMinutes() > dailyMinutes) {
                throw new IllegalArgumentException("Every atomic activity must fit the selected daily budget.");
            }
            Integer activityLimit = activityLimits.get(activity.activityType());
            if (!current.isEmpty() && (currentMinutes + activity.estimatedMinutes() > dailyMinutes
                    || exceedsQuota(activity, memberCounts, limits)
                    || activityLimit != null && activityCounts.getOrDefault(activity.activityType(), 0) >= activityLimit)) {
                segments.add(new PackageSegment(current, currentMinutes));
                current = new ArrayList<>();
                memberCounts.clear();
                activityCounts.clear();
                currentMinutes = 0;
            }
            Integer quota = limits.get(activity.activityType());
            if (quota != null && activity.memberIds().size() > quota) {
                throw new IllegalArgumentException("One activity exceeds its daily member quota.");
            }
            current.add(activity);
            memberCounts.merge(activity.activityType(), activity.memberIds().size(), Integer::sum);
            activityCounts.merge(activity.activityType(), 1, Integer::sum);
            currentMinutes += activity.estimatedMinutes();
        }
        if (!current.isEmpty()) segments.add(new PackageSegment(current, currentMinutes));
        return List.copyOf(segments);
    }

    /** Segments of one chapter; the first one fills the rest of the current day when {@code firstContinuesDay}. */
    public record DayPlan(List<PackageSegment> segments, boolean firstContinuesDay) {
        public DayPlan {
            segments = List.copyOf(segments);
        }
    }

    /**
     * D3-05 (owner 2026-10-24): JLPT days are filled by minutes only, so a chapter starts in what is left of the day
     * its previous chapter ended in ({@code usedToday} minutes already planned). When even its first activity does
     * not fit there, the chapter starts on a new day. Activities stay whole and in curriculum order.
     */
    public DayPlan packContinuing(List<ActivityPlan> activities, int dailyMinutes, int usedToday) {
        if (activities == null || dailyMinutes <= 0 || usedToday < 0) {
            throw new IllegalArgumentException("Activities, a positive daily budget and today's planned minutes are required.");
        }
        boolean continues = usedToday > 0 && usedToday < dailyMinutes;
        int capacity = continues ? dailyMinutes - usedToday : dailyMinutes;
        List<PackageSegment> segments = new ArrayList<>();
        List<ActivityPlan> current = new ArrayList<>();
        int currentMinutes = 0;
        for (ActivityPlan activity : activities) {
            if (activity == null || activity.estimatedMinutes() > dailyMinutes) {
                throw new IllegalArgumentException("Every atomic activity must fit the selected daily budget.");
            }
            if (segments.isEmpty() && current.isEmpty() && continues && activity.estimatedMinutes() > capacity) {
                continues = false;
                capacity = dailyMinutes;
            }
            if (!current.isEmpty() && currentMinutes + activity.estimatedMinutes() > capacity) {
                segments.add(new PackageSegment(current, currentMinutes));
                current = new ArrayList<>();
                currentMinutes = 0;
                capacity = dailyMinutes;
            }
            current.add(activity);
            currentMinutes += activity.estimatedMinutes();
        }
        if (!current.isEmpty()) segments.add(new PackageSegment(current, currentMinutes));
        return new DayPlan(segments, continues && !segments.isEmpty());
    }

    private boolean exceedsQuota(ActivityPlan activity,
            Map<LearningActivityType, Integer> memberCounts,
            Map<LearningActivityType, Integer> limits) {
        Integer limit = limits.get(activity.activityType());
        return limit != null && memberCounts.getOrDefault(activity.activityType(), 0)
                + activity.memberIds().size() > limit;
    }
}
