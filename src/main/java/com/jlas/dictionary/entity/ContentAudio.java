package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * File audio của một từ vựng hoặc một content item, theo từng giọng đọc.
 * owner_id là khoá đa hình (trỏ vocabulary_entry hoặc content_item tuỳ owner_type)
 * nên KHÔNG map @ManyToOne; ràng buộc do importer và service giữ.
 * Table: content_audio
 */
@Entity
@Table(name = "content_audio")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentAudio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** VOCABULARY_WORD/CONTENT_ITEM. */
    @Column(name = "owner_type", nullable = false, length = 30)
    private String ownerType;

    /** Id của vocabulary_entry hoặc content_item tuỳ ownerType. */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voice_code", nullable = false,
            foreignKey = @ForeignKey(name = "fk_content_audio_voice"))
    private ContentVoice voice;

    /** Đường dẫn tương đối trong kho audio. */
    @Column(name = "audio_url", nullable = false, length = 500)
    private String audioUrl;

    /** File gộp cả bài theo giọng; phục vụ phát liền mạch. */
    @Column(name = "merged_url", length = 500)
    private String mergedUrl;

    @Column(name = "start_ms")
    private Integer startMs;

    @Column(name = "end_ms")
    private Integer endMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
