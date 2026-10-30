package com.jlas.assessment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record JlptFuriganaRequest(
        @NotEmpty @Size(max = 200)
        List<@NotBlank @Size(max = 12000) String> texts) {
}
