package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Kết quả tra Kanji theo các bộ phận đã chọn. */
@Data
@Builder
public class ComponentSearchResponse {
    /** Tổng số Kanji chứa đủ mọi bộ phận đã chọn (có thể lớn hơn số phần tử trả về). */
    private long total;
    /** Kanji khớp, xếp theo số nét rồi độ phổ biến; tối đa một giới hạn cố định. */
    private List<Item> kanji;
    /** Bộ phận còn có thể chọn thêm mà vẫn có kết quả; FE làm mờ các bộ phận còn lại. */
    private List<Integer> availableComponentIds;

    @Data
    @Builder
    public static class Item {
        private String character;
        private Integer strokeCount;
        private String hanViet;
        private String jlptLevel;
    }
}
