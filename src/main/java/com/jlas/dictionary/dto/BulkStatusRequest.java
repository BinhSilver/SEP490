package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * [JLAS2-17] Công bố / ẩn hàng loạt giáo trình hoặc bài học. Một request = một loại + một hành động + tối đa
 * {@value #MAX_IDS} id, chạy trong MỘT transaction (lỗi ở bất kỳ id nào thì không đổi gì). Trình duyệt chia lô ≤ 500.
 */
@Data
public class BulkStatusRequest {

    public static final int MAX_IDS = 500;

    /** TEXTBOOK / LESSON. */
    @NotBlank(message = "Loại nội dung không được để trống")
    @Pattern(regexp = "TEXTBOOK|LESSON", message = "Loại nội dung chỉ nhận TEXTBOOK hoặc LESSON")
    private String kind;

    /** PUBLISH (→ PUBLISHED) / UNPUBLISH (→ DRAFT). Lưu trữ / khôi phục vẫn làm từng mục. */
    @NotBlank(message = "Hành động không được để trống")
    @Pattern(regexp = "PUBLISH|UNPUBLISH", message = "Hành động chỉ nhận PUBLISH hoặc UNPUBLISH")
    private String action;

    @NotEmpty(message = "Danh sách id không được rỗng")
    @Size(max = MAX_IDS, message = "Tối đa " + MAX_IDS + " mục mỗi lần")
    private List<@NotNull(message = "Id không được để trống") Long> ids;
}
