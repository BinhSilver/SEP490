package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Chương/phần của textbook.
 * Table: unit
 */
@Entity
@Table(name = "unit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Unit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
