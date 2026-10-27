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
        if (activities == null || dailyMinutes <= 0) {
            throw new IllegalArgumentException("Activities and a positive daily budget are required.");
        }
        Map<LearningActivityType, Integer> limits = dailyMemberLimits == null
                ? Map.of() : dailyMemberLimits;
        if (activities.isEmpty()) return List.of();
        List<PackageSegment> segments = new ArrayList<>();
        List<ActivityPlan> current = new ArrayList<>();
        Map<LearningActivityType, Integer> memberCounts = new EnumMap<>(LearningActivityType.class);
        int currentMinutes = 0;
        for (ActivityPlan activity : activities) {
            if (activity == null || activity.estimatedMinutes() > dailyMinutes) {
                throw new IllegalArgumentException("Every atomic activity must fit the selected daily budget.");
            }
            if (!current.isEmpty() && (currentMinutes + activity.estimatedMinutes() > dailyMinutes
                    || exceedsQuota(activity, memberCounts, limits))) {
                segments.add(new PackageSegment(current, currentMinutes));
                current = new ArrayList<>();
                memberCounts.clear();
                currentMinutes = 0;
            }
            Integer quota = limits.get(activity.activityType());
            if (quota != null && activity.memberIds().size() > quota) {
                throw new IllegalArgumentException("One activity exceeds its daily member quota.");
            }
            current.add(activity);
            memberCounts.merge(activity.activityType(), activity.memberIds().size(), Integer::sum);
            currentMinutes += activity.estimatedMinutes();
        }
        if (!current.isEmpty()) segments.add(new PackageSegment(current, currentMinutes));
        return List.copyOf(segments);
    }

    private boolean exceedsQuota(ActivityPlan activity,
            Map<LearningActivityType, Integer> memberCounts,
            Map<LearningActivityType, Integer> limits) {
        Integer limit = limits.get(activity.activityType());
        return limit != null && memberCounts.getOrDefault(activity.activityType(), 0)
                + activity.memberIds().size() > limit;
    }
}
