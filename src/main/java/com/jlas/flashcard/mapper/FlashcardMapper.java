package com.jlas.flashcard.mapper;

import com.jlas.flashcard.dto.FlashcardDtos.CardDto;
import com.jlas.flashcard.entity.FlashcardCard;
import com.jlas.flashcard.entity.FlashcardCardProgress;
import java.util.Optional;

/** Mapper cho Flashcard module. */
public final class FlashcardMapper {
    private FlashcardMapper() { }

    public static CardDto card(FlashcardCard card, FlashcardCardProgress state) {
        return CardDto.builder().id(card.getId()).front(card.getFront()).back(card.getBack()).kanji(card.getFront())
                .hiragana(card.getReading()).reading(card.getReading()).meaning(card.getMeaning()).hanViet(card.getHanViet())
                .image(card.getImageUrl()).jlptLevel(card.getJlptLevel()).wordType(card.getWordType())
                .exampleJa(card.getExampleJa()).exampleVi(card.getExampleVi())
                .position(Optional.ofNullable(card.getPosition()).orElse(0))
                .starred(state != null && Boolean.TRUE.equals(state.getIsStarred())).memoryStatus(memoryStatus(state)).build();
    }

    /** MASTERED → REMEMBERED, LEARNING → NOT_REMEMBERED, còn lại NEW (tên trạng thái UI cũ). */
    public static String memoryStatus(FlashcardCardProgress state) {
        if (state == null) return "NEW";
        return "MASTERED".equals(state.getStatus()) ? "REMEMBERED" : "LEARNING".equals(state.getStatus()) ? "NOT_REMEMBERED" : "NEW";
    }
}
