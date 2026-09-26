package com.jlas.learning.service.impl;

import com.jlas.learning.dto.LearningResourceCandidate;
import com.jlas.learning.service.LearningCatalogSource;
import com.jlas.learning.repository.LearningCatalogRepository;
import com.jlas.learning.repository.LearningGrammarPointRepository;
import com.jlas.learning.repository.LearningVocabularyRepository;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.LessonRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JpaLearningCatalogSource implements LearningCatalogSource {
    /** [JLAS-W0108] Phút ước lượng cho một điểm ngữ pháp ôn tập (không có duration riêng trong schema). */
    private static final int GRAMMAR_POINT_MINUTES = 10;
    /** [JLAS-W0108] Phút ước lượng cho một từ vựng/Kanji ôn tập. */
    private static final int VOCAB_ITEM_MINUTES = 5;
    /** [JLAS-W0108] Phút ước lượng cho một bài luyện đọc/nghe warm-up. */
    private static final int COMPREHENSION_MINUTES = 15;
    /** [JLAS-W0108] Thứ tự cấp độ JLPT dùng để chọn cấp gần nhất khi thiếu học liệu. */
    private static final List<String> JLPT_LEVELS = List.of("N5", "N4", "N3", "N2", "N1");

    private final LearningCatalogRepository repository;
    private final LearningGrammarPointRepository grammarPoints;
    private final LearningVocabularyRepository vocabulary;
    private final LessonRepository lessons;

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
                lesson.getUnit().getOrderIndex(), lesson.getOrderIndex(), null, null)).toList();
    }

    @Override
    public Optional<LearningResourceCandidate> findGrammarPoint(Long grammarPointId) {
        if (grammarPointId == null) return Optional.empty();
        return grammarPoints.findWithLesson(grammarPointId)
                .filter(point -> "PUBLISHED".equals(point.getLesson().getStatus()))
                .filter(point -> "PUBLISHED".equals(point.getLesson().getUnit().getTextbook().getStatus()))
                .map(point -> {
                    var lesson = point.getLesson();
                    var textbook = lesson.getUnit().getTextbook();
                    var tags = lesson.getKnowledgeTag() == null
                            ? Set.<String>of() : Set.of(lesson.getKnowledgeTag().getCode());
                    return new LearningResourceCandidate(lesson.getId(), point.getPattern(),
                            textbook.getId(), lesson.getUnit().getId(), tags,
                            textbook.getJlptLevel(), GRAMMAR_POINT_MINUTES,
                            lesson.getUnit().getOrderIndex(), lesson.getOrderIndex(), point.getId(), null);
                });
    }

    @Override
    public Optional<LearningResourceCandidate> findVocabularyItem(Long vocabularyEntryId) {
        if (vocabularyEntryId == null) return Optional.empty();
        return vocabulary.findLinksByVocabularyId(vocabularyEntryId).stream().findFirst().map(link -> {
            var entry = link.getVocabularyEntry();
            var lesson = link.getContentItem().getSection().getLesson();
            var textbook = lesson.getUnit().getTextbook();
            var tags = lesson.getKnowledgeTag() == null
                    ? Set.<String>of() : Set.of(lesson.getKnowledgeTag().getCode());
            return new LearningResourceCandidate(lesson.getId(), entry.getExpression(),
                    textbook.getId(), lesson.getUnit().getId(), tags,
                    textbook.getJlptLevel(), VOCAB_ITEM_MINUTES,
                    lesson.getUnit().getOrderIndex(), lesson.getOrderIndex(), null, entry.getId());
        });
    }

    @Override
    public Optional<LearningResourceCandidate> findVocabularyItemByExpression(String expression) {
        if (expression == null || expression.isBlank()) return Optional.empty();
        return vocabulary.findLinksByExpression(expression).stream().findFirst().map(link -> {
            var entry = link.getVocabularyEntry();
            var lesson = link.getContentItem().getSection().getLesson();
            var textbook = lesson.getUnit().getTextbook();
            var tags = lesson.getKnowledgeTag() == null
                    ? Set.<String>of() : Set.of(lesson.getKnowledgeTag().getCode());
            return new LearningResourceCandidate(lesson.getId(), entry.getExpression(),
                    textbook.getId(), lesson.getUnit().getId(), tags,
                    textbook.getJlptLevel(), VOCAB_ITEM_MINUTES,
                    lesson.getUnit().getOrderIndex(), lesson.getOrderIndex(), null, entry.getId());
        });
    }

    @Override
    public Optional<LearningResourceCandidate> findComprehensionLesson(String skill, String level) {
        if (skill == null || level == null) return Optional.empty();
        var published = lessons.findPublishedBySkill(skill);
        if (published.isEmpty()) return Optional.empty();
        // [JLAS-W0108] Ưu tiên đúng cấp, rồi cấp gần nhất (tie-break ưu tiên cấp dễ hơn) để warm-up
        // không còn báo "thiếu học liệu" chỉ vì cấp độ chính xác chưa có bài READING/LISTENING.
        return published.stream()
                .min(Comparator
                        .comparingInt((Lesson lesson) -> levelDistance(lesson, level))
                        .thenComparingInt(lesson -> JLPT_LEVELS.indexOf(lesson.getUnit().getTextbook().getJlptLevel())))
                .map(this::toComprehensionCandidate);
    }

    private static int levelDistance(Lesson lesson, String targetLevel) {
        int target = JLPT_LEVELS.indexOf(targetLevel);
        int actual = JLPT_LEVELS.indexOf(lesson.getUnit().getTextbook().getJlptLevel());
        if (target < 0 || actual < 0) return Integer.MAX_VALUE;
        return Math.abs(actual - target);
    }

    private LearningResourceCandidate toComprehensionCandidate(Lesson lesson) {
        var textbook = lesson.getUnit().getTextbook();
        var tags = lesson.getKnowledgeTag() == null
                ? Set.<String>of() : Set.of(lesson.getKnowledgeTag().getCode());
        return new LearningResourceCandidate(lesson.getId(), lesson.getTitle(),
                textbook.getId(), lesson.getUnit().getId(), tags,
                textbook.getJlptLevel(), COMPREHENSION_MINUTES,
                lesson.getUnit().getOrderIndex(), lesson.getOrderIndex(), null, null);
    }
}
