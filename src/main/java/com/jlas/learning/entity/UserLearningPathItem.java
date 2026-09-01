package com.jlas.learning.entity;

import com.jlas.dictionary.entity.Lesson;
import jakarta.persistence.*;
import lombok.*;

/**
 * Lesson trong learning path; cho phép cùng lesson xuất hiện lại để review.
 * Table: user_learning_path_item
 */
@Entity
@Table(name = "user_learning_path_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLearningPathItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "learning_path_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_path_item_path"))
    private UserLearningPath learningPath;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_path_item_lesson"))
    private Lesson lesson;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** CURRICULUM hoặc REVIEW. */
    @Column(name = "kind", nullable = false, length = 20)
    private String kind;
}
