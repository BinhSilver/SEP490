package com.jlas.learning.service.impl;

import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.learning.dto.LessonContentResponse;
import com.jlas.learning.repository.LearningCatalogRepository;
import com.jlas.learning.repository.LearningLessonContentRepository;
import com.jlas.learning.service.LessonContentService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link LessonContentService}. */
@Service
@RequiredArgsConstructor
public class LessonContentServiceImpl implements LessonContentService {
    private final LearningCatalogRepository catalog;
    private final LearningLessonContentRepository contentRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<LessonContentResponse> findPublished(Long lessonId) {
        if (lessonId == null || lessonId <= 0) {
            return Optional.empty();
        }
        if (catalog.findPublishedLessonDetail(lessonId).isEmpty()) {
            return Optional.empty();
        }

        List<LessonSection> sections = contentRepository.findSections(lessonId);
        List<ContentItem> items = contentRepository.findContentItems(lessonId);
        List<GrammarPoint> grammarPoints = contentRepository.findGrammarPoints(lessonId);

        Map<Long, List<LessonContentResponse.ItemContent>> itemsBySection = new LinkedHashMap<>();
        for (ContentItem item : items) {
            itemsBySection.computeIfAbsent(item.getSection().getId(), key -> new ArrayList<>())
                    .add(new LessonContentResponse.ItemContent(item.getId(), item.getContentType(),
                            item.getTitle(), item.getContent(), item.getTranslatedText()));
        }

        List<LessonContentResponse.SectionContent> sectionDtos = sections.stream()
                .map(section -> new LessonContentResponse.SectionContent(section.getId(), section.getSectionType(),
                        section.getTitle(), section.getOrderIndex(),
                        itemsBySection.getOrDefault(section.getId(), List.of())))
                .toList();

        List<LessonContentResponse.GrammarPointContent> grammarDtos = grammarPoints.stream()
                .map(point -> new LessonContentResponse.GrammarPointContent(point.getId(), point.getPattern(),
                        point.getMeaning(), point.getExample(), point.getNote()))
                .toList();

        return Optional.of(new LessonContentResponse(lessonId, sectionDtos, grammarDtos));
    }
}
