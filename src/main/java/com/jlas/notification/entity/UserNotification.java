package com.jlas.notification.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

/** A persistent, in-app notification owned by one account. */
@Entity
@Table(name = "user_notification", uniqueConstraints = @UniqueConstraint(
        name = "uk_user_notification_event", columnNames = {"user_id", "event_key"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 40)
    private String type;
    @Column(nullable = false, length = 160)
    private String title;
    @Column(nullable = false, length = 1000)
    private String message;
    @Column(name = "target_url", length = 500)
    private String targetUrl;
    @Column(name = "event_key", length = 160)
    private String eventKey;
    @Column(name = "read_at")
    private LocalDateTime readAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
