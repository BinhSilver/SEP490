package com.jlas.shadowing.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SeriesSummaryDto {
    private Long id;
    private String title;
    private String description;
    private String estimatedLevel;
    private String levelSource;
    private String visibility;
    private Long ownerId;
    private LocalDateTime createdAt;
    private int videoCount;
    private int viewCount;
    private Double avgRating;
    private int ratingCount;
    private boolean ownedByMe;
}
