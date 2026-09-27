package com.jlas.learning.service;

import com.jlas.learning.dto.LearningResourceCandidate;
import java.util.List;
import java.util.Optional;

public interface LearningCatalogSource {
    // Return only published lessons in published textbooks with a primary KNOWLEDGE tag.
    List<LearningResourceCandidate> findPublishedLessons(List<Long> textbookIds);

    /**
     * [JLAS-W0108] Resolve a published grammar point to a focused warm-up resource. A default
     * {@link Optional#empty()} keeps catalog-less callers (unit tests, demo adapters) working;
     * the production JPA source overrides it to return the specific grammar pattern.
     */
    default Optional<LearningResourceCandidate> findGrammarPoint(Long grammarPointId) {
        return Optional.empty();
    }

    /**
     * [JLAS-W0108] Resolve một vocabulary_entry (câu KANJI/VOCABULARY đã sai) tới bài học đã xuất
     * bản dạy từ đó, làm tài nguyên warm-up tập trung. Mặc định {@link Optional#empty()} giữ cho
     * caller không có catalog (unit test, demo adapter) vẫn hoạt động; production JPA source override.
     */
    default Optional<LearningResourceCandidate> findVocabularyItem(Long vocabularyEntryId) {
        return Optional.empty();
    }

    /**
     * [JLAS-W0108] Resolve bài luyện đọc/nghe đã xuất bản ở cấp độ yếu (skill READING/LISTENING)
     * làm tài nguyên warm-up. Mặc định {@link Optional#empty()}; production JPA source override.
     */
    default Optional<LearningResourceCandidate> findComprehensionLesson(String skill, String level) {
        return Optional.empty();
    }

    /**
     * [JLAS-W0122] Resolve một từ vựng/Kanji theo expression (lexeme) khi chỉ biết chữ mặt chữ
     * (notebook canonical_item). Mặc định {@link Optional#empty()}; production JPA source override.
     */
    default Optional<LearningResourceCandidate> findVocabularyItemByExpression(String expression) {
        return Optional.empty();
    }

    /**
     * [JLAS-W0108] Lát D (§12.2) — phân rã các bài học GOAL ngữ pháp thành từng mẫu ngữ pháp
     * ("mục kiến thức") thay vì cả bài. Trả về một candidate cho mỗi grammar point (title = pattern,
     * {@code grammarPointId} set, {@code lessonId} vẫn là bài gốc). Mặc định {@link List#of()} giữ
     * caller không có catalog (unit test, demo adapter) hoạt động; production JPA source override.
     */
    default List<LearningResourceCandidate> findGrammarPointsByLessons(List<Long> lessonIds) {
        return List.of();
    }
}
