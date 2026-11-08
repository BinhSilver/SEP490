package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] One vocabulary topic card: visible words of the topic split by learner state
 * ({@code total = known + learning + notebook + notLearned}); {@code percentKnown = floor(100·known/total)}.
 */
public record JpathTopicCardView(String topicKey, String title, int total, int known, int learning, int notebook,
                                 int notLearned, int percentKnown) {
}
