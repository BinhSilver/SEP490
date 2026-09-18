package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Vai diễn trong hội thoại của một bài học. */
@Data
@Builder
public class DialogueRoleResponse {
    private Long id;
    private String code;
    private String nameVi;
    private String nameJa;
    private String description;
    private String voiceCode;
}
