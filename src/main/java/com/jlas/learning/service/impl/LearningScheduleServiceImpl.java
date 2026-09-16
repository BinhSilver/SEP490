package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.dto.SelectedLearningResource;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.service.LearningScheduleService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Packs resources into days without splitting a lesson. Warm-up runs first; the goal
 * phase always starts on a fresh day. A lesson longer than the budget takes a whole day.
 */
@Component
public class LearningScheduleServiceImpl implements LearningScheduleService {

    @Override
    public List<ScheduledLearningResource> schedule(List<SelectedLearningResource> resources, int minutesPerDay) {
        if (minutesPerDay <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Daily study budget must be positive.");
        }
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        var scheduled = new ArrayList<ScheduledLearningResource>();
        int day = 0;
        int dayMinutes = 0;
        LearningPhase currentPhase = null;
        int order = 0;
        for (var selection : resources) {
            int minutes = selection.resource().estimatedMinutes();
            if (currentPhase != selection.phase()) {
                day++;
                dayMinutes = 0;
                currentPhase = selection.phase();
            } else if (dayMinutes > 0 && dayMinutes + minutes > minutesPerDay) {
                day++;
                dayMinutes = 0;
            }
            dayMinutes += minutes;
            scheduled.add(new ScheduledLearningResource(selection.resource(), selection.phase(),
                    selection.reasonCode(), selection.reasonText(), selection.matchedTagCodes(),
                    day, ++order, minutes));
        }
        return List.copyOf(scheduled);
    }
}
