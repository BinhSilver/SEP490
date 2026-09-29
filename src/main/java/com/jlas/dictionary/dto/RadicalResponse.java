package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Response chứa thông tin bộ thủ. */
@Data
@Builder
public class RadicalResponse {
    private Integer radicalNumber;
    private String character;
    private String nameJa;
    private String nameVi;
    private String meaningVi;
    private Integer strokeCount;
}
