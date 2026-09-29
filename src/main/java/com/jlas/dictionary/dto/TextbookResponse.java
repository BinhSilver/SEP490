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
    private String status;
    private Integer version;
    private Long unitCount;
}
