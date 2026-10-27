package com.jlas.assessment.dto;

import java.util.List;

public record JlptStudyResponse(String code, String title, String level,
        List<Item> vocabulary, List<Item> grammar) {
    public record Item(String key, String title, String reading, String meaning,
            String exampleJa, String exampleVi, String note, String level,
            boolean asked, boolean correctAnswer, List<Source> sources,
            String hanViet, String questionType, String lessonUrl, List<String> expressions) {
        public Item(String key, String title, String reading, String meaning,
                String exampleJa, String exampleVi, String note, String level,
                boolean asked, boolean correctAnswer, List<Source> sources,
                String hanViet, String questionType, String lessonUrl) {
            this(key, title, reading, meaning, exampleJa, exampleVi, note, level,
                    asked, correctAnswer, sources, hanViet, questionType, lessonUrl, List.of());
        }
        public Item(String key, String title, String reading, String meaning,
                String exampleJa, String exampleVi, String note, String level,
                boolean asked, boolean correctAnswer, List<Source> sources) {
            this(key, title, reading, meaning, exampleJa, exampleVi, note, level,
                    asked, correctAnswer, sources, null, null, null, List.of());
        }
    }
    public record Source(long questionId, int number, String part, String type, String surface) {}
}
