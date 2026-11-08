package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request tạo/sửa một đoạn hội thoại. Theo quy ước của dữ liệu hiện có, mỗi đoạn hội thoại là một
 * lesson_section loại EXAMPLE với title dạng "Hội thoại: &lt;tên cảnh&gt;"; service tự thêm tiền tố nên
 * người dùng chỉ nhập tên cảnh.
 */
@Data
public class DialogueSceneUpsertRequest {

    @NotBlank(message = "Tên đoạn hội thoại không được để trống")
    @Size(max = 200, message = "Tên đoạn hội thoại tối đa 200 ký tự")
    private String title;
}
