package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Cụm tách nghĩa của một lời thoại; meaningVi null với dấu câu. */
@Data
@Builder
public class DialogueChunkResponse {
    private Long id;
    private Integer orderIndex;
    private String surface;
    private String meaningVi;
}
