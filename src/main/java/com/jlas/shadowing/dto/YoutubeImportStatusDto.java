package com.jlas.shadowing.dto;

import com.jlas.shadowing.model.ProcessingStep;
import com.jlas.shadowing.model.VideoStatus;
import lombok.Data;

@Data
public class YoutubeImportStatusDto {
    private Long jobId;
    private Long lessonId;
    private int progress;
    private String status;
    private VideoStatus videoStatus;
    private ProcessingStep processingStep;
    private String errorMessage;
}
