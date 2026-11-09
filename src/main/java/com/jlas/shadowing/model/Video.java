package com.jlas.shadowing.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(name = "video")
@Data
public class Video {
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".m4a", ".ogg", ".flac", ".aac", ".opus"
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String videoPath;

    /** True when media path is pure audio (player shows lyrics UI, not video frame). */
    @Transient
    @JsonProperty("audioOnly")
    public boolean isAudioOnly() {
        if (videoPath == null || videoPath.isBlank()) return false;
        String lower = videoPath.toLowerCase(Locale.ROOT).replace('\\', '/');
        int slash = lower.lastIndexOf('/');
        String name = slash >= 0 ? lower.substring(slash + 1) : lower;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return AUDIO_EXTENSIONS.contains(name.substring(dot));
    }

    @Column(columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailPath;

    @Column(name = "duration_seconds")
    @JdbcTypeCode(SqlTypes.DECIMAL)
    private Double duration;

    @Enumerated(EnumType.STRING)
    private VideoStatus status = VideoStatus.READY;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ProcessingStep processingStep;

    @JdbcTypeCode(SqlTypes.TINYINT)
    private Integer progressPercent = 0;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private String targetLanguage = "vi";

    private LocalDateTime createdAt;

    @Transient
    private Integer viewCount = 0;

    @Transient
    private Double avgRating;

    @Transient
    private Integer ratingCount = 0;

    /** Owner user id (auth users.user_id). Null = legacy content. */
    @Column(name = "owner_id")
    private Long ownerId;

    /** PUBLIC = visible in library for everyone when READY; PRIVATE = owner only. */
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", length = 20)
    private VideoVisibility visibility = VideoVisibility.PRIVATE;

    /** Tuổi tối thiểu để xem: 13, 16 hoặc 18 (xem VideoAgeRatings). Null = mọi lứa tuổi. */
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "min_age")
    private Integer minAge;

    /**
     * When false, skip AI furigana (use plain JP text) — faster for long videos.
     * Translation still runs if missing from YouTube.
     */
    @Column(name = "generate_furigana", nullable = false)
    private Boolean generateFurigana = false;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** SAFE | REVIEW | BLOCKED | NOT_SCANNED | ERROR */
    @Column(name = "moderation_status", length = 20)
    private String moderationStatus = "NOT_SCANNED";

    @Column(name = "moderation_checked_at")
    private LocalDateTime moderationCheckedAt;

    @Column(name = "moderation_summary", length = 500)
    private String moderationSummary;

    /** Playback of a YouTube video: EMBED = YouTube's own player (no file kept), FILE = downloaded copy
     *  (only for videos whose owner does not allow embedding). Null = row imported before this column existed:
     *  played from its file when it has one (the bundled sample videos), otherwise through the embed. */
    @Column(name = "playback_mode", length = 10)
    private String playbackMode;

    public static final String PLAYBACK_EMBED = "EMBED";
    public static final String PLAYBACK_FILE = "FILE";

    /** True when a YouTube video has no file of its own: a stored file always plays first, the embed is the fallback. */
    @Transient
    @JsonProperty("youtubeEmbed")
    public boolean isYoutubeEmbed() {
        if (com.jlas.shadowing.util.youtube.YoutubeUrlValidator.extractVideoId(sourceUrl).isEmpty()) return false;
        if (videoPath != null && !videoPath.isBlank()) return false;
        return !PLAYBACK_FILE.equals(playbackMode);
    }

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "video_tag",
        joinColumns = @JoinColumn(name = "video_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private List<Tag> tags = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null) status = VideoStatus.READY;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
