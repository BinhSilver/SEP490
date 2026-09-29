package com.jlas.learning.service.impl;

import com.jlas.dictionary.entity.Lesson;
import com.jlas.learning.dto.LessonDetailResponse;
import com.jlas.learning.repository.LearningCatalogRepository;
import com.jlas.learning.service.LessonDetailService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cài đặt {@link LessonDetailService}. */
@Service
@RequiredArgsConstructor
public class LessonDetailServiceImpl implements LessonDetailService {
    private final LearningCatalogRepository catalog;

    @Override
    @Transactional(readOnly = true)
    public Optional<LessonDetailResponse> findPublished(Long lessonId) {
        if (lessonId == null || lessonId <= 0) {
            return Optional.empty();
        }
        return catalog.findPublishedLessonDetail(lessonId).map(LessonDetailServiceImpl::toResponse);
    }

    private static LessonDetailResponse toResponse(Lesson lesson) {
        var unit = lesson.getUnit();
        var textbook = unit.getTextbook();
        var tag = lesson.getKnowledgeTag();
        return new LessonDetailResponse(
                lesson.getId(),
                lesson.getTitle(),
                lesson.getDescription(),
                lesson.getSkill(),
                textbook.getJlptLevel(),
                lesson.getEstimatedMinutes(),
                tag.getName(),
                textbook.getTitle(),
                unit.getTitle());
    }
}
