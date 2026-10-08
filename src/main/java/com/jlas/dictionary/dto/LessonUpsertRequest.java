package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request tạo/sửa bài học. Unit cha nằm trên URL (POST /units/{unitId}/lessons) chứ không trong body.
 * Trạng thái đi qua PATCH /lessons/{id}/status, order_index đi qua endpoint sắp xếp riêng.
 * <p>
 * Các giá trị hợp lệ dưới đây lấy đúng theo CHECK constraint trong jlas_schema.sql
 * (chk_lesson_type, chk_lesson_skill, chk_lesson_situation_tag, chk_lesson_minutes):
 * chặn từ tầng DTO để lỗi trả về là 400 có thông báo tiếng Việt, thay vì 500 do DB từ chối.
 */
@Data
public class LessonUpsertRequest {

    /**
     * Điểm kiến thức chính của bài (lesson.knowledge_tag_id NOT NULL, FK sang tag).
     * Mỗi bài thường có tag riêng nên màn hình quản lý cho tạo tag KNOWLEDGE mới ngay tại form.
     */
    @NotNull(message = "Phải chọn điểm kiến thức cho bài học")
    private Long knowledgeTagId;

    @NotBlank(message = "Tên bài học không được để trống")
    @Size(max = 255, message = "Tên bài học tối đa 255 ký tự")
    private String title;

    private String description;

    @NotBlank(message = "Loại bài không được để trống")
    @Pattern(regexp = "CURRICULUM|AMON|REVIEW", message = "Loại bài chỉ nhận CURRICULUM, AMON hoặc REVIEW")
    private String lessonType;

    @NotBlank(message = "Kỹ năng không được để trống")
    @Pattern(regexp = "VOCABULARY|GRAMMAR|READING|LISTENING|SPEAKING|ONYOMI|KUNYOMI",
            message = "Kỹ năng chỉ nhận VOCABULARY, GRAMMAR, READING, LISTENING, SPEAKING, ONYOMI hoặc KUNYOMI")
    private String skill;

    /** Để trống nếu bài không gắn tình huống nào. */
    @Pattern(regexp = "^(TRAVEL|WORK|FOUNDATION)?$",
            message = "Tình huống chỉ nhận TRAVEL, WORK hoặc FOUNDATION")
    private String situationTag;

    @Positive(message = "Thời lượng phải lớn hơn 0")
    private Integer estimatedMinutes;

    /** Bài ôn tập; để trống hiểu là không phải bài ôn tập. */
    private Boolean isReview;
}
