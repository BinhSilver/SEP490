package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class SubtitleCreateRequest {
    private String text;
    private String furigana;
    private String translation;
    private Double startTime;
    private Double endTime;
    private Integer sortOrder;
    /** True when a learner explicitly adds a blank subtitle in the editor. */
    private Boolean manual;
}
