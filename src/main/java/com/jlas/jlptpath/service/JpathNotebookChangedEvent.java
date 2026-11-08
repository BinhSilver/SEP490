package com.jlas.jlptpath.service;

/**
 * [JLAS-W0108] A path vocabulary word entered ({@code added = true}) or left ({@code added = false}) the learner's
 * notebook. Only VOCAB items linked to a dictionary entry publish it; a listener mirrors it into the team notebook.
 */
public record JpathNotebookChangedEvent(Long userId, Long dictionaryEntryId, boolean added) {
}
