package com.jlas.kaiwa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Mẫu system prompt có version.
 * Table: prompt_template
 */
@Entity
@Table(name = "prompt_template")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 80, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String systemPrompt;

    @Column(name = "version", nullable = false, length = 20)
    @Builder.Default
    private String version = "1.0";

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
