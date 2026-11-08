package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request gắn một từ vựng CÓ SẴN vào bài học. Không tạo từ mới ở đây: vocabulary_entry là bảng dùng
 * chung toàn hệ thống và có uk_vocabulary_normalized, nên việc tạo từ cần hàm chuẩn hoá riêng và được
 * tách thành phần việc khác.
 */
@Data
public class AttachVocabularyRequest {

    /** Id từ trong vocabulary_entry; phải là từ đã tồn tại. */
    @NotNull(message = "Phải chọn một từ vựng")
    private Long vocabularyId;

    /**
     * Phần nội dung chứa từ. Để trống thì service tự chọn phần phù hợp của bài và tạo nếu chưa có —
     * xem ContentAdminService.attachVocabulary.
     */
    private Long sectionId;
}
