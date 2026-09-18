package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/** Giáo trình kèm toàn bộ unit và bài học (đã lọc theo quyền xem). */
@Data
@Builder
public class TextbookUnitsResponse {
    private TextbookResponse textbook;
    private List<UnitResponse> units;
}
