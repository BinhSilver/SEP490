package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request tạo/sửa mẫu ngữ pháp. Bài cha nằm trên URL (POST /lessons/{lessonId}/grammar-points)
 * chứ không trong body, nên PUT không chuyển được mẫu sang bài khác.
 * <p>
 * order_index không có ở đây: tạo mới thì service đặt vào cuối, đổi thứ tự đi qua endpoint sắp xếp
 * riêng vì cột này có UNIQUE uk_grammar_order (lesson_id, order_index).
 */
@Data
public class GrammarPointUpsertRequest {

    @NotBlank(message = "Mẫu ngữ pháp không được để trống")
    @Size(max = 500, message = "Mẫu ngữ pháp tối đa 500 ký tự")
    private String pattern;

    /** grammar_point.meaning là NOT NULL trong schema. */
    @NotBlank(message = "Giải nghĩa không được để trống")
    private String meaning;

    /**
     * Câu ví dụ gắn liền với mẫu, hiển thị khi mẫu chưa có câu ví dụ nào trỏ tới (để không lặp).
     * Khác với câu ví dụ dạng content_item — xem {@link ExampleSentenceUpsertRequest}.
     */
    private String example;

    private String note;

    /** Tag KNOWLEDGE chi tiết cho mẫu này; để trống nếu không gắn (grammar_point.tag_id cho phép NULL). */
    private Long tagId;
}
