package com.jlas.shadowing.dto;

import com.jlas.shadowing.model.VideoStatus;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class VideoSummaryDto {
    private Long id;
    private String title;
    private VideoStatus status;
    private String thumbnailPath;
    private Double duration;
    private Integer progressPercent;
    private List<String> tags;
    private String category;
    private String estimatedLevel;
    private String levelSource;
    private LocalDateTime createdAt;
    private long subtitleCount;
    private boolean favorited;
    private Double avgRating;
    private Integer userRating;
    private int ratingCount;
    private int viewCount;
    private long commentCount;
    private Long ownerId;
    private String visibility;
    private boolean ownedByMe;
}
