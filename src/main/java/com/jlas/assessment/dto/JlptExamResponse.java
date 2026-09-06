package com.jlas.assessment.dto;

import lombok.Builder;
import lombok.Data;

/** Response chứa thông tin đề thi JLPT. */
@Data
@Builder
public class JlptExamResponse {

    private Long id;
    private String title;
    private String level;
    // TODO: Bổ sung thêm fields
}
