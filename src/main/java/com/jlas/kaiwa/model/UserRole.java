package com.jlas.kaiwa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Vai người học trong kịch bản — không phải tài khoản đăng nhập. */
@Entity
@Table(name = "scenario_role")
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_kind", nullable = false, length = 20)
    private String roleKind = "LEARNER";

    @Column(length = 80)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (code == null || code.isBlank()) {
            code = name != null ? name.toLowerCase().replaceAll("[^a-z0-9_]+", "_") : "user_role_" + System.currentTimeMillis();
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
}
