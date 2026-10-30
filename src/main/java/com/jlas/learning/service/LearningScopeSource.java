package com.jlas.learning.service;

import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;

/**
 * Resolves which textbooks feed each phase for a profile. The scope is a server-side
 * curriculum decision, never derived from Smart Test scores or client input.
 */
public interface LearningScopeSource {
    LearningCatalogScope resolve(Long userId, LearningProfileSnapshot profile);
}
