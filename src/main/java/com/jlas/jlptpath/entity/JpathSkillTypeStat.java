package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] Per-learner statistics of one question type (jpath_skill_type_stat); drives the type of the day (#37).
 */
@Entity
@Table(name = "jpath_skill_type_stat")
@IdClass(JpathSkillTypeStatId.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathSkillTypeStat {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "type_code", length = 24)
    private String typeCode;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "sessions_done", nullable = false)
    private Integer sessionsDone = 0;

    @Builder.Default
    @Column(name = "questions_done", nullable = false)
    private Integer questionsDone = 0;

    @Builder.Default
    @Column(name = "correct_done", nullable = false)
    private Integer correctDone = 0;

    @Column(name = "last_pct", precision = 5, scale = 2)
    private BigDecimal lastPct;

    @Column(name = "last_session_day")
    private LocalDate lastSessionDay;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
