package com.jlas.learning.service.impl;

import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.repository.UserLearningPathItemRepository;

/** [JLAS-W0125] "Phần x / n": a pace change may split one skill into ordered parts of the same package.
 * The position follows study order ({@code order_index}); {@code part_index} is only a stable identity, because a
 * second split appends new indexes right after the part they were cut from. */
public final class LearningActivityParts {
    private LearningActivityParts() { }

    /** 1-based position among same-skill parts of the package in study order; 1 when unsplit or package-less. */
    public static int index(UserLearningPathItem activity, UserLearningPathItemRepository items) {
        if (!inPackage(activity) || activity.getOrderIndex() == null) return 1;
        return Math.toIntExact(items.countByLearningPackageIdAndActivityTypeAndOrderIndexLessThan(
                activity.getLearningPackage().getId(), activity.getActivityType(), activity.getOrderIndex()) + 1);
    }

    /** Same-skill parts in the activity's package; an unsplit or package-less activity is a single part. */
    public static int count(UserLearningPathItem activity, UserLearningPathItemRepository items) {
        if (!inPackage(activity)) return 1;
        long count = items.countByLearningPackageIdAndActivityType(
                activity.getLearningPackage().getId(), activity.getActivityType());
        return (int) Math.max(count, index(activity, items));
    }

    private static boolean inPackage(UserLearningPathItem activity) {
        return activity.getLearningPackage() != null && activity.getLearningPackage().getId() != null;
    }
}
