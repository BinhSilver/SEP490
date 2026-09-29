package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Response tổng hợp cho một lần Dictionary Search / Global Search. */
@Data
@Builder
public class SearchResultResponse {
    private String query;
    private String searchMode;
    private List<KanjiResponse> kanjiResults;
    private PageResponse<VocabularyResponse> vocabularyResults;
    private int total;
}
