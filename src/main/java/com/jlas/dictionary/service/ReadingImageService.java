package com.jlas.dictionary.service;

import com.jlas.dictionary.dto.LessonReadingImagesResponse;
import com.jlas.dictionary.dto.ReadingImageResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * [JLAS2-17] Quản lý ảnh đoạn đọc trên trang học: thêm / thay / ẩn ảnh gốc / gỡ. Lưu ở bảng reading_image_override
 * (khoá theo mã ổn định của bài đọc) nên chạy lại seed không mất; ảnh seed trong content.audio.path không bao giờ bị
 * ghi hay xoá.
 */
public interface ReadingImageService {

    /** DB đã có bảng reading_image_override (migration 022) chưa. */
    boolean isAvailable();

    /** Ảnh đã chỉnh của các bài đọc thuộc bài học; available = false (items rỗng) khi chưa có bảng. */
    LessonReadingImagesResponse listForLesson(Long lessonId);

    /** Tải lên / thay ảnh (JPG/PNG/WEBP ≤ 5MB, kiểm magic bytes); ảnh tải lên trước đó bị xoá sau khi lưu xong. */
    ReadingImageResponse upload(String stableCode, MultipartFile file, Long userId);

    /** Ẩn ảnh gốc của seed mà không thay ảnh khác (ảnh tải lên trước đó, nếu có, bị xoá). */
    ReadingImageResponse hideSeedImage(String stableCode, Long userId);

    /** Gỡ mọi chỉnh sửa: xoá dòng override và file tải lên, bài đọc quay về ảnh seed. */
    void remove(String stableCode, Long userId);
}
