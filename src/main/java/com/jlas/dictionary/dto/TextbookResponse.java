package com.jlas.dictionary.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/** Giáo trình đã số hoá (danh sách). */
@Data
@Builder
public class TextbookResponse {
    private Long id;
    private String title;
    private String description;
    private String jlptLevel;
    /** Ảnh thumbnail; null thì frontend vẽ thumbnail tự sinh từ thumbnailKanji/themeColor. */
    private String thumbnailUrl;
    private String thumbnailKanji;
    private String themeColor;
    private String status;
    private Integer version;
    private Long unitCount;

    /**
     * Giáo trình học theo chủ đề: không có cấp độ JLPT và có bài gắn tag SITUATION.
     * Trang danh sách dùng cờ này để tách nhóm "Theo chủ đề" khỏi nhóm "Nhập môn".
     */
    private Boolean topicBased;

    /** Lần sửa gần nhất (textbook.updated_at) — trang quản lý sắp xếp "Mới cập nhật". */
    private LocalDateTime updatedAt;

    /**
     * [JLAS2-17] Mã chủ đề (tag SITUATION qua lesson_tag) của các bài trong giáo trình — chỉ điền ở danh sách quản lý
     * (GET /manage/textbooks) cho bộ lọc "Chủ đề"; các API khác để null.
     */
    private List<String> topicCodes;
}
