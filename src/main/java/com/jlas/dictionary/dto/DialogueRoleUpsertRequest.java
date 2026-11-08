package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request tạo/sửa vai hội thoại. Bài cha nằm trên URL (POST /lessons/{lessonId}/dialogue-roles).
 * Mã vai phải duy nhất trong phạm vi bài — uk_dialogue_role_lesson_code.
 */
@Data
public class DialogueRoleUpsertRequest {

    @NotBlank(message = "Mã vai không được để trống")
    @Size(max = 64, message = "Mã vai tối đa 64 ký tự")
    private String code;

    @NotBlank(message = "Tên vai không được để trống")
    @Size(max = 255, message = "Tên vai tối đa 255 ký tự")
    private String nameVi;

    @Size(max = 255, message = "Tên tiếng Nhật tối đa 255 ký tự")
    private String nameJa;

    private String description;

    /** Mã giọng đọc trong content_voice; để trống nếu chưa chọn (fk_dialogue_role_voice là SET NULL). */
    @Size(max = 32, message = "Mã giọng tối đa 32 ký tự")
    private String voiceCode;
}
