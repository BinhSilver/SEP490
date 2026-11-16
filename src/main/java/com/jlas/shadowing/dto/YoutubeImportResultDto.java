package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class YoutubeImportResultDto {
    private Long lessonId;
    private Long jobId;
    private String message;
    /** CP paid for this video when the week's free allowance was used up; 0 otherwise. */
    private int creditCharged;
}
