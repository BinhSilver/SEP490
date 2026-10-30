package com.jlas.kaiwa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Nhận xét một lượt hội thoại (JLAS-W0199). Table: conversation_turn_feedback */
@Entity
@Table(name = "conversation_turn_feedback")
public class TurnFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ConversationSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private ConversationMessage message;

    @Column(name = "original_text", columnDefinition = "TEXT")
    private String originalText;

    @Column(name = "corrected_text", columnDefinition = "TEXT")
    private String correctedText;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "mistake_type", length = 80)
    private String mistakeType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ConversationSession getSession() { return session; }
    public void setSession(ConversationSession session) { this.session = session; }
    public ConversationMessage getMessage() { return message; }
    public void setMessage(ConversationMessage message) { this.message = message; }
    public String getOriginalText() { return originalText; }
    public void setOriginalText(String originalText) { this.originalText = originalText; }
    public String getCorrectedText() { return correctedText; }
    public void setCorrectedText(String correctedText) { this.correctedText = correctedText; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getMistakeType() { return mistakeType; }
    public void setMistakeType(String mistakeType) { this.mistakeType = mistakeType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
