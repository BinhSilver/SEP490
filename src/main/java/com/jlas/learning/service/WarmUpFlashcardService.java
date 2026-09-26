package com.jlas.learning.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.flashcard.dto.FlashcardDtos.CardInput;
import com.jlas.flashcard.dto.FlashcardDtos.SetRequest;
import com.jlas.flashcard.service.FlashcardService;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Sinh 1 bộ flashcard warm-up gộp Kanji + Từ vựng đã sai trong Smart Test,
 * get-or-create mỗi path: id bộ được cache vào {@code user_learning_path.warm_up_flashcard_set_id}
 * để không tạo lại mỗi lần bấm. Tái dùng {@link FlashcardService#create} của module flashcard —
 * không sửa code module flashcard.
 */
@Service
@RequiredArgsConstructor
public class WarmUpFlashcardService {
    public static final List<String> WARM_UP_WEAK_REASON_CODES =
            List.of("WEAK_KANJI_ITEM", "WEAK_VOCAB_ITEM");

    private final FlashcardService flashcards;
    private final UserLearningPathRepository paths;
    private final UserLearningPathItemRepository items;
    private final VocabularyEntryRepository vocabulary;

    @Transactional
    public Long ensureSet(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        UserLearningPath path = paths.findByUserIdAndStatus(userId, "ACTIVE")
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No active learning path found."));

        if (path.getWarmUpFlashcardSetId() != null) {
            return path.getWarmUpFlashcardSetId();
        }

        List<VocabularyEntry> entries = vocabulary
                .findAllById(items.findVocabularyEntryIdsIn(path.getId(), WARM_UP_WEAK_REASON_CODES)).stream()
                .filter(WarmUpFlashcardService::hasCardContent)
                .toList();
        if (entries.isEmpty()) {
            return null;
        }

        List<CardInput> cards = IntStream.range(0, entries.size())
                .mapToObj(i -> toCard(entries.get(i), i + 1))
                .toList();

        SetRequest request = new SetRequest();
        request.setTitle("Kanji & Từ vựng sai Smart Test");
        request.setDescription("Các Kanji và từ vựng bạn làm sai trong Smart Test — ôn lại trước khi vào giai đoạn theo mục tiêu.");
        request.setVisibility("PRIVATE");
        request.setCards(cards);

        Long setId = flashcards.create(request, userId).getId();
        path.setWarmUpFlashcardSetId(setId);
        paths.save(path);
        return setId;
    }

    private static boolean hasCardContent(VocabularyEntry entry) {
        return entry != null
                && entry.getExpression() != null && !entry.getExpression().isBlank()
                && entry.getReading() != null && !entry.getReading().isBlank()
                && entry.getMeaning() != null && !entry.getMeaning().isBlank();
    }

    private static CardInput toCard(VocabularyEntry entry, int position) {
        CardInput card = new CardInput();
        card.setFront(entry.getExpression());
        card.setReading(entry.getReading());
        card.setMeaning(entry.getMeaning());
        card.setJlptLevel(entry.getJlptLevel());
        card.setWordType(entry.getWordType());
        card.setExampleJa(entry.getExampleJa());
        card.setExampleVi(entry.getExampleVi());
        card.setPosition(position);
        return card;
    }
}
