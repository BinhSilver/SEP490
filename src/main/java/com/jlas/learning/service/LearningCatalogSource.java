package com.jlas.learning.service;

import com.jlas.learning.dto.LearningResourceCandidate;
import java.util.List;

public interface LearningCatalogSource {
    // Return only published lessons in published textbooks with a primary KNOWLEDGE tag.
    List<LearningResourceCandidate> findPublishedLessons(List<Long> textbookIds);
}
