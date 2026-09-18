package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Một file audio của từ vựng / lời thoại theo một giọng đọc. */
@Data
@Builder
public class ContentAudioResponse {
    private Long id;
    private String voiceCode;
    private String voiceName;
    private String voiceGender;
    private Boolean voiceDefault;
    private String audioUrl;
    private String mergedUrl;
    private Integer startMs;
    private Integer endMs;
}
