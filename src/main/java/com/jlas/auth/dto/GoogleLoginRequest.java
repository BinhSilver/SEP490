package com.jlas.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
public class GoogleLoginRequest {
    @NotBlank
    @ToString.Exclude
    private String idToken;
}
