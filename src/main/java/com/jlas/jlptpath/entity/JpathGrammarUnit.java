package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] Grammar unit of a level (jpath_grammar_unit). */
@Entity
@Table(name = "jpath_grammar_unit")
@IdClass(JpathGrammarUnitId.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathGrammarUnit {

    @Id
    @Column(name = "level", length = 2)
    private String level;

    @Id
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "unit_no")
    private Integer unitNo;

    @Column(name = "title_vi", length = 120)
    private String titleVi;
}
