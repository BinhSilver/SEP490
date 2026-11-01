package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Một bộ phận trong bảng tra Kanji theo bộ thủ. */
@Data
@Builder
public class KanjiComponentResponse {
    private Integer id;
    private String character;
    private Integer strokeCount;
    /** Tên bộ thủ tiếng Việt (ví dụ "Nhân" cho 亻); null nếu bộ phận không phải bộ thủ Khang Hi. */
    private String nameVi;
}
