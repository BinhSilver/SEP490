package com.jlas.jlptpath.service.impl;

import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathEntryRoute;
import com.jlas.jlptpath.service.JpathEntryRouter;
import com.jlas.jlptpath.service.JpathFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Plan decision 11: only learners whose survey goal is JLPT use the topic-based path. */
@Service
public class JpathEntryRouterImpl implements JpathEntryRouter {

    private static final String JLPT_GOAL = "JLPT";

    private final JpathFeature feature;
    private final JpathUserPathRepository paths;
    private final UserLearningSurveyRepository surveys;
    /** [JLAS-W0108] Owner 07/11: with the communication path on, its goals never fall back to the old path. */
    @org.springframework.beans.factory.annotation.Value("${learning.comm-path.enabled:false}")
    private boolean commPathEnabled;
    private static final java.util.Set<String> COMM_GOALS = java.util.Set.of("BA", "TRAVEL", "DAILY_LIFE", "WORK");

    public JpathEntryRouterImpl(JpathFeature feature, JpathUserPathRepository paths,
            UserLearningSurveyRepository surveys) {
        this.feature = feature;
        this.paths = paths;
        this.surveys = surveys;
    }

    @Override
    @Transactional(readOnly = true)
    public JpathEntryRoute route(Long userId) {
        if (userId == null || (!feature.enabled() && !commPathEnabled)) {
            return JpathEntryRoute.LEGACY;
        }
        String goal = surveys.findById(userId).map(UserLearningSurvey::getGoal)
                .map(g -> g.trim().toUpperCase(java.util.Locale.ROOT)).orElse(null);
        if (commPathEnabled && goal != null && COMM_GOALS.contains(goal)) {
            return JpathEntryRoute.COMM;
        }
        if (!feature.enabled() || !JLPT_GOAL.equals(goal)) {
            return JpathEntryRoute.LEGACY;
        }
        return paths.existsByUserIdAndStatus(userId, JpathPathStatus.ACTIVE)
                ? JpathEntryRoute.JPATH_PAGE : JpathEntryRoute.JPATH_START;
    }
}
