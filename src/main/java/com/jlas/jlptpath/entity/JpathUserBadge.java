package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** [JLAS-W0108] A badge earned by a learner (jpath_user_badge). */
@Entity
@Table(name = "jpath_user_badge")
@IdClass(JpathUserBadgeId.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathUserBadge {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "badge_code", length = 30)
    private String badgeCode;

    /** Server time; filled on insert when not set. */
    @Column(name = "earned_at", nullable = false)
    private LocalDateTime earnedAt;

    @PrePersist
    void fillEarnedAt() {
        if (earnedAt == null) {
            earnedAt = LocalDateTime.now();
        }
    }
}
