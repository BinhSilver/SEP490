package com.jlas.learning.service;

import com.jlas.learning.dto.InteractiveCompositionResult;
import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.entity.UserLearningPath;
import java.util.List;

public interface InteractivePackageComposer {
    InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule);
}
