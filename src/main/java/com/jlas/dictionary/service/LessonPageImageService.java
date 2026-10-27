package com.jlas.dictionary.service;

import com.jlas.dictionary.dto.LessonPageImagesResponse;

/**
 * [JLAS2-17] Ảnh trang từ vựng / ngữ pháp / kanji (bảng lesson_page_image, migration 037; seed vnjpclub ghi).
 * Chỉ đọc — không có thao tác ghi từ giao diện.
 */
public interface LessonPageImageService {

    /** DB đã có bảng lesson_page_image (migration 037) chưa. */
    boolean isAvailable();

    /**
     * Ảnh trang của bài theo tab + thứ tự. Cùng quyền xem với nội dung bài: CONTENT_CREATOR thấy mọi bài, vai khác chỉ
     * thấy bài PUBLISHED thuộc giáo trình PUBLISHED (bài không xem được → items rỗng). available = false khi chưa có bảng.
     */
    LessonPageImagesResponse listForLesson(Long lessonId);
}
