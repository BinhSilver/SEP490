package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Chương/phần của textbook.
 * Table: unit
 */
@Entity
@Table(name = "unit", uniqueConstraints =
        @UniqueConstraint(name = "uk_unit_source_code", columnNames = {"textbook_id", "source_code"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Unit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** [JLAS-W0108] Stable key within a textbook; null preserves legacy/module rows. */
    @Column(name = "source_code", length = 64)
    private String sourceCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "textbook_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_unit_textbook"))
    private Textbook textbook;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;
}
