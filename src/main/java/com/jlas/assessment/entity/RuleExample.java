package com.jlas.assessment.entity;

import com.jlas.dictionary.entity.Kanji;
import jakarta.persistence.*;
import lombok.*;

/**
 * Ví dụ sư phạm cho rule, neo vào chữ Kanji đang minh họa.
 * Table: rule_example
 */
@Entity
@Table(name = "rule_example")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleExample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_rule_example_rule"))
    private ConversionRule rule;

    /** Chữ chính áp dụng rule. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kanji_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_rule_example_kanji"))
    private Kanji kanji;

    /** Từ/cụm từ dùng làm ví dụ. */
    @Column(name = "example_expression", nullable = false, length = 255)
    private String exampleExpression;

    /** Cách đọc của ví dụ trong ngữ cảnh. */
    @Column(name = "example_reading", length = 255)
    private String exampleReading;

    @Column(name = "breakdown", columnDefinition = "TEXT")
    private String breakdown;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;
}
