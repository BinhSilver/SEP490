package com.jlas.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class RequestEmailChangeRequest {
    @NotBlank
    @Email
    @Size(max = 255)
    private String newEmail;

    @Size(max = 200)
    @ToString.Exclude
    private String currentPassword;
}
