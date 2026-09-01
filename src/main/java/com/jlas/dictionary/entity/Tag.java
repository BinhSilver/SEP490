package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Catalog tag dùng chung cho video, lesson và question.
 * Table: tag
 */
@Entity
@Table(name = "tag")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key ổn định, ví dụ N5-PARTICLE. */
    @Column(name = "code", nullable = false, length = 100, unique = true)
    private String code;

    /** Tên hiển thị. */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** VIDEO/KNOWLEDGE/SITUATION/SKILL. */
    @Column(name = "tag_type", nullable = false, length = 20)
    private String tagType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
