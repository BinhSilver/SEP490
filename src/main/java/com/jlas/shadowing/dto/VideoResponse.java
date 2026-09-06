package com.jlas.shadowing.dto;

import lombok.Builder;
import lombok.Data;

/** Response thông tin video shadowing. */
@Data
@Builder
public class VideoResponse {
    private Long id;
    private String title;
    private String sourceUrl;
    private String difficulty;
    private int durationSec;
}
