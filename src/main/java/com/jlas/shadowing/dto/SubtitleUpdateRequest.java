package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class SubtitleUpdateRequest {
    private String text;
    private String furigana;
    private String translation;
    private Double startTime;
    private Double endTime;
}
