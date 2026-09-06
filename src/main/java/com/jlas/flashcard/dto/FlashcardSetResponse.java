package com.jlas.flashcard.dto;

import lombok.Builder;
import lombok.Data;

/** Response thông tin bộ flashcard. */
@Data
@Builder
public class FlashcardSetResponse {
    private Long id;
    private String title;
    private String description;
    private int cardCount;
}
