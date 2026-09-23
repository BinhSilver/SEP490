package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.repository.TextbookRepository;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.service.LearningScopeSource;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production adapter: resolves the two-phase curriculum scope from the published JLPT textbooks whose
 * level falls inside {@code [assessedLevel, targetLevel]}. Both phases share the same textbook set;
 * WARM_UP narrows to the learner's weak lesson tags inside it.
 */
@Component
@Primary
@RequiredArgsConstructor
public class JpaLearningScopeSource implements LearningScopeSource {
    private static final List<String> LEVELS = List.of("BELOW_N5", "N5", "N4", "N3", "N2", "N1");

    /** Local demo seed (`db/jlas-learning-demo-seed.sql`) creates textbooks with ids >= 9000; never in production scope. */
    private static final long DEMO_TEXTBOOK_ID_FLOOR = 9000L;

    private final TextbookRepository textbooks;

    @Override
    @Transactional(readOnly = true)
    public LearningCatalogScope resolve(Long userId, LearningProfileSnapshot profile) {
        if (!"JLPT".equals(profile.goalCode())) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Curriculum scope is only available for the JLPT goal.");
        }
        int from = Math.max(LEVELS.indexOf(profile.assessedLevel()), 1);
        int target = LEVELS.indexOf(profile.targetLevel());
        int to = target < from ? from + 1 : Math.min(target + 1, LEVELS.size());
        List<Long> bookIds = textbooks
                .findByJlptLevelInAndStatusAndIdLessThanOrderByIdAsc(LEVELS.subList(from, to), "PUBLISHED",
                        DEMO_TEXTBOOK_ID_FLOOR)
                .stream().map(Textbook::getId).toList();
        return new LearningCatalogScope(bookIds, bookIds);
    }
}
