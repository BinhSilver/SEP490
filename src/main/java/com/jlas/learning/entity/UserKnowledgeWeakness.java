package com.jlas.learning.entity;

import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Outcome;
import com.jlas.dictionary.entity.Tag;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sổ tay điểm yếu bền vững (Weakness Notebook) theo skill với canonical item.
 * Table: user_knowledge_weakness
 *
 * <p>[JLAS-W0108] Lát 2 — mở rộng từ "điểm yếu gom theo tag KNOWLEDGE" sang notebook per-skill
 * canonical (spec §2.4):</p>
 * <ul>
 *   <li>{@code canonicalItem} là key item chính xác (KANJI:食 / VOCAB:先生 / GP:&lt;id&gt; / READ / LISTEN),
 *       thay vì tạo một tag mới cho mỗi kanji/vocab sai.</li>
 *   <li>{@code tag} nullable: KANJI/VOCAB key bằng canonical_item, không có tag.</li>
 *   <li>{@code wrongCount}/{@code masteryScore}/{@code status}/{@code lastReviewedAt} phục vụ lifecycle
 *       mastery ở Lát 3 (Progress Quiz + replan).</li>
 * </ul>
 */
@Entity
@Table(name = "user_knowledge_weakness")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserKnowledgeWeakness {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_weakness_user"))
    private User user;

    /** Nullable: KANJI/VOCAB không có tag, key bằng canonical_item. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", foreignKey = @ForeignKey(name = "fk_weakness_tag"))
    private Tag tag;

    /** Key item chính xác: KANJI:食 / VOCAB:先生 / GP:<id> / READ / LISTEN. */
    @Column(name = "canonical_item", length = 255)
    private String canonicalItem;

    /** Fallback khi chưa tìm được lesson khớp tag. */
    @Column(name = "skill", length = 20)
    private String skill;

    @Column(name = "jlpt_level", length = 10)
    private String jlptLevel;

    /** TOPIC tag (reading/listening). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_tag_id", foreignKey = @ForeignKey(name = "fk_weakness_topic"))
    private Tag topicTag;

    /** Outcome kỹ năng con (reading/listening). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "outcome_id", foreignKey = @ForeignKey(name = "fk_weakness_outcome"))
    private Outcome outcome;

    @Column(name = "score_percent", precision = 5, scale = 2)
    private BigDecimal scorePercent;

    /** Số câu sai đã gom cho item/tag/skill này (gồm NO_RESPONSE do hết giờ). */
    @Column(name = "wrong_count", nullable = false)
    private int wrongCount;

    /** Điểm mastery 0..100, chỉ ghi ở Lát 3 (Progress Quiz). */
    @Column(name = "mastery_score", precision = 5, scale = 2)
    private BigDecimal masteryScore;

    /** WEAK/NEEDS_REINFORCEMENT. */
    @Column(name = "severity", nullable = false, length = 30)
    private String severity;

    /** OPEN/REVIEWED/MASTERED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "source", length = 255)
    private String source;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @PrePersist
    protected void onCreate() {
        detectedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
