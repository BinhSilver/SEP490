package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.repository.TextbookRepository;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.service.LearningScopeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production adapter: resolves the two-phase curriculum scope from the published JLPT textbooks whose
 * level falls inside {@code [assessedLevel, targetLevel]}. Both phases share the same textbook set;
 * WARM_UP narrows to the learner's weak lesson tags inside it.
 *
 * <p>For each level in range the real published textbook (id &lt; 9000) is preferred; when a level has
 * no real content yet (N3/N2/N1 are still being digitized), the local demo seed (id &gt;= 9000,
 * {@code db/jlas-learning-demo-seed.sql}) is used as a temporary fallback so the JLPT path always has
 * output across every learning stage. Real content replaces the fallback as it is published.
 */
@Component
@Primary
@RequiredArgsConstructor
public class JpaLearningScopeSource implements LearningScopeSource {
    private static final List<String> LEVELS = List.of("BELOW_N5", "N5", "N4", "N3", "N2", "N1");

    /** Local demo seed (`db/jlas-learning-demo-seed.sql`) creates textbooks with ids >= 9000. */
    private static final long DEMO_TEXTBOOK_ID_FLOOR = 9000L;

    private final TextbookRepository textbooks;

    @Override
    @Transactional(readOnly = true)
    public LearningCatalogScope resolve(Long userId, LearningProfileSnapshot profile) {
        if (!"JLPT".equals(profile.goalCode())) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Curriculum scope is only available for the JLPT goal.");
        }
        // [JLAS-W0108] From-zero: warm-up = sách nền tảng bảng chữ cái (tag KANA-*, jlpt_level NULL);
        // chỉ lấy đúng sách Kana, KHÔNG lẫn sách hội thoại IT/du lịch/đời sống cũng có level NULL.
        boolean fromZero = "BELOW_N5".equals(profile.assessedLevel());
        List<Long> foundationIds = fromZero
                ? textbooks.findKanaFoundationByStatus("PUBLISHED").stream()
                        .map(Textbook::getId).toList()
                : List.of();
        int from = Math.max(LEVELS.indexOf(profile.assessedLevel()), 1);
        int target = LEVELS.indexOf(profile.targetLevel());
        int to = target < from ? from + 1 : Math.min(target + 1, LEVELS.size());
        List<String> levels = LEVELS.subList(from, to);
        List<Long> bookIds = new ArrayList<>();
        Map<String, List<Long>> realByLevel = textbooks
                .findByJlptLevelInAndStatusAndIdLessThanOrderByIdAsc(levels, "PUBLISHED",
                        DEMO_TEXTBOOK_ID_FLOOR)
                .stream().collect(Collectors.groupingBy(Textbook::getJlptLevel,
                        Collectors.mapping(Textbook::getId, Collectors.toList())));
        Map<String, List<Long>> demoByLevel = textbooks
                .findByJlptLevelInAndStatusAndIdGreaterThanEqualOrderByIdAsc(levels, "PUBLISHED",
                        DEMO_TEXTBOOK_ID_FLOOR)
                .stream().collect(Collectors.groupingBy(Textbook::getJlptLevel,
                        Collectors.mapping(Textbook::getId, Collectors.toList())));
        for (String level : levels) {
            List<Long> ids = realByLevel.get(level);
            if (ids == null || ids.isEmpty()) {
                ids = demoByLevel.get(level);
            }
            if (ids != null) {
                bookIds.addAll(ids);
            }
        }
        return new LearningCatalogScope(foundationIds.isEmpty() ? bookIds : foundationIds, bookIds);
    }
}
