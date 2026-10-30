package com.jlas.learning.dto;
import com.jlas.dictionary.entity.Lesson;
import java.util.List;
import java.util.Map;
public record InteractiveContentSnapshot(Lesson lesson, String revision, List<Long> vocabularyIds,
        List<Long> kanjiIds, List<Long> grammarPointIds, List<Long> dialogueLineIds, List<CoverageGap> gaps,
        Map<Long, VocabularyLessonContext> vocabularyContexts, List<Long> readingQuestionIds,
        List<Long> listeningQuestionIds) {
    public InteractiveContentSnapshot { vocabularyIds=List.copyOf(vocabularyIds); kanjiIds=List.copyOf(kanjiIds);
        grammarPointIds=List.copyOf(grammarPointIds); dialogueLineIds=List.copyOf(dialogueLineIds); gaps=List.copyOf(gaps);
        vocabularyContexts=Map.copyOf(vocabularyContexts); readingQuestionIds=List.copyOf(readingQuestionIds);
        listeningQuestionIds=List.copyOf(listeningQuestionIds); }
    public InteractiveContentSnapshot(Lesson lesson, String revision, List<Long> vocabularyIds,
            List<Long> kanjiIds, List<Long> grammarPointIds, List<Long> dialogueLineIds, List<CoverageGap> gaps,
            Map<Long, VocabularyLessonContext> vocabularyContexts) {
        this(lesson,revision,vocabularyIds,kanjiIds,grammarPointIds,dialogueLineIds,gaps,vocabularyContexts,List.of(),List.of());
    }
    public InteractiveContentSnapshot(Lesson lesson, String revision, List<Long> vocabularyIds,
            List<Long> kanjiIds, List<Long> grammarPointIds, List<Long> dialogueLineIds, List<CoverageGap> gaps) {
        this(lesson, revision, vocabularyIds, kanjiIds, grammarPointIds, dialogueLineIds, gaps, Map.of(),List.of(),List.of());
    }
    public boolean ready() { return gaps.isEmpty(); }
}
