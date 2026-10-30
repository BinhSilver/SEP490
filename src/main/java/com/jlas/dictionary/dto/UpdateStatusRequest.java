package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Request đổi trạng thái duyệt của textbook / lesson; chỉ nhận đúng 3 giá trị của CHECK trong DB. */
@Data
public class UpdateStatusRequest {

    /** DRAFT / PUBLISHED / ARCHIVED. */
    @NotBlank(message = "Trạng thái không được để trống")
    @Pattern(regexp = "DRAFT|PUBLISHED|ARCHIVED", message = "Trạng thái chỉ nhận DRAFT, PUBLISHED hoặc ARCHIVED")
    private String status;
}
