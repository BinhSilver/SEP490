package com.jlas.learning.dto;

import java.util.List;

/** Read model for a published lesson's content (sections + grammar points). */
public record LessonContentResponse(
        Long lessonId,
        List<SectionContent> sections,
        List<GrammarPointContent> grammarPoints) {

    public record SectionContent(Long id, String sectionType, String title, Integer orderIndex,
                                 List<ItemContent> items) { }

    public record ItemContent(Long id, String contentType, String title, String content, String translatedText) { }

    public record GrammarPointContent(Long id, String pattern, String meaning, String example, String note) { }
}
