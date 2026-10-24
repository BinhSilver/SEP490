package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * [JLAS2-17] Ảnh đoạn đọc đã chỉnh của một bài học. available = false khi DB chưa chạy migration 022: trang học
 * dùng ảnh seed như cũ và ẩn nút quản lý ảnh.
 */
@Data
@Builder
public class LessonReadingImagesResponse {
    private boolean available;
    private List<ReadingImageResponse> items;
}
