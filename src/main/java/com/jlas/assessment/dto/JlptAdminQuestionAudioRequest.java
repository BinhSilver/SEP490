package com.jlas.assessment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JlptAdminQuestionAudioRequest(@NotBlank @Size(max = 1000) String audioUrl) {}
