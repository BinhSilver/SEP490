package com.jlas.learning.service.impl;

import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.service.LearningScopeSource;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Default demo adapter: resolves the two-phase curriculum scope from configuration. Textbook
 * ids must point at seeded published textbooks.
 */
@Component
public class LocalLearningScopeSource implements LearningScopeSource {
    private final List<Long> warmUpBookIds;
    private final List<Long> goalBookIds;

    public LocalLearningScopeSource(
            @Value("${jlas.learning.local-seed.warm-up-book-ids:}") String warmUp,
            @Value("${jlas.learning.local-seed.goal-book-ids:}") String goal) {
        this.warmUpBookIds = parse(warmUp);
        this.goalBookIds = parse(goal);
    }

    @Override
    public LearningCatalogScope resolve(Long userId, LearningProfileSnapshot profile) {
        return new LearningCatalogScope(warmUpBookIds, goalBookIds);
    }

    private static List<Long> parse(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(part -> !part.isEmpty())
                .map(Long::parseLong).toList();
    }
}
