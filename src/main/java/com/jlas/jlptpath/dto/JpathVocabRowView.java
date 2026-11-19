package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathItemType;

/**
 * [JLAS-W0108] One row of the vocabulary list, the notebook or the known store; state NEW|LEARNING|NOTEBOOK|KNOWN.
 * Kanji in the notebook/known store use the same row ({@code expression} = the character, {@code reading} = its first
 * level reading as a label); {@code audioUrl} is null when there is no file. {@code notebookSource} (owner 20/11) = how a
 * notebook row got there (e.g. SMART_TEST), null outside the notebook.
 */
public record JpathVocabRowView(String code, JpathItemType itemType, String expression, String reading, String romaji,
                                String meaning, String wordType, String topicKey, String state, boolean hard,
                                boolean learnable, Long dictionaryEntryId, String audioUrl,
                                String notebookSource) {

    public JpathVocabRowView(String code, JpathItemType itemType, String expression, String reading, String romaji,
                             String meaning, String wordType, String topicKey, String state, boolean hard,
                             boolean learnable, Long dictionaryEntryId, String audioUrl) {
        this(code, itemType, expression, reading, romaji, meaning, wordType, topicKey, state, hard, learnable,
                dictionaryEntryId, audioUrl, null);
    }
}
