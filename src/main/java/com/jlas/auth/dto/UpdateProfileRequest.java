package com.jlas.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {
    @Size(max = 50)
    private String username;

    @Size(max = 150)
    private String fullName;

    @Size(max = 30)
    private String phoneNumber;

    @Size(max = 20)
    private String gender;

    @Size(max = 10)
    private String japaneseLevel;

    @Size(max = 500)
    private String avatar;
}
