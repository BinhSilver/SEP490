package com.jlas.dictionary.service;

import com.jlas.dictionary.dto.LessonImportResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Import nội dung một bài học từ file Excel (.xlsx) cho CONTENT_CREATOR.
 * <p>
 * File gồm các sheet TuVung, NguPhap, ViDu, HoiThoai, Kanji (sheet nào không có thì bỏ qua; tên sheet so
 * khớp không phân biệt hoa thường/dấu, nên "Từ vựng" cũng được). Dòng 1 của mỗi sheet là tiêu đề cột.
 * Bố cục cột đúng như file mẫu do {@link #buildTemplate()} sinh ra.
 */
public interface LessonImportService {

    /**
     * Kiểm tra toàn bộ file trước; có dòng lỗi thì không lưu gì và trả về danh sách lỗi
     * ({@code imported = false}). File hợp lệ thì lưu tất cả trong một transaction, tái dùng các thao tác
     * biên soạn của {@link ContentAdminService} (cùng luật kiểm tra như khi soạn tay).
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu bài không tồn tại; BAD_REQUEST nếu file
     *                                               rỗng, quá lớn, không đọc được hoặc không có dữ liệu;
     *                                               CONFLICT nếu giáo trình đã lưu trữ
     */
    LessonImportResult importLesson(Long lessonId, MultipartFile file);

    /** File mẫu .xlsx: các sheet dữ liệu chỉ có dòng tiêu đề, kèm sheet HuongDan giải thích từng cột. */
    byte[] buildTemplate();
}
