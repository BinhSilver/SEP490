package com.jlas.auth.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Data;
import lombok.ToString;

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

    /** Chỉ đặt được khi tài khoản chưa có ngày sinh; gửi lại đúng ngày đã lưu thì bỏ qua. */
    @ToString.Exclude
    private LocalDate dateOfBirth;

    @Size(max = 10)
    private String japaneseLevel;

    @Size(max = 500)
    private String avatar;
}
