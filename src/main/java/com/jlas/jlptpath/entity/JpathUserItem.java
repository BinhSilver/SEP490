package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathNotebookSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] Learner state of one item (jpath_user_item); no row means not studied yet. The SRS columns
 * (nextReviewAt, lastReviewedAt, firstLearnedAt, countedAt, notebookSince, knownAt) hold the learner's local time.
 * level is NULL for KANA. knownSource: USER, SKIP_CHECK, TOPIC_CHECK, or TOPIC_PEND on a LEARNING row (topic item
 * not asked yet). GRAMMAR items never go to NOTEBOOK (DDL CHECK).
 */
@Entity
@Table(name = "jpath_user_item", uniqueConstraints = @UniqueConstraint(
        name = "uk_jpath_item_user_code", columnNames = {"user_id", "item_type", "item_code"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathUserItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 8)
    private JpathItemType itemType;

    @Column(name = "item_code", nullable = false, length = 24)
    private String itemCode;

    @Column(name = "level", length = 2)
    private String level;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 10)
    private JpathItemState state;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "repetitions", nullable = false)
    private Integer repetitions = 0;

    @Builder.Default
    @Column(name = "interval_days", nullable = false)
    private Integer intervalDays = 0;

    @Builder.Default
    @Column(name = "easiness", nullable = false, precision = 4, scale = 2)
    private BigDecimal easiness = new BigDecimal("2.50");

    @Column(name = "next_review_at")
    private LocalDateTime nextReviewAt;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "first_learned_at")
    private LocalDateTime firstLearnedAt;

    @Column(name = "counted_at")
    private LocalDateTime countedAt;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "forget_count", nullable = false)
    private Integer forgetCount = 0;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "correct_streak", nullable = false)
    private Integer correctStreak = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "notebook_source", length = 12)
    private JpathNotebookSource notebookSource;

    @Column(name = "notebook_since")
    private LocalDateTime notebookSince;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "notebook_hits", nullable = false)
    private Integer notebookHits = 0;

    @Column(name = "notebook_last_hit_day")
    private LocalDate notebookLastHitDay;

    @Column(name = "known_at")
    private LocalDateTime knownAt;

    @Column(name = "known_source", length = 12)
    private String knownSource;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
