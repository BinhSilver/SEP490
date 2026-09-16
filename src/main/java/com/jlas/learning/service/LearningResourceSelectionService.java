package com.jlas.learning.service;

import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.LearningSelectionResult;

public interface LearningResourceSelectionService {
    LearningSelectionResult select(Long userId, LearningProfileSnapshot profile, LearningCatalogScope scope);
}
