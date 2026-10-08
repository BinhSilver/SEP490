package com.jlas.dictionary.dto;

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
}
