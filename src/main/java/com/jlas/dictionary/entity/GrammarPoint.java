package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Mẫu ngữ pháp có cấu trúc trong lesson.
 * Table: grammar_point
 */
@Entity
@Table(name = "grammar_point")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GrammarPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_grammar_lesson"))
    private Lesson lesson;

    /** Tag KNOWLEDGE chi tiết nếu có. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id",
            foreignKey = @ForeignKey(name = "fk_grammar_tag"))
    private Tag tag;

    @Column(name = "pattern", nullable = false, length = 500)
    private String pattern;

    @Column(name = "meaning", nullable = false, columnDefinition = "TEXT")
    private String meaning;

    @Column(name = "example", columnDefinition = "TEXT")
    private String example;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;
}
