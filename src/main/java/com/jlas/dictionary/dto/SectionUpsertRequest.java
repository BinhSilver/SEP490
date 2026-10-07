package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request tạo/sửa phần nội dung trong một bài học. Bài cha nằm trên URL
 * (POST /lessons/{lessonId}/sections) chứ không trong body, nên PUT không chuyển được phần sang bài khác.
 * <p>
 * order_index không có ở đây: tạo mới thì service đặt vào cuối, đổi thứ tự đi qua endpoint sắp xếp riêng
 * vì cột này có UNIQUE uk_section_order (lesson_id, order_index).
 */
@Data
public class SectionUpsertRequest {

    @NotBlank(message = "Tên phần nội dung không được để trống")
    @Size(max = 255, message = "Tên phần nội dung tối đa 255 ký tự")
    private String title;

    /** Giá trị lấy đúng theo chk_section_type trong jlas_schema.sql. */
    @NotBlank(message = "Loại phần nội dung không được để trống")
    @Pattern(regexp = "EXPLANATION|EXAMPLE|EXERCISE",
            message = "Loại phần nội dung chỉ nhận EXPLANATION, EXAMPLE hoặc EXERCISE")
    private String sectionType;
}
