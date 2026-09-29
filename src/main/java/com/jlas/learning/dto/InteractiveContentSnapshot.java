package com.jlas.learning.dto;
import com.jlas.dictionary.entity.Lesson;
import java.util.List;
public record InteractiveContentSnapshot(Lesson lesson, String revision, List<Long> vocabularyIds,
        List<Long> kanjiIds, List<Long> grammarPointIds, List<Long> dialogueLineIds, List<CoverageGap> gaps) {
    public InteractiveContentSnapshot { vocabularyIds=List.copyOf(vocabularyIds); kanjiIds=List.copyOf(kanjiIds);
        grammarPointIds=List.copyOf(grammarPointIds); dialogueLineIds=List.copyOf(dialogueLineIds); gaps=List.copyOf(gaps); }
    public boolean ready() { return gaps.isEmpty(); }
}
