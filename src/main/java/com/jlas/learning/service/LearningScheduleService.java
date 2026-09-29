package com.jlas.learning.service;

import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.dto.SelectedLearningResource;
import java.util.List;

/** Positions selected resources into a daily schedule within the daily study budget. */
public interface LearningScheduleService {
    List<ScheduledLearningResource> schedule(List<SelectedLearningResource> resources, int minutesPerDay);
}
