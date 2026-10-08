package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Một cụm tách nghĩa trong lời thoại. Thứ tự lấy theo thứ tự phần tử trong danh sách gửi lên,
 * service tự đánh order_index 1..n (uk_dialogue_chunk_order).
 * <p>
 * BẤT BIẾN: ghép {@code surface} của tất cả cụm theo đúng thứ tự phải ra ĐÚNG nguyên câu của lời thoại.
 * Giao diện học dựng lại câu hoàn toàn từ các cụm và bỏ qua content_item.content, nên lệch một ký tự là
 * học viên đọc một câu mà audio đọc câu khác — hỏng trong im lặng.
 */
@Data
public class DialogueChunkInput {

    @NotBlank(message = "Cụm không được để trống")
    @Size(max = 255, message = "Mỗi cụm tối đa 255 ký tự")
    private String surface;

    /** Để trống với cụm là dấu câu — giao diện có nhánh riêng cho chúng (chunk--punct). */
    @Size(max = 512, message = "Nghĩa của cụm tối đa 512 ký tự")
    private String meaningVi;
}
