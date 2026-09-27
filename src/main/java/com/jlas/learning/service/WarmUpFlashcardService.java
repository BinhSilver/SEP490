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
import java.util.LinkedHashMap;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.flashcard.dto.FlashcardDtos.ProgressRequest;
import com.jlas.flashcard.dto.FlashcardDtos.CardDto;
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
            List.of("WEAK_KANJI_ITEM", "WEAK_VOCAB_ITEM", "REMEDIATION_KANJI_ITEM", "REMEDIATION_VOCAB_ITEM");

    public static boolean isFlashcardItem(UserLearningPathItem item) {
        return "WARM_UP".equals(item.getPhase()) && item.getVocabularyEntry() != null
                && WARM_UP_WEAK_REASON_CODES.contains(item.getReasonCode());
    }

    /** Activity completion only: this does not grant mastery or complete a source lesson. */
    @Transactional(readOnly = true)
    public boolean isCompleted(UserLearningPath path) {
        if (path.getWarmUpFlashcardSetId() == null) return false;
        var summary = flashcards.summary(path.getWarmUpFlashcardSetId(), path.getUser().getId());
        return summary != null && summary.getTotal() > 0 && summary.getRemembered() == summary.getTotal();
    }

    @Transactional
    public CardDto remember(Long userId, Long setId, Long cardId, String memoryStatus) {
        var path = paths.findActiveForFlashcardUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!setId.equals(path.getWarmUpFlashcardSetId())) {
            throw new ApiException(ErrorCode.CONFLICT, "Bộ thẻ không thuộc lộ trình hiện tại. Hãy tải lại lộ trình.");
        }
        var set = flashcards.set(setId, userId);
        if (set.getCards().stream().noneMatch(card -> cardId.equals(card.getId()))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thẻ không thuộc bộ ôn tập.");
        }
        if (!"REMEMBERED".equals(memoryStatus) && !"NOT_REMEMBERED".equals(memoryStatus)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        return flashcards.updateProgress(cardId, new ProgressRequest(null, memoryStatus), userId);
    }

    private final FlashcardService flashcards;
    private final UserLearningPathRepository paths;
    private final UserLearningPathItemRepository items;
    private final VocabularyEntryRepository vocabulary;

    @Transactional
    public Long ensureSet(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        UserLearningPath path = paths.findActiveForFlashcardUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No active learning path found."));

        if (path.getWarmUpFlashcardSetId() != null) {
            return path.getWarmUpFlashcardSetId();
        }

        var distinct = new LinkedHashMap<String, VocabularyEntry>();
        vocabulary
                .findAllById(items.findVocabularyEntryIdsIn(path.getId(), WARM_UP_WEAK_REASON_CODES)).stream()
                .filter(WarmUpFlashcardService::hasCardContent)
                .forEach(entry -> distinct.putIfAbsent(entry.getExpression().strip() + "\u0000"
                        + entry.getReading().strip(), entry));
        List<VocabularyEntry> entries = List.copyOf(distinct.values());
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
