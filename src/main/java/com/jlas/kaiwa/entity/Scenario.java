package com.jlas.kaiwa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Tình huống hội thoại; flow_json chứa state/condition/hint/ending runtime.
 * Table: scenario
 */
@Entity
@Table(name = "scenario")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_scenario_category"))
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_role_id",
            foreignKey = @ForeignKey(name = "fk_scenario_ai_role"))
    private ScenarioRole aiRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "learner_role_id",
            foreignKey = @ForeignKey(name = "fk_scenario_learner_role"))
    private ScenarioRole learnerRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prompt_template_id",
            foreignKey = @ForeignKey(name = "fk_scenario_prompt"))
    private PromptTemplate promptTemplate;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "slug", nullable = false, length = 120, unique = true)
    private String slug;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** N5/N4/N3/N2/N1. */
    @Column(name = "jlpt_level", nullable = false, length = 10)
    @Builder.Default
    private String jlptLevel = "N5";

    /** EASY/MEDIUM/HARD. */
    @Column(name = "difficulty", nullable = false, length = 20)
    @Builder.Default
    private String difficulty = "EASY";

    @Column(name = "estimated_minutes", nullable = false)
    @Builder.Default
    private Short estimatedMinutes = 5;

    @Column(name = "background", columnDefinition = "TEXT")
    private String background;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "time_of_day", length = 100)
    private String timeOfDay;

    @Column(name = "culture_note", columnDefinition = "TEXT")
    private String cultureNote;

    /** Cấu hình luồng hội thoại dạng JSON. */
    @Column(name = "flow_json", nullable = false, columnDefinition = "json")
    private String flowJson;

    @Column(name = "learning_content_json", columnDefinition = "json")
    private String learningContentJson;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

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
