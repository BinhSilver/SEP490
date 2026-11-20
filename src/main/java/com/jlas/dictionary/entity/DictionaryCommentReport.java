package com.jlas.dictionary.entity;

import com.jlas.moderation.entity.ContentComment;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "dictionary_comment_report", uniqueConstraints =
        @UniqueConstraint(name = "uk_dictionary_comment_report_once", columnNames = {"comment_id", "reporter_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DictionaryCommentReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comment_id", nullable = false)
    private ContentComment comment;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
