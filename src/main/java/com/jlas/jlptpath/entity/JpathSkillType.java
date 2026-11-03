package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathSkill;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] JLPT question type of one level (jpath_skill_type); seed-written, read-only for the app. sortOrder = order in the exam.
 */
@Entity
@Table(name = "jpath_skill_type")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathSkillType {

    @Id
    @Column(name = "code", length = 24)
    private String code;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    @Enumerated(EnumType.STRING)
    @Column(name = "skill", nullable = false, length = 10)
    private JpathSkill skill;

    @Column(name = "name_vi", nullable = false, length = 120)
    private String nameVi;

    @Column(name = "name_ja", nullable = false, length = 60)
    private String nameJa;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "questions_in_exam", nullable = false)
    private Integer questionsInExam;

    @Column(name = "needs_image", nullable = false)
    private boolean needsImage;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "chars_min")
    private Integer charsMin;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "chars_max")
    private Integer charsMax;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "speakers")
    private Integer speakers;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "seconds_typical")
    private Integer secondsTypical;

    @Column(name = "seed_version", length = 16)
    private String seedVersion;
}
