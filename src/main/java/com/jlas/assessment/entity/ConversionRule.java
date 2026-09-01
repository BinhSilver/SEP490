package com.jlas.assessment.entity;

import com.jlas.dictionary.entity.Lesson;
import jakarta.persistence.*;
import lombok.*;

/**
 * Quy tắc chuyển âm; chỉ tồn tại với lesson Amon/Onyomi/Kunyomi.
 * Table: conversion_rule
 */
@Entity
@Table(name = "conversion_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_conversion_rule_lesson"))
    private Lesson lesson;

    @Column(name = "source_sound", length = 50)
    private String sourceSound;

    @Column(name = "target_sound", length = 50)
    private String targetSound;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "example_breakdown", columnDefinition = "TEXT")
    private String exampleBreakdown;

    @Column(name = "tip", columnDefinition = "TEXT")
    private String tip;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;
}
