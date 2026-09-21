package com.jlas.flashcard.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Bộ flashcard do user/admin tạo; metadata clone nằm ngay trên set.
 * Table: flashcard_set
 */
@Entity
@Table(name = "flashcard_set")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlashcardSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_flashcard_set_owner"))
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id",
            foreignKey = @ForeignKey(name = "fk_flashcard_set_folder"))
    private FlashcardFolder folder;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    /** PUBLIC/PRIVATE. */
    @Column(name = "visibility", nullable = false, length = 20)
    @Builder.Default
    private String visibility = "PRIVATE";

    @Column(name = "is_draft", nullable = false)
    @Builder.Default
    private boolean draft = false;

    /** NOT_SCANNED/SAFE/UNSAFE. */
    @Column(name = "moderation_status", nullable = false, length = 20)
    @Builder.Default
    private String moderationStatus = "NOT_SCANNED";

    @Column(name = "moderation_checked_at")
    private LocalDateTime moderationCheckedAt;

    @Column(name = "moderation_summary", length = 500)
    private String moderationSummary;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** Set gốc nếu đây là bản clone. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_set_id",
            foreignKey = @ForeignKey(name = "fk_flashcard_set_source"))
    private FlashcardSet sourceSet;

    @Column(name = "cloned_at")
    private LocalDateTime clonedAt;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

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
