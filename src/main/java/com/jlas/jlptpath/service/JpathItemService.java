package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathItemDetailView;
import com.jlas.jlptpath.dto.JpathItemStateView;
import com.jlas.jlptpath.dto.JpathKnownResult;
import com.jlas.jlptpath.dto.JpathTodayView;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathNotebookSource;
import java.util.Optional;

/**
 * [JLAS-W0108] One item of the JLPT path seen from the list drawer: its detail, "Tôi biết từ này" and the manual
 * notebook. Writers take the learner's ACTIVE path lock first and fail {@code JPATH_NO_ACTIVE_PATH} without one.
 */
public interface JpathItemService {

    /** Detail of a visible item; hidden, unreleased or unknown → {@code JPATH_ITEM_NOT_FOUND}. */
    JpathItemDetailView detail(Long userId, JpathItemType type, String code);

    /**
     * {@link #detail} when the item is visible, empty otherwise — never throws for a hidden item, so a caller inside
     * a transaction is not marked rollback-only (study cards of items hidden while a session is open, #22).
     */
    Optional<JpathItemDetailView> detailIfVisible(Long userId, JpathItemType type, String code);

    /**
     * Puts a learnable pattern not started yet into today's TODAY round (preflight C16): it takes the first grammar
     * slot not reached of the open GRAMMAR/COMBINED session, or a TODAY session is started with it. Already started →
     * nothing changes. Not transactional (today's plan is made to exist first); returns today's block.
     */
    JpathTodayView queueGrammarToday(Long userId, String code);

    /**
     * Marks an item the learner already has a row for as KNOWN (known_source USER; counted at once when it was
     * learned before, #16). An already KNOWN item is left as it is. An item without a row (NEW) stays NEW
     * and gets a one-question SKIP_ONE check to take first ({@code checkToken}).
     */
    JpathKnownResult markKnown(Long userId, JpathItemType type, String code);

    /**
     * Puts a visible, learnable word or kanji in the notebook, creating its row when needed. GRAMMAR and KANA →
     * {@code JPATH_GRAMMAR_NO_NOTEBOOK}.
     */
    JpathItemStateView addToNotebook(Long userId, JpathItemType type, String code, JpathNotebookSource source);

    /** Takes an item out of the notebook back to LEARNING; an item not in the notebook is returned unchanged. */
    JpathItemStateView removeFromNotebook(Long userId, JpathItemType type, String code);
}
