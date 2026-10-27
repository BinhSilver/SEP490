package com.jlas.dictionary.dto;

import java.time.LocalDateTime;

public record DictionaryFavoriteResponse(Long vocabularyId, String expression, String reading,
                                         String note, LocalDateTime createdAt, boolean created) { }
