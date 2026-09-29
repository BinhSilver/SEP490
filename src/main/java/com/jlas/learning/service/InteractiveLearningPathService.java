package com.jlas.learning.service;

import com.jlas.learning.dto.InteractiveLearningPackageResponse;
import com.jlas.learning.dto.InteractiveLearningPathResponse;

public interface InteractiveLearningPathService {
    InteractiveLearningPathResponse current(Long userId);

    InteractiveLearningPackageResponse packageDetail(Long userId, Long packageId);

    InteractiveLearningPackageResponse start(Long userId, Long packageId);
}
