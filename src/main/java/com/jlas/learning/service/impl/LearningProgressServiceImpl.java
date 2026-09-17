package com.jlas.learning.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.learning.dto.LessonProgressResponse;
import com.jlas.learning.entity.LessonProgress;
import com.jlas.learning.enums.LessonStatus;
import com.jlas.learning.repository.LearningCatalogRepository;
import com.jlas.learning.repository.LessonProgressRepository;
import com.jlas.learning.service.LearningProgressService;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link LearningProgressService}. */
@Service
@RequiredArgsConstructor
public class LearningProgressServiceImpl implements LearningProgressService {
    private final LessonProgressRepository progressRepository;
    private final LearningCatalogRepository catalog;
    private final EntityManager em;

    @Override
    @Transactional
    public LessonProgressResponse start(Long userId, Long lessonId) {
        Lesson lesson = requirePublishedLesson(lessonId);
        LessonProgress progress = progressRepository.findByUserIdAndLessonId(userId, lessonId)
                .orElseGet(() -> newProgress(userId, lesson));
        if (LessonStatus.NOT_STARTED.name().equals(progress.getStatus())) {
            progress.setStatus(LessonStatus.IN_PROGRESS.name());
            progress.setStartedAt(LocalDateTime.now());
            progressRepository.save(progress);
        }
        return toResponse(progress);
    }

    @Override
    @Transactional
    public LessonProgressResponse complete(Long userId, Long lessonId) {
        Lesson lesson = requirePublishedLesson(lessonId);
        LessonProgress progress = progressRepository.findByUserIdAndLessonId(userId, lessonId)
                .orElseGet(() -> newProgress(userId, lesson));
        if (!LessonStatus.COMPLETED.name().equals(progress.getStatus())) {
            progress.setStatus(LessonStatus.COMPLETED.name());
            progress.setStartedAt(progress.getStartedAt() == null ? LocalDateTime.now() : progress.getStartedAt());
            progress.setCompletedAt(LocalDateTime.now());
            progressRepository.save(progress);
        }
        return toResponse(progress);
    }

    @Override
    @Transactional(readOnly = true)
    public LessonProgressResponse status(Long userId, Long lessonId) {
        requirePublishedLesson(lessonId);
        return progressRepository.findByUserIdAndLessonId(userId, lessonId)
                .map(LearningProgressServiceImpl::toResponse)
                .orElseGet(() -> LessonProgressResponse.notStarted(lessonId));
    }

    private LessonProgress newProgress(Long userId, Lesson lesson) {
        return LessonProgress.builder()
                .user(em.getReference(User.class, userId))
                .lesson(lesson)
                .status(LessonStatus.NOT_STARTED.name())
                .build();
    }

    private Lesson requirePublishedLesson(Long lessonId) {
        if (lessonId == null || lessonId <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Lesson not found.");
        }
        return catalog.findPublishedLessonDetail(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found."));
    }

    private static LessonProgressResponse toResponse(LessonProgress progress) {
        return new LessonProgressResponse(
                progress.getLesson().getId(),
                progress.getStatus(),
                progress.getStartedAt(),
                progress.getCompletedAt());
    }
}
