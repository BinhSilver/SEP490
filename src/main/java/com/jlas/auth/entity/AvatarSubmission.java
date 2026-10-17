package com.jlas.auth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Table(name = "avatar_submission")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AvatarSubmission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "storage_key", nullable = false, length = 100) private String storageKey;
    @Column(name = "original_name", nullable = false, length = 200) private String originalName;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @PrePersist void create() { createdAt = LocalDateTime.now(); }
}
