package com.jlas.learning.service;

import com.jlas.learning.dto.OnyomiCourseView;
import com.jlas.learning.dto.OnyomiFinalView;
import com.jlas.learning.dto.OnyomiLessonView;
import com.jlas.learning.dto.OnyomiRulePageView;
import java.util.Optional;

/**
 * The Onyomi course (Hán Việt → âm On) and its step-by-step unlocking: a lesson opens once the previous
 * lesson's practice quiz is passed; a stage's final test opens once all its lessons are completed; the
 * next stage opens once that final test is passed. Content reviewers (content admin, admin) have the
 * whole course unlocked so they can check it.
 */
public interface OnyomiCourseService {

    OnyomiCourseView course(Long userId);

    /** Empty when the lesson is not a published Onyomi lesson. The view says whether it is locked. */
    Optional<OnyomiLessonView> lesson(Long userId, Long lessonId);

    /** Empty when the rule does not belong to a published Onyomi lesson. */
    Optional<OnyomiRulePageView> rule(Long userId, Long ruleId);

    /** Empty when the unit is not a stage of the course. */
    Optional<OnyomiFinalView> stageFinal(Long userId, Long unitId);

    /** True only for an Onyomi lesson the user has not unlocked yet. */
    boolean isLessonLocked(Long userId, Long lessonId);

    /** True only for an Onyomi stage whose final test the user has not unlocked yet. */
    boolean isFinalLocked(Long userId, Long unitId);

    /** True for any Onyomi lesson, published or not. */
    boolean isCourseLesson(Long lessonId);
}
