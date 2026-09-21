package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class RatingSummaryDto {
    private Double avgRating;
    private int ratingCount;
    private Integer userRating;
}
