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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
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
        List<VocabularyEntry> membership = distinctMembership(path.getId());
        if (membership.isEmpty()) return false;
        // [JLAS-W0108] P2d — membership thay đổi (replan thêm mục yếu mới) thì bộ chưa ôn lại
        // mục mới: activity CHƯA hoàn thành kể cả khi bộ cũ đã nhớ hết, đến khi regenerate.
        if (!fingerprint(membership).equals(path.getWarmUpFlashcardVersion())) return false;
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

        List<VocabularyEntry> entries = distinctMembership(path.getId());
        if (entries.isEmpty()) {
            return null;
        }

        String fingerprint = fingerprint(entries);
        Long existingSetId = path.getWarmUpFlashcardSetId();
        // [JLAS-W0108] P2d — membership không đổi thì giữ nguyên bộ (bảo toàn tiến độ đã nhớ).
        if (existingSetId != null && fingerprint.equals(path.getWarmUpFlashcardVersion())) {
            return existingSetId;
        }

        SetRequest request = buildSetRequest(entries, existingSetId, userId);
        Long setId;
        if (existingSetId != null) {
            // Membership thay đổi: update bộ hiện có — thẻ cũ giữ id (bảo toàn trạng thái đã nhớ),
            // mục mới vào với id null (thẻ mới → chưa nhớ).
            flashcards.update(existingSetId, request, userId);
            setId = existingSetId;
        } else {
            setId = flashcards.create(request, userId).getId();
        }
        path.setWarmUpFlashcardSetId(setId);
        path.setWarmUpFlashcardVersion(fingerprint);
        paths.save(path);
        return setId;
    }

    /**
     * [JLAS-W0108] P2d — membership bộ flashcard: các từ/Kanji yếu (WEAK_* + REMEDIATION_*) đã khử trùng
     * theo identity (expression+reading), sắp theo id để sinh thẻ ổn định giữa các lần.
     */
    private List<VocabularyEntry> distinctMembership(Long pathId) {
        var distinct = new LinkedHashMap<String, VocabularyEntry>();
        vocabulary
                .findAllById(items.findVocabularyEntryIdsIn(pathId, WARM_UP_WEAK_REASON_CODES)).stream()
                .filter(WarmUpFlashcardService::hasCardContent)
                .forEach(entry -> distinct.putIfAbsent(identity(entry), entry));
        return distinct.values().stream()
                .sorted(Comparator.comparing(VocabularyEntry::getId))
                .toList();
    }

    /** Fingerprint ổn định của membership (các identity đã sắp thứ tự) để phát hiện thay đổi. */
    private static String fingerprint(List<VocabularyEntry> entries) {
        return entries.stream().map(WarmUpFlashcardService::identity).sorted()
                .collect(Collectors.joining("\n"));
    }

    private static String identity(VocabularyEntry entry) {
        return identity(entry.getExpression(), entry.getReading());
    }

    private static String identity(String expression, String reading) {
        return (expression == null ? "" : expression.strip()) + "\u0000"
                + (reading == null ? "" : reading.strip());
    }

    private SetRequest buildSetRequest(List<VocabularyEntry> entries, Long existingSetId, Long userId) {
        // [JLAS-W0108] P2d — map thẻ cũ theo (front, reading) để giữ id (và tiến độ đã nhớ) khi update;
        // mục mới không khớp → id null → thẻ mới (chưa nhớ).
        Map<String, Long> existingIdByKey = new HashMap<>();
        if (existingSetId != null) {
            var existing = flashcards.set(existingSetId, userId);
            if (existing != null && existing.getCards() != null) {
                for (var card : existing.getCards()) {
                    existingIdByKey.putIfAbsent(identity(card.getFront(), card.getReading()), card.getId());
                }
            }
        }
        List<CardInput> cards = IntStream.range(0, entries.size()).mapToObj(i -> {
            var entry = entries.get(i);
            CardInput card = toCard(entry, i + 1);
            card.setId(existingIdByKey.get(identity(entry)));
            return card;
        }).toList();

        SetRequest request = new SetRequest();
        request.setTitle("Kanji & Từ vựng sai Smart Test");
        request.setDescription("Các Kanji và từ vựng bạn làm sai trong Smart Test — ôn lại trước khi vào giai đoạn theo mục tiêu.");
        request.setVisibility("PRIVATE");
        request.setCards(cards);
        return request;
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
