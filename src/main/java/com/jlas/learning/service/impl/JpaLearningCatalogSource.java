package com.jlas.learning.service.impl;

import com.jlas.learning.dto.LearningResourceCandidate;
import com.jlas.learning.service.LearningCatalogSource;
import com.jlas.learning.repository.LearningCatalogRepository;
import com.jlas.learning.repository.LearningGrammarPointRepository;
import com.jlas.learning.repository.LearningVocabularyRepository;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.LessonRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
                lesson.getUnit().getOrderIndex(), lesson.getOrderIndex(), null, null, lesson.getSkill())).toList();
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
    public List<LearningResourceCandidate> findGrammarPointsByLessons(List<Long> lessonIds) {
        if (lessonIds == null || lessonIds.isEmpty()) return List.of();
        return grammarPoints.findByLessonIds(lessonIds).stream()
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
                })
                .toList();
    }

    @Override
    public Optional<LearningResourceCandidate> findComprehensionLesson(String skill, String level) {
        if (skill == null || level == null) return Optional.empty();
        // [JLAS-W0108] Chỉ lấy bài có cấp JLPT — loại bỏ bài Kana/foundation (textbook jlpt_level NULL)
        // vì đó không phải passage đọc hiểu/nghe hiểu.
        // 1) Ưu tiên bài chuyên READING/LISTENING ĐÚNG cấp (N3/N2/N1 có bài nghe riêng).
        var exactSkill = lessons.findPublishedByJlptLevelAndSkill(level, skill).stream()
                .filter(lesson -> lesson.getUnit().getTextbook().getJlptLevel() != null)
                .toList();
        if (!exactSkill.isEmpty()) {
            return exactSkill.stream().findFirst().map(this::toComprehensionCandidate);
        }
        // 2) [JLAS-W0108] Không có bài chuyên kĩ năng đúng cấp -> lấy bài ĐÚNG cấp có chứa
        //    content_item đúng loại (READING/LISTENING). Ở N5/N4 phần đọc/nghe nằm trong
        //    content_item của bài tổng hợp GRAMMAR — đây là nguồn dữ liệu thật theo cấp độ,
        //    không bịa bài học hay gắn nhầm dữ liệu vào sách.
        var contentLessons = lessons.findPublishedByJlptLevelAndContentType(level, skill).stream()
                .filter(lesson -> lesson.getUnit().getTextbook().getJlptLevel() != null)
                .toList();
        if (!contentLessons.isEmpty()) {
            return contentLessons.stream().findFirst().map(this::toComprehensionCandidate);
        }
        // 3) Không có content_item riêng -> lấy bài học tổng hợp ĐÚNG cấp (N5/N4: mỗi bài
        //    GRAMMAR đều chứa sẵn phần đọc + nghe) thay vì lạc sang bài N3 không liên quan như trước.
        var exactAny = lessons.findPublishedByTextbookJlptLevel(level).stream()
                .filter(lesson -> lesson.getUnit().getTextbook().getJlptLevel() != null)
                .toList();
        if (!exactAny.isEmpty()) {
            return exactAny.stream().findFirst().map(this::toComprehensionCandidate);
        }
        // 4) Fallback cấp gần nhất theo skill (tie-break ưu tiên cấp dễ hơn).
        var published = lessons.findPublishedBySkill(skill).stream()
                .filter(lesson -> lesson.getUnit().getTextbook().getJlptLevel() != null)
                .toList();
        if (published.isEmpty()) return Optional.empty();
        return published.stream()
                .min(Comparator
                        .comparingInt((Lesson lesson) -> levelDistance(lesson, level))
                        .thenComparingInt(lesson -> JLPT_LEVELS.indexOf(lesson.getUnit().getTextbook().getJlptLevel())))
                .map(this::toComprehensionCandidate);
    }

    /**
     * [JLAS-W0108] P3 — danh sách bài luyện đọc/nghe ĐÚNG cấp (dedup theo lessonId, giữ thứ tự ưu tiên
     * bài chuyên skill → bài chứa content_item → bài tổng hợp cùng cấp). KHÔNG fallback cấp gần nhất:
     * thiếu đủ số bài đúng cấp thì selector báo gap thay vì dùng tài nguyên sai cấp (§6.1).
     */
    @Override
    public List<LearningResourceCandidate> findComprehensionLessons(String skill, String level) {
        if (skill == null || level == null) return List.of();
        Map<Long, LearningResourceCandidate> byId = new LinkedHashMap<>();
        for (Lesson lesson : orderedComprehensionLessons(skill, level)) {
            byId.putIfAbsent(lesson.getId(), toComprehensionCandidate(lesson));
        }
        return List.copyOf(byId.values());
    }

    private List<Lesson> orderedComprehensionLessons(String skill, String level) {
        List<Lesson> ordered = new ArrayList<>();
        lessons.findPublishedByJlptLevelAndSkill(level, skill).stream()
                .filter(lesson -> lesson.getUnit().getTextbook().getJlptLevel() != null)
                .forEach(ordered::add);
        lessons.findPublishedByJlptLevelAndContentType(level, skill).stream()
                .filter(lesson -> lesson.getUnit().getTextbook().getJlptLevel() != null)
                .forEach(ordered::add);
        lessons.findPublishedByTextbookJlptLevel(level).stream()
                .filter(lesson -> lesson.getUnit().getTextbook().getJlptLevel() != null)
                .forEach(ordered::add);
        return ordered;
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
