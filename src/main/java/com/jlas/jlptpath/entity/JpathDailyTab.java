package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathTab;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] One tab of a daily plan (jpath_daily_tab): new-item target and review cap with their progress. */
@Entity
@Table(name = "jpath_daily_tab")
@IdClass(JpathDailyTabId.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathDailyTab {

    @Id
    @Column(name = "plan_id")
    private Long planId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "tab", length = 8)
    private JpathTab tab;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "new_target", nullable = false)
    private Integer newTarget;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "new_done", nullable = false)
    private Integer newDone = 0;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "extra_done", nullable = false)
    private Integer extraDone = 0;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "review_cap", nullable = false)
    private Integer reviewCap;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "review_done", nullable = false)
    private Integer reviewDone = 0;

    @Column(name = "check_passed_at")
    private LocalDateTime checkPassedAt;

    @Column(name = "done_at")
    private LocalDateTime doneAt;

    @Column(name = "too_fast", nullable = false)
    private boolean tooFast;
}
