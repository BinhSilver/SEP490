package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * [JLAS2-17] Ảnh đoạn đọc người biên soạn đã chỉnh cho một bài đọc (reading_image_override), khoá theo mã ổn định.
 * imageUrl = ảnh tải lên (/uploads/reading-images/...), null khi chỉ ẩn ảnh gốc. hideSeedImage = không hiện ảnh của seed.
 */
@Data
@Builder
public class ReadingImageResponse {
    private String stableCode;
    private String imageUrl;
    private boolean hideSeedImage;
    private LocalDateTime updatedAt;
}
