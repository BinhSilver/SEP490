package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.service.LessonAccessPolicy;
import com.jlas.learning.service.OnyomiCourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Keeps the quizzes of Onyomi lessons and stages closed until the learner has unlocked them. */
@Component
@RequiredArgsConstructor
public class OnyomiLessonAccessPolicy implements LessonAccessPolicy {
    private final OnyomiCourseService courseService;

    @Override
    public void checkLesson(Long userId, Long lessonId) {
        if (courseService.isLessonLocked(userId, lessonId)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Bài học này chưa mở khoá. Hãy vượt qua bài luyện tập của bài trước.");
        }
    }

    @Override
    public void checkUnitFinal(Long userId, Long unitId) {
        if (courseService.isFinalLocked(userId, unitId)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Bài kiểm tra cuối giai đoạn mở khoá khi bạn hoàn thành mọi bài trong giai đoạn.");
        }
    }

    @Override
    public void checkManualCompletion(Long userId, Long lessonId) {
        if (courseService.isCourseLesson(lessonId)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Bài Onyomi được ghi nhận hoàn thành khi bạn đạt từ 60% ở phần Luyện tập.");
        }
    }
}
