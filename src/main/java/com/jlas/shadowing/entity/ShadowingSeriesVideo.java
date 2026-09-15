package com.jlas.shadowing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Bridge shadowing_series và video.
 * Table: shadowing_series_video
 */
@Entity
@Table(name = "shadowing_series_video")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowingSeriesVideo {

    @EmbeddedId
    private ShadowingSeriesVideoId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("seriesId")
    @JoinColumn(name = "series_id")
    private ShadowingSeries series;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("videoId")
    @JoinColumn(name = "video_id")
    private Video video;

    @Column(name = "position", nullable = false)
    private Integer position;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class ShadowingSeriesVideoId implements Serializable {
        @Column(name = "series_id")
        private Long seriesId;

        @Column(name = "video_id")
        private Long videoId;
    }
}
