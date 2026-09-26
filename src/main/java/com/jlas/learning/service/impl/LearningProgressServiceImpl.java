package com.jlas.learning.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.common.event.LearningEvent;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.learning.dto.LessonProgressResponse;
import com.jlas.learning.entity.LessonProgress;
import com.jlas.learning.enums.LessonStatus;
import com.jlas.learning.repository.LearningCatalogRepository;
import com.jlas.learning.repository.LearningLessonContentRepository;
import com.jlas.learning.repository.LessonProgressRepository;
import com.jlas.learning.service.LearningProgressService;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link LearningProgressService}. */
@Service
@RequiredArgsConstructor
public class LearningProgressServiceImpl implements LearningProgressService {
    private final LessonProgressRepository progressRepository;
    private final LearningCatalogRepository catalog;
    private final LearningLessonContentRepository contentRepository;
    private final EntityManager em;
    private final ApplicationEventPublisher events;

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
            events.publishEvent(LearningEvent.lessonStarted(userId, lessonId));
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
            LocalDateTime startedAt = progress.getStartedAt() == null
                    ? LocalDateTime.now() : progress.getStartedAt();
            LocalDateTime completedAt = LocalDateTime.now();
            progress.setStatus(LessonStatus.COMPLETED.name());
            progress.setStartedAt(startedAt);
            progress.setCompletedAt(completedAt);
            progressRepository.save(progress);
            int creditedMinutes = creditedMinutes(startedAt, completedAt, lesson.getEstimatedMinutes());
            int vocabularyCount = (int) contentRepository.countVocabularyByLessonId(lessonId);
            events.publishEvent(LearningEvent.lessonCompleted(
                    userId, lessonId, creditedMinutes, vocabularyCount));
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

    /** Credits the actual time spent on the lesson, capped at its estimate so fast passes never over-count. */
    private static int creditedMinutes(LocalDateTime startedAt, LocalDateTime completedAt, Integer estimatedMinutes) {
        long elapsed = Duration.between(startedAt, completedAt).toMinutes();
        long minutes = Math.max(0, elapsed);
        if (estimatedMinutes != null && estimatedMinutes > 0) {
            minutes = Math.min(minutes, estimatedMinutes);
        }
        return (int) minutes;
    }

    private static LessonProgressResponse toResponse(LessonProgress progress) {
        return new LessonProgressResponse(
                progress.getLesson().getId(),
                progress.getStatus(),
                progress.getStartedAt(),
                progress.getCompletedAt());
    }
}
