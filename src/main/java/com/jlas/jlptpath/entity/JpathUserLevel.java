package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] One level inside a path (jpath_user_level): stage, sliders and slider adjustments used (at most 2). */
@Entity
@Table(name = "jpath_user_level")
@IdClass(JpathUserLevelId.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathUserLevel {

    @Id
    @Column(name = "path_id")
    private Long pathId;

    @Id
    @Column(name = "level", length = 2)
    private String level;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16)
    private JpathStage stage = JpathStage.STUDY;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "slider_vocab", nullable = false)
    private Integer sliderVocab;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "slider_kanji", nullable = false)
    private Integer sliderKanji;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "slider_grammar", nullable = false)
    private Integer sliderGrammar;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "adjustments_used", nullable = false)
    private Integer adjustmentsUsed = 0;

    @Column(name = "first_drag_used", nullable = false)
    private boolean firstDragUsed;

    @Column(name = "priority_mode", nullable = false)
    private boolean priorityMode;

    /** Server time; filled on insert when not set. */
    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "skill_practice_at")
    private LocalDateTime skillPracticeAt;

    @Column(name = "passed_at")
    private LocalDateTime passedAt;

    @Column(name = "mock_attempt_id")
    private Long mockAttemptId;

    @PrePersist
    void fillOpenedAt() {
        if (openedAt == null) {
            openedAt = LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }
}
