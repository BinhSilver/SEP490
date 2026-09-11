package com.jlas.shadowing.model;

import jakarta.persistence.*;
import lombok.*;

@Entity(name = "ShadowingVocabularyEntry")
@Table(name = "vocabulary_entry")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VocabularyEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 255)
    private String expression;
    @Column(name = "normalized_expression", nullable = false, length = 255)
    private String normalizedExpression;
    @Column(nullable = false, length = 255)
    private String reading;
    @Column(name = "normalized_reading", nullable = false, length = 255)
    private String normalizedReading;
    @Column(columnDefinition = "TEXT")
    private String meaning;
    @Column(name = "word_type", length = 50)
    private String wordType;
    @Column(name = "jlpt_level", length = 10)
    private String jlptLevel;
    @Column(name = "example_ja", columnDefinition = "TEXT")
    private String exampleJa;
    @Column(name = "example_vi", columnDefinition = "TEXT")
    private String exampleVi;
    @Column(name = "reading_romaji", length = 255)
    private String readingRomaji;
    @Column(name = "usage_scope", nullable = false, length = 20)
    @Builder.Default
    private String usageScope = "TEXTBOOK";
    @Column(name = "dictionary_status", nullable = false, length = 20)
    @Builder.Default
    private String dictionaryStatus = "PUBLISHED";
}
