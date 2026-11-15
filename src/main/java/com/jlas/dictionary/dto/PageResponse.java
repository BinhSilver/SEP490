package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Trang kết quả vocabulary. Vì kết quả được gộp từ nhiều tier ranking (không phải một câu SQL
 * duy nhất), {@code totalElements} phản ánh số lượng đã thu thập trong lần truy vấn này
 * (bị chặn bởi cap nội bộ), KHÔNG phải COUNT(*) toàn bảng — tránh count query nặng.
 */
@Data
@Builder
public class PageResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean last;
}
