package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathGrammarFilter;
import com.jlas.jlptpath.dto.JpathGrammarLessonView;
import com.jlas.jlptpath.dto.JpathGrammarUnitView;
import com.jlas.jlptpath.dto.JpathKanaRowView;
import com.jlas.jlptpath.dto.JpathKanjiFilter;
import com.jlas.jlptpath.dto.JpathKanjiRowView;
import com.jlas.jlptpath.dto.JpathPage;
import com.jlas.jlptpath.dto.JpathTopicCardView;
import com.jlas.jlptpath.dto.JpathVocabFilter;
import com.jlas.jlptpath.dto.JpathVocabRowView;
import java.util.List;

/**
 * [JLAS-W0108] Read-only lists of the JLPT path: vocabulary topics and words, kanji, grammar units and lessons, the
 * kana table, the notebook and the known store. Only visible content is listed; every row carries the learner's
 * state (NEW when there is no row). A level that is not N1..N5 → {@code BAD_REQUEST}; a level that is not released →
 * {@code JPATH_LEVEL_NOT_RELEASED}. Pages are 0-based with {@link #PAGE_SIZE} rows.
 */
public interface JpathListService {

    int PAGE_SIZE = 50;

    /** Topic cards of {@code level} in topic order; topics without a visible word are left out. */
    List<JpathTopicCardView> topics(Long userId, String level);

    /** Words of a level in teaching order, filtered; facets {@code topics} and {@code states}. */
    JpathPage<JpathVocabRowView> vocabulary(Long userId, JpathVocabFilter filter);

    /** Kanji of a level, filtered and sorted; facet {@code radicals}. */
    JpathPage<JpathKanjiRowView> kanji(Long userId, JpathKanjiFilter filter);

    /** Grammar units of a level in order with their matching patterns; units without a match are left out. */
    List<JpathGrammarUnitView> grammar(Long userId, JpathGrammarFilter filter);

    /** The lesson of one visible pattern; hidden or unknown → {@code JPATH_ITEM_NOT_FOUND}. */
    JpathGrammarLessonView grammarLesson(Long userId, String code);

    /** Kana rows of {@code script} (HIRAGANA/KATAKANA, any case; null = both) in teaching order. */
    List<JpathKanaRowView> kana(Long userId, String script);

    /**
     * Notebook items of {@code tab} (VOCAB or KANJI) across every visible level, newest {@code notebook_since}
     * first. GRAMMAR and KANA have no notebook → empty page.
     */
    JpathPage<JpathVocabRowView> notebook(Long userId, String tab, int page);

    /** KNOWN items of {@code tab} (VOCAB, KANJI or GRAMMAR) across every visible level, newest {@code known_at} first. */
    JpathPage<JpathVocabRowView> known(Long userId, String tab, int page);
}
