package com.jlas.shadowing.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name = "subtitle")
@Data
public class Subtitle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "video_id", nullable = false)
    @JsonIgnore
    private Video video;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    @Column(columnDefinition = "TEXT")
    private String furigana;

    @Column(columnDefinition = "TEXT")
    private String translation;

    @JdbcTypeCode(SqlTypes.DECIMAL)
    @Column(nullable = false)
    private Double startTime;

    @JdbcTypeCode(SqlTypes.DECIMAL)
    @Column(nullable = false)
    private Double endTime;

    @Column(nullable = false)
    private Integer sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiStatus aiStatus = AiStatus.PENDING;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
