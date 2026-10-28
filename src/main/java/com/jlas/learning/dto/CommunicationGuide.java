package com.jlas.learning.dto;

import java.util.List;

/** [JLAS-W0108] Owner 2026-10-19: how a communication roadmap groups its units. Each topic lists its vocabulary
 * lesson first, then its dialogue situations easy → hard; survey focus topics come first and are flagged.
 * Grouping only guides the learner — every unit stays freely selectable. BrSE paths also carry optional book
 * suggestions (§2.24, Q5) that never gate a situation. */
public record CommunicationGuide(String learnerSelfLevel, List<Topic> topics, List<BookSuggestion> bookSuggestions) {
    public CommunicationGuide {
        topics = List.copyOf(topics);
        bookSuggestions = bookSuggestions == null ? List.of() : List.copyOf(bookSuggestions);
    }

    public CommunicationGuide(String learnerSelfLevel, List<Topic> topics) {
        this(learnerSelfLevel, topics, List.of());
    }

    public record Topic(String code, String titleVi, int order, boolean priority, List<Unit> units) {
        public Topic {
            units = List.copyOf(units);
        }
    }

    /** A lesson of the path inside a topic; {@code jlptLevel} is set for dialogue situations only. */
    public record Unit(Long lessonId, boolean vocabularyTopic, String jlptLevel) { }

    /** A J-LAS textbook suggested to a BrSE learner; {@code available} is false while learners cannot open it yet. */
    /** {@code available}: learners can open it; {@code unreviewed}: still DRAFT, shown with a "Chưa duyệt" label (OD-45). */
    public record BookSuggestion(String level, String title, Long textbookId, boolean available, boolean unreviewed) {
        public BookSuggestion(String level, String title, Long textbookId, boolean available) {
            this(level, title, textbookId, available, false);
        }
    }
}
