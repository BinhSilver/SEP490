package com.jlas.learning.entity;

import com.jlas.learning.enums.LearningMemberType;
import jakarta.persistence.*;
import lombok.*;

/** [JLAS-W0193] Versioned membership of canonical IDs; dictionary content is not copied. */
@Entity
@Table(name = "learning_activity_member", uniqueConstraints = @UniqueConstraint(
        name = "uk_learning_activity_member_identity", columnNames = {"activity_item_id", "member_type", "canonical_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LearningActivityMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_item_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_activity_member_item"))
    private UserLearningPathItem activity;
    @Enumerated(EnumType.STRING) @Column(name = "member_type", nullable = false, length = 30)
    private LearningMemberType memberType;
    @Column(name = "canonical_id", nullable = false) private Long canonicalId;
    @Column(name = "order_index", nullable = false) private Integer orderIndex;
    @Column(name = "source_revision", nullable = false, length = 64) private String sourceRevision;
}
