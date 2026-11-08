package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathRating;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] One study event (jpath_study_event). clientEventId is unique per user so an offline queue replayed
 * twice records each event once. studyDay is the learner's date.
 */
@Entity
@Table(name = "jpath_study_event", uniqueConstraints = @UniqueConstraint(
        name = "uk_jpath_event_client", columnNames = {"user_id", "client_event_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathStudyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "client_event_id", nullable = false, length = 40)
    private String clientEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 8)
    private JpathItemType itemType;

    @Column(name = "item_code", nullable = false, length = 24)
    private String itemCode;

    @Column(name = "action", nullable = false, length = 12)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "rating", length = 10)
    private JpathRating rating;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "quality")
    private Integer quality;

    @Column(name = "correct")
    private Boolean correct;

    @Column(name = "elapsed_ms")
    private Integer elapsedMs;

    @Column(name = "study_day", nullable = false)
    private LocalDate studyDay;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
