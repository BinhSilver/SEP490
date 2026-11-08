package com.jlas.learning.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.dictionary.repository.ContentVocabularyRepository;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] F1-3 (owner 2026-10-27): which words of a compiled-book chapter are EXTENDED (not in the level's main course
 * book and in fewer than two source books). Read from {@code learning/compiled-vocabulary-tiers.json}, generated with the
 * unit labels; a missing file means every word is CORE. Only a path squeezed by its exam date leaves them out.
 */
@Component
public class CompiledVocabularyTiers {
    static final String RESOURCE = "learning/compiled-vocabulary-tiers.json";

    private final ContentVocabularyRepository links;
    private final Set<Long> extendedItems;

    @org.springframework.beans.factory.annotation.Autowired
    public CompiledVocabularyTiers(ContentVocabularyRepository links) {
        this(links, load());
    }

    CompiledVocabularyTiers(ContentVocabularyRepository links, Set<Long> extendedItems) {
        this.links = links;
        this.extendedItems = Set.copyOf(extendedItems);
    }

    /** The lesson's EXTENDED vocabulary entries (by their chapter content item). */
    public Set<Long> extendedVocabularyIds(Long lessonId) {
        if (extendedItems.isEmpty() || lessonId == null) return Set.of();
        return links.findLearningContextsByLessonId(lessonId).stream()
                .filter(link -> extendedItems.contains(link.getId().getContentItemId()))
                .map(link -> link.getId().getVocabularyId())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<Long> load() {
        try (InputStream in = CompiledVocabularyTiers.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) return Set.of();
            JsonNode extended = new ObjectMapper().readTree(in).path("extended");
            Set<Long> ids = new HashSet<>();
            extended.forEach(level -> level.forEach(id -> ids.add(id.asLong())));
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }
}
