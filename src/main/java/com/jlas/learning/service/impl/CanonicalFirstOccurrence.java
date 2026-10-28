package com.jlas.learning.service.impl;

import com.jlas.learning.dto.InteractiveContentSnapshot;
import com.jlas.learning.dto.VocabularyLessonContext;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [JLAS-W0108] OD-D2-05 (owner 2026-10-23, spec §2.2 / OD-15 "khử trùng theo canonical"): on a new JLPT path a word or
 * Kanji is required — studied and checked — only in the first chapter that teaches it. Later chapters show it as
 * "Ôn — đã học ở …" (read side, {@link LessonReviewItems}), outside their required check. The same word with a
 * different meaning in a later lesson stays required there. A chapter left with a single new item keeps one already
 * learnt item so its choice question still has a real distractor. Feed lessons in path order; one instance per path.
 */
final class CanonicalFirstOccurrence {
    private final Map<Long, Set<String>> vocabularyMeanings = new HashMap<>();
    private final Set<Long> kanji = new HashSet<>();

    InteractiveContentSnapshot keepFirst(InteractiveContentSnapshot snapshot) {
        List<Long> words = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(snapshot.vocabularyIds())) {
            String meaning = meaning(snapshot.vocabularyContexts().get(id));
            if (vocabularyMeanings.computeIfAbsent(id, ignored -> new HashSet<>()).add(meaning)) words.add(id);
        }
        List<Long> characters = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(snapshot.kanjiIds())) {
            if (kanji.add(id)) characters.add(id);
        }
        return new InteractiveContentSnapshot(snapshot.lesson(), snapshot.revision(),
                topUp(words, snapshot.vocabularyIds()), topUp(characters, snapshot.kanjiIds()), snapshot.grammarPointIds(),
                snapshot.dialogueLineIds(), snapshot.gaps(), snapshot.vocabularyContexts(), snapshot.readingQuestionIds(),
                snapshot.listeningQuestionIds());
    }

    /** One new item cannot make a choice question: add back the lesson's first already-learnt item. */
    private static List<Long> topUp(List<Long> kept, List<Long> lesson) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(lesson));
        if (kept.size() != 1 || distinct.size() < 2) return kept;
        Long extra = distinct.stream().filter(id -> !kept.contains(id)).findFirst().orElse(null);
        List<Long> result = new ArrayList<>(distinct.stream().filter(id -> kept.contains(id) || id.equals(extra)).toList());
        return List.copyOf(result);
    }

    private static String meaning(VocabularyLessonContext context) {
        return context == null ? "" : Normalizer.normalize(context.meaning(), Normalizer.Form.NFKC).trim().toLowerCase()
                .replaceAll("\\s+", " ");
    }
}
