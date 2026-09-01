package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Phần nội dung trong lesson.
 * Table: lesson_section
 */
@Entity
@Table(name = "lesson_section")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_section_lesson"))
    private Lesson lesson;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** EXPLANATION/EXAMPLE/EXERCISE. */
    @Column(name = "section_type", nullable = false, length = 20)
    private String sectionType;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;
}
