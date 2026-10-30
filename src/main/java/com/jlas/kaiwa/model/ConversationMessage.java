package com.jlas.kaiwa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Một lời thoại trong transcript của phiên (JLAS-W0199). Table: conversation_message */
@Entity
@Table(name = "conversation_message")
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ConversationSession session;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Cảnh của kịch bản lúc nói câu này; state đầy đủ dựng lại từ flow_json theo mã. */
    @Column(name = "state_code", length = 80)
    private String stateCode;

    @Transient
    private ConversationState state;

    @Column(name = "meta_json", columnDefinition = "JSON")
    private String metaJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ConversationSession getSession() { return session; }
    public void setSession(ConversationSession session) { this.session = session; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStateCode() { return stateCode; }
    public ConversationState getState() { return state; }
    public void setState(ConversationState state) {
        this.state = state;
        this.stateCode = state == null ? null : state.getCode();
    }
    public String getMetaJson() { return metaJson; }
    public void setMetaJson(String metaJson) { this.metaJson = metaJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
