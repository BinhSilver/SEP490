package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] Sets a learner has used (jpath_skill_set_use); lastUsedDay is the learner's date (set picking, #39).
 */
@Entity
@Table(name = "jpath_skill_set_use", indexes = @Index(
        name = "idx_jpath_setuse_day", columnList = "user_id, last_used_day"))
@IdClass(JpathSkillSetUseId.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathSkillSetUse {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "set_code", length = 24)
    private String setCode;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "uses", nullable = false)
    private Integer uses = 0;

    @Column(name = "last_used_day", nullable = false)
    private LocalDate lastUsedDay;
}
