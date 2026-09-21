package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class WatchRequest {
    private String clientId;
    private Double currentTime;
    private Double duration;
    /** Optional — id user đang đăng nhập (cho BR-03 creator reward). */
    private Long userId;
}
