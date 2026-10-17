package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;

/** Shared guard for activity LEARN endpoints; ownership is resolved before this policy is applied. */
public final class InteractiveActivityStatePolicy {
    private InteractiveActivityStatePolicy() { }

    public static void requireUnlocked(UserLearningPathItem activity) {
        if (activity.getActivityState() == LearningActivityState.LOCKED) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Hãy hoàn thành phần học trước để mở khóa hoạt động này.");
        }
    }
}
