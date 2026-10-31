package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ContributionRequest {
    @NotBlank
    @Size(max = 2000)
    private String content;
}
