package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class RatingRequest {
    private String clientId;
    private Integer rating;
}
