package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request tạo/sửa câu ví dụ của một mẫu ngữ pháp. Câu ví dụ là content_item loại GRAMMAR với
 * grammar_point_id trỏ về mẫu; mẫu cha nằm trên URL (POST /grammar-points/{id}/examples).
 * <p>
 * Tên trường theo cách gọi của giao diện (textJa/textVi) chứ không theo tên cột
 * (content/translated_text), thống nhất với {@link ExampleSentenceResponse} đang trả về.
 */
@Data
public class ExampleSentenceUpsertRequest {

    /** content_item.title là NOT NULL; dùng làm nhãn ngắn cho câu, ví dụ "Ví dụ 1". */
    @NotBlank(message = "Nhãn câu ví dụ không được để trống")
    @Size(max = 255, message = "Nhãn câu ví dụ tối đa 255 ký tự")
    private String title;

    @NotBlank(message = "Câu tiếng Nhật không được để trống")
    private String textJa;

    private String textVi;

    /**
     * Phần nội dung chứa câu này. Để trống thì service tự chọn phần loại EXAMPLE đầu tiên của bài,
     * và tạo một phần mới nếu bài chưa có phần EXAMPLE nào — xem ContentAdminService.createExample.
     */
    private Long sectionId;
}
