package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request tạo tag dùng cho form bài học (điểm kiến thức). Giá trị tagType lấy đúng theo
 * chk_tag_type trong jlas_schema.sql; code có UNIQUE (uk_tag_code) nên service kiểm tra trùng trước.
 */
@Data
public class TagUpsertRequest {

    @NotBlank(message = "Mã tag không được để trống")
    @Size(max = 100, message = "Mã tag tối đa 100 ký tự")
    private String code;

    @NotBlank(message = "Tên tag không được để trống")
    @Size(max = 255, message = "Tên tag tối đa 255 ký tự")
    private String name;

    @NotBlank(message = "Loại tag không được để trống")
    @Pattern(regexp = "VIDEO|KNOWLEDGE|SITUATION|SKILL",
            message = "Loại tag chỉ nhận VIDEO, KNOWLEDGE, SITUATION hoặc SKILL")
    private String tagType;
}
