package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ContributionVoteRequest {
    @NotNull
    private Integer vote;
}
