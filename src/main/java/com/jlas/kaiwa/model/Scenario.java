package com.jlas.kaiwa.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

/** Metadata UI của tình huống — không chứa logic AI. */
@Entity
@Table(name = "scenario")
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Compact scenario definition. During migration this is nullable so the
     * existing normalized child tables remain the source of truth for older
     * scenarios. New scenarios can store their flow and learning content here.
     */
    @Column(name = "flow_json", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private String flowJson = "{}";

    @Column(name = "learning_content_json", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private String learningContentJson;

    @Column(name = "jlpt_level", nullable = false, length = 10)
    private String jlptLevel = "N5";

    @Column(nullable = false, length = 20)
    private String difficulty = "EASY";

    @Column(name = "estimated_minutes")
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Integer estimatedMinutes = 5;

    @Column(columnDefinition = "TEXT")
    private String background;

    @Column(length = 200)
    private String location;

    @Column(name = "time_of_day", length = 100)
    private String timeOfDay;

    @Column(name = "culture_note", columnDefinition = "TEXT")
    private String cultureNote;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_role_id")
    private AiRole aiRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "learner_role_id")
    private UserRole userRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prompt_template_id")
    private PromptTemplate promptTemplate;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "scenario_evaluation_rule",
            joinColumns = @JoinColumn(name = "scenario_id"),
            inverseJoinColumns = @JoinColumn(name = "rule_id")
    )
    private java.util.Set<FeedbackRule> feedbackRules = new java.util.HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "scenario_evaluation_rule",
            joinColumns = @JoinColumn(name = "scenario_id"),
            inverseJoinColumns = @JoinColumn(name = "rule_id")
    )
    private java.util.Set<ScoringRule> scoringRules = new java.util.HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false,
            columnDefinition = "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void touch() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFlowJson() { return flowJson; }
    public void setFlowJson(String flowJson) { this.flowJson = flowJson; }
    public String getLearningContentJson() { return learningContentJson; }
    public void setLearningContentJson(String learningContentJson) { this.learningContentJson = learningContentJson; }
    public String getJlptLevel() { return jlptLevel; }
    public void setJlptLevel(String jlptLevel) { this.jlptLevel = jlptLevel; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public Integer getEstimatedMinutes() { return estimatedMinutes; }
    public void setEstimatedMinutes(Integer estimatedMinutes) { this.estimatedMinutes = estimatedMinutes; }
    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getTimeOfDay() { return timeOfDay; }
    public void setTimeOfDay(String timeOfDay) { this.timeOfDay = timeOfDay; }
    public String getCultureNote() { return cultureNote; }
    public void setCultureNote(String cultureNote) { this.cultureNote = cultureNote; }
    public String getThumbnail() { return thumbnail; }
    public void setThumbnail(String thumbnail) { this.thumbnail = thumbnail; }
    public AiRole getAiRole() { return aiRole; }
    public void setAiRole(AiRole aiRole) { this.aiRole = aiRole; }
    public UserRole getUserRole() { return userRole; }
    public void setUserRole(UserRole userRole) { this.userRole = userRole; }
    public PromptTemplate getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(PromptTemplate promptTemplate) { this.promptTemplate = promptTemplate; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public java.util.Set<FeedbackRule> getFeedbackRules() { return feedbackRules; }
    public void setFeedbackRules(java.util.Set<FeedbackRule> feedbackRules) { this.feedbackRules = feedbackRules; }
    public java.util.Set<ScoringRule> getScoringRules() { return scoringRules; }
    public void setScoringRules(java.util.Set<ScoringRule> scoringRules) { this.scoringRules = scoringRules; }
}
