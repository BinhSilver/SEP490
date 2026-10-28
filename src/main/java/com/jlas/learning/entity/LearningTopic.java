package com.jlas.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** [JLAS-W0108] Read-only view of a goal topic. Communication dialogue situations point at their vocabulary topic
 * through {@code parentTopicId} and carry an approximate {@code jlptLevel} (owner 2026-10-19); rows are written by
 * the generated Learning-owned seed only. */
@Entity
@Immutable
@Table(name = "learning_topic")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class LearningTopic {
    @Id private Long id;
    @Column(name = "goal_id", nullable = false) private Long goalId;
    @Column(name = "parent_topic_id") private Long parentTopicId;
    @Column(name = "topic_code", nullable = false, length = 100) private String topicCode;
    @Column(name = "title_vi", nullable = false) private String titleVi;
    @Column(name = "topic_order", nullable = false) private Integer topicOrder;
    @Column(name = "jlpt_level", length = 10) private String jlptLevel;

    /** The vocabulary topic a dialogue situation belongs to, or this topic itself. */
    public Long rootTopicId() {
        return parentTopicId != null ? parentTopicId : id;
    }
}
