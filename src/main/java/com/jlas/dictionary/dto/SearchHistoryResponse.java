package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Response cho một dòng lịch sử tra cứu. */
@Data
@Builder
public class SearchHistoryResponse {
    private Long id;
    private String queryText;
    private String searchMode;
    private int resultCount;
    private LocalDateTime searchedAt;
    private Long selectedVocabularyId;
}
