package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Bridge lesson-tag cho các tag phụ.
 * Table: lesson_tag
 */
@Entity
@Table(name = "lesson_tag")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonTag {

    @EmbeddedId
    private LessonTagId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("lessonId")
    @JoinColumn(name = "lesson_id",
            foreignKey = @ForeignKey(name = "fk_lesson_tag_lesson"))
    private Lesson lesson;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("tagId")
    @JoinColumn(name = "tag_id",
            foreignKey = @ForeignKey(name = "fk_lesson_tag_tag"))
    private Tag tag;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class LessonTagId implements Serializable {
        @Column(name = "lesson_id")
        private Long lessonId;

        @Column(name = "tag_id")
        private Long tagId;
    }
}
