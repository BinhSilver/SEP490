package com.jlas.shadowing.entity;

import com.jlas.dictionary.entity.Tag;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Bridge video với tag loại VIDEO.
 * Table: video_tag
 */
@Entity
@Table(name = "video_tag")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoTag {

    @EmbeddedId
    private VideoTagId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("videoId")
    @JoinColumn(name = "video_id",
            foreignKey = @ForeignKey(name = "fk_video_tag_video"))
    private Video video;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("tagId")
    @JoinColumn(name = "tag_id",
            foreignKey = @ForeignKey(name = "fk_video_tag_tag"))
    private Tag tag;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class VideoTagId implements Serializable {
        @Column(name = "video_id")
        private Long videoId;

        @Column(name = "tag_id")
        private Long tagId;
    }
}
