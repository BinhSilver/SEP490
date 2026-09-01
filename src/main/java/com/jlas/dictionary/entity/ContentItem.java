package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Nội dung cụ thể của một section.
 * Table: content_item
 */
@Entity
@Table(name = "content_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_content_item_section"))
    private LessonSection section;

    /** READING/GRAMMAR/LISTENING. */
    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "content", columnDefinition = "MEDIUMTEXT")
    private String content;

    /** Bản dịch của nội dung cố định. */
    @Column(name = "translated_text", columnDefinition = "MEDIUMTEXT")
    private String translatedText;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;
}
