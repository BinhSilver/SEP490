package com.jlas.kaiwa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "scenario_role")
public class AiRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_kind", nullable = false, length = 20)
    private String roleKind = "AI";

    @Column(length = 80)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String personality;

    @Column(name = "speech_style", columnDefinition = "TEXT")
    private String speechStyle;

    @Transient
    private String defaultPrompt;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (code == null || code.isBlank()) {
            code = name != null ? name.toLowerCase().replaceAll("[^a-z0-9_]+", "_") : "ai_role_" + System.currentTimeMillis();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public String getSpeechStyle() { return speechStyle; }
    public void setSpeechStyle(String speechStyle) { this.speechStyle = speechStyle; }
    public String getDefaultPrompt() { return defaultPrompt; }
    public void setDefaultPrompt(String defaultPrompt) { this.defaultPrompt = defaultPrompt; }
}
