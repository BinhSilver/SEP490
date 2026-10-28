package com.jlas.learning.service.impl;

import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.learning.dto.InteractiveContentSnapshot;
import com.jlas.learning.dto.LessonReviewItem;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.InteractiveContentCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * [JLAS-W0108] OD-D2-05: the "Ôn — đã học ở …" list of a Vocabulary/Kanji activity on a JLPT path. A path's membership
 * never changes after it is made, so the list is computed once per activity and kept (bounded) in memory; flips and
 * checks reuse it without inspecting the lesson again.
 */
@Component
class LessonReviewLookup {
    private static final int MAX_CACHED = 2000;
    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final InteractiveContentCatalog catalog;
    private final VocabularyEntryRepository vocabulary;
    private final KanjiRepository kanji;
    private final Map<String, List<LessonReviewItem>> cache = new ConcurrentHashMap<>();

    LessonReviewLookup(UserLearningPathItemRepository items, LearningActivityMemberRepository members,
            InteractiveContentCatalog catalog, VocabularyEntryRepository vocabulary, KanjiRepository kanji) {
        this.items = items;
        this.members = members;
        this.catalog = catalog;
        this.vocabulary = vocabulary;
        this.kanji = kanji;
    }

    List<LessonReviewItem> vocabulary(Long activityId) {
        return cached(LearningActivityType.VOCABULARY, activityId, ids -> vocabulary.findAllById(ids).stream()
                .collect(Collectors.toMap(VocabularyEntry::getId, entry -> new String[] {
                        entry.getExpression(), entry.getReading(), entry.getMeaning() }, (a, b) -> a)));
    }

    List<LessonReviewItem> kanji(Long activityId) {
        return cached(LearningActivityType.KANJI, activityId, ids -> kanji.findAllById(ids).stream()
                .collect(Collectors.toMap(Kanji::getId, entry -> new String[] { entry.getCharacter(), null,
                        // Hán Việt is a Vietnamese reading, not a Japanese one: it goes with the meaning.
                        java.util.stream.Stream.of(entry.getHanViet(), entry.getMeaningVi())
                                .filter(text -> text != null && !text.isBlank()).collect(Collectors.joining(" · ")) },
                        (a, b) -> a)));
    }

    private List<LessonReviewItem> cached(LearningActivityType type, Long activityId,
            Function<List<Long>, Map<Long, String[]>> details) {
        if (activityId == null) return List.of();
        String key = type + ":" + activityId;
        List<LessonReviewItem> known = cache.get(key);
        if (known != null) return known;
        List<LessonReviewItem> computed = compute(type, activityId, details);
        if (cache.size() >= MAX_CACHED) cache.clear();
        cache.put(key, computed);
        return computed;
    }

    private List<LessonReviewItem> compute(LearningActivityType type, Long activityId,
            Function<List<Long>, Map<Long, String[]>> details) {
        UserLearningPathItem activity = items.findById(activityId).orElse(null);
        if (activity == null || activity.getLesson() == null || activity.getLearningPath() == null
                || com.jlas.learning.service.GoalPathPolicy.isCommunicationGoal(activity.getLearningPath())) return List.of();
        var unit = activity.getLesson().getUnit();
        String level = unit == null || unit.getTextbook() == null ? null : unit.getTextbook().getJlptLevel();
        InteractiveContentSnapshot snapshot;
        try {
            snapshot = catalog.inspect(activity.getLesson().getId(), level);
        } catch (RuntimeException unavailable) {
            return List.of(); // the review list is a convenience; it never blocks the lesson
        }
        List<Long> lessonIds = type == LearningActivityType.KANJI ? snapshot.kanjiIds() : snapshot.vocabularyIds();
        var reviews = LessonReviewItems.forActivity(activity, type, lessonIds, items, members);
        if (reviews.isEmpty()) return List.of();
        Map<Long, String[]> shown = details.apply(reviews.stream().map(LessonReviewItems.Review::canonicalId).toList());
        return reviews.stream().filter(review -> shown.containsKey(review.canonicalId())).map(review -> {
            String[] text = shown.get(review.canonicalId());
            return new LessonReviewItem(review.canonicalId(), text[0], text[1], text[2], review.learnedIn());
        }).toList();
    }
}
