package com.jlas.kaiwa.model;

import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "evaluation_rule")
@SQLRestriction("rule_type = 'FEEDBACK'")
public class FeedbackRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_type", nullable = false, length = 20)
    private String ruleType = "FEEDBACK";

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private int priority = 0;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @PrePersist
    void onCreate() {
        if (code == null || code.isBlank()) code = name.toLowerCase().replaceAll("[^a-z0-9_]+", "_");
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
