package com.jlas.jlptpath.service;

/**
 * [JLAS-W0108] Moves the words and kanji a learner got wrong in a submitted Smart Test into the path notebook. Looked up
 * through an {@code ObjectProvider}: without an implementation both counts are 0.
 */
public interface JpathSmartTestImporter {

    /** How many items {@link #importWrongItems} would put into the notebook; nothing is written. */
    int previewWrongItemCount(Long userId, Long attemptId);

    /** Puts the wrong Smart Test items into the notebook and returns how many were added. */
    int importWrongItems(Long userId, Long attemptId);
}
