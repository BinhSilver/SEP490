package com.jlas.assessment.entity;

import com.jlas.dictionary.entity.Tag;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Bridge question-tag cho tag phụ.
 * Table: question_tag
 */
@Entity
@Table(name = "question_tag")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionTag {

    @EmbeddedId
    private QuestionTagId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("questionId")
    @JoinColumn(name = "question_id",
            foreignKey = @ForeignKey(name = "fk_question_tag_question"))
    private AssessmentQuestion question;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("tagId")
    @JoinColumn(name = "tag_id",
            foreignKey = @ForeignKey(name = "fk_question_tag_tag"))
    private Tag tag;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class QuestionTagId implements Serializable {
        @Column(name = "question_id")
        private Long questionId;

        @Column(name = "tag_id")
        private Long tagId;
    }
}
