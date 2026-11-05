package com.jlas.admin.dto;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminBirthDateRequest extends AdminReasonRequest {
    /** Ngày sinh mới; null xoá ngày sinh để người dùng được hỏi lại khi mở video có nhãn độ tuổi. */
    private LocalDate dateOfBirth;
}
