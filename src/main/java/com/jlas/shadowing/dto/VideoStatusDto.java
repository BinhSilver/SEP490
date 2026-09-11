package com.jlas.shadowing.dto;

import com.jlas.shadowing.model.ProcessingStep;
import com.jlas.shadowing.model.VideoStatus;
import lombok.Data;

@Data
public class VideoStatusDto {
    private Long id;
    private String title;
    private VideoStatus status;
    private ProcessingStep processingStep;
    private Integer progressPercent;
    private String errorMessage;
}
