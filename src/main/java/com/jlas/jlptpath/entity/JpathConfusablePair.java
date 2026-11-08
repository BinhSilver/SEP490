package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathItemType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * [JLAS-W0108] Pair of easily confused items (jpath_confusable_pair); codeA &lt; codeB, level = the higher level of the
 * two. itemType is VOCAB, KANJI or GRAMMAR.
 */
@Entity
@Table(name = "jpath_confusable_pair")
@IdClass(JpathConfusablePairId.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathConfusablePair {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", length = 8)
    private JpathItemType itemType;

    @Id
    @Column(name = "code_a", length = 24)
    private String codeA;

    @Id
    @Column(name = "code_b", length = 24)
    private String codeB;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    /** Owner 07/11: how the two differ (Vietnamese), mainly for grammar pairs. */
    @Column(name = "diff_vi", length = 600)
    private String diffVi;

    /** [{code, ja, vi}] contrasting examples. */
    @Column(name = "examples_json", columnDefinition = "json")
    private String examplesJson;

    /** {ja, options[], answer, explainVi}: one choose-the-pattern question. */
    @Column(name = "question_json", columnDefinition = "json")
    private String questionJson;
}
