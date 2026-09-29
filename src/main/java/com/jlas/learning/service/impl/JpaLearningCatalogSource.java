package com.jlas.learning.service.impl;

import com.jlas.learning.dto.LearningResourceCandidate;
import com.jlas.learning.service.LearningCatalogSource;
import com.jlas.learning.repository.LearningCatalogRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JpaLearningCatalogSource implements LearningCatalogSource {
    private final LearningCatalogRepository repository;

    @Override
    public List<LearningResourceCandidate> findPublishedLessons(List<Long> textbookIds) {
        if (textbookIds.isEmpty()) return List.of();
        var lessons = repository.findPublishedLessons(textbookIds);
        if (lessons.isEmpty()) return List.of();
        Map<Long, Set<String>> tags = new HashMap<>();
        lessons.forEach(lesson -> tags.put(lesson.getId(), new HashSet<>(Set.of(lesson.getKnowledgeTag().getCode()))));
        repository.findKnowledgeTags(lessons.stream().map(lesson -> lesson.getId()).toList())
                .forEach(link -> tags.get(link.getLesson().getId()).add(link.getTag().getCode()));
        return lessons.stream().map(lesson -> new LearningResourceCandidate(lesson.getId(), lesson.getTitle(),
                lesson.getUnit().getTextbook().getId(), lesson.getUnit().getId(), tags.get(lesson.getId()),
                lesson.getUnit().getTextbook().getJlptLevel(), lesson.getEstimatedMinutes(),
                lesson.getUnit().getOrderIndex(), lesson.getOrderIndex())).toList();
    }
}
