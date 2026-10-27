package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * [JLAS2-17] Ảnh trang của một bài học. available = false khi DB chưa chạy migration 037: trang học không hiện ảnh trang.
 */
@Data
@Builder
public class LessonPageImagesResponse {
    private boolean available;
    private List<LessonPageImageResponse> items;
}
