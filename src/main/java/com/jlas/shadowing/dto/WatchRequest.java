package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class WatchRequest {
    private String clientId;
    private Double currentTime;
    private Double duration;
    /** Seconds actually played since the last acknowledged ping (seeks excluded). */
    private Double listenedSec;
    /** Unused: the viewer comes from the session. Kept for older clients. */
    private Long userId;
}
