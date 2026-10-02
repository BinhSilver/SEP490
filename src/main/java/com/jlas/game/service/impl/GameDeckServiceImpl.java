package com.jlas.game.service.impl;

import com.jlas.game.enums.GameType;
import com.jlas.game.util.GameRules;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.exception.ApiException;
import com.jlas.flashcard.entity.FlashcardCard;
import com.jlas.flashcard.entity.FlashcardSet;
import com.jlas.flashcard.repository.FlashcardCardRepository;
import com.jlas.flashcard.service.FlashcardStudyAccess;
import com.jlas.game.dto.GameDtos.DeckCardView;
import com.jlas.game.dto.GameDtos.DeckDetailView;
import com.jlas.game.dto.GameDtos.DeckPage;
import com.jlas.game.dto.GameDtos.DeckPairView;
import com.jlas.game.dto.GameDtos.DeckSampleCard;
import com.jlas.game.dto.GameDtos.DeckSummaryView;
import com.jlas.game.enums.DeckField;
import com.jlas.game.repository.GameDeckJdbcRepository;
import com.jlas.game.repository.GameDeckJdbcRepository.CoverageRow;
import com.jlas.game.repository.GameDeckJdbcRepository.SetRow;
import com.jlas.game.runtime.GameWord;
import com.jlas.game.service.GameDeckService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Cài đặt {@link GameDeckService}. Quyền đọc bộ thẻ dùng chung chính sách của module flashcard. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameDeckServiceImpl implements GameDeckService {

    private static final int PAGE_SIZE = 12;
    private static final int SAMPLE_CARDS = 4;

    private final GameDeckJdbcRepository deckQueries;
    private final FlashcardCardRepository cards;
    private final FlashcardStudyAccess studyAccess;

    @Override
    public DeckPage decks(long userId, String tab, String query, int page, GameType game) {
        String normalizedTab = tab == null ? "MINE" : tab.trim().toUpperCase(Locale.ROOT);
        String trimmed = query == null ? "" : query.trim();
        String like = trimmed.isEmpty() ? null : "%" + trimmed.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        int safePage = Math.max(0, page);
        int offset = safePage * PAGE_SIZE;
        // Lật thẻ: bàn nhỏ nhất cần bằng này cặp; hai game kia có ngưỡng cố định.
        Integer minPair = game == null ? null
                : game == GameType.MEMORY_MATCH ? GameRules.MEMORY_BOARD_SIZES.get(0) / 2
                : GameRules.minWords(game, null);
        // Lấy dư một dòng để biết còn trang sau.
        List<SetRow> rows = switch (normalizedTab) {
            case "RECENT" -> deckQueries.recent(userId, like, minPair, PAGE_SIZE + 1, offset);
            case "ALL" -> deckQueries.all(userId, like, minPair, PAGE_SIZE + 1, offset);
            default -> deckQueries.mine(userId, like, minPair, PAGE_SIZE + 1, offset);
        };
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<SetRow> visible = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        Map<Long, CoverageRow> coverage = deckQueries.coverage(visible.stream().map(SetRow::id).toList());
        List<DeckSummaryView> items = visible.stream()
                .map(row -> summary(row, coverage.get(row.id()), userId))
                .toList();
        return new DeckPage(normalizedTab, trimmed, items, safePage, hasMore);
    }

    @Override
    public DeckDetailView deck(long userId, long setId) {
        FlashcardSet set = studyAccess.readable(setId, userId);
        List<FlashcardCard> setCards = cards.findBySetIdOrderByPositionAscIdAsc(setId);

        Map<DeckField, Integer> coverage = new EnumMap<>(DeckField.class);
        for (DeckField field : DeckField.values()) {
            coverage.put(field, (int) setCards.stream().filter(card -> field.read(card) != null).count());
        }
        List<DeckPairView> pairs = new ArrayList<>();
        for (DeckField question : DeckField.values()) {
            for (DeckField answer : DeckField.values()) {
                if (question == answer) {
                    continue;
                }
                List<GameWord> words = toWords(setCards, question, answer);
                long distinctAnswers = words.stream().map(GameWord::answer).distinct().count();
                pairs.add(new DeckPairView(question, answer, words.size(), (int) distinctAnswers));
            }
        }
        List<DeckSampleCard> samples = setCards.stream().limit(SAMPLE_CARDS)
                .map(card -> new DeckSampleCard(DeckField.KANJI.read(card), DeckField.HIRAGANA.read(card),
                        DeckField.MEANING.read(card), DeckField.HAN_VIET.read(card)))
                .toList();

        String ownerName = set.getOwner().getFullName() != null && !set.getOwner().getFullName().isBlank()
                ? set.getOwner().getFullName() : set.getOwner().getUsername();
        DeckSummaryView summary = new DeckSummaryView(set.getId(), set.getTitle(), set.getDescription(),
                set.getThumbnailUrl(), set.getVisibility(), set.isDraft(),
                set.getOwner().getId().equals(userId), ownerName, setCards.size(), coverage, null,
                set.getUpdatedAt());
        return new DeckDetailView(summary, pairs, samples);
    }

    @Override
    public List<DeckCardView> cards(long userId, long setId) {
        studyAccess.studyable(setId, userId);
        return cards.findBySetIdOrderByPositionAscIdAsc(setId).stream()
                .map(card -> new DeckCardView(card.getId(), DeckField.KANJI.read(card), DeckField.HIRAGANA.read(card),
                        DeckField.MEANING.read(card), DeckField.HAN_VIET.read(card)))
                .toList();
    }

    @Override
    public PickedDeck pickCards(long userId, long setId, DeckField question, DeckField answer, List<Long> cardIds,
                                int minWords, String gameName) {
        GameRules.validateFields(question, answer);
        List<DeckCardView> deckCards = cards(userId, setId);
        Set<Long> wanted = cardIds == null
                ? deckCards.stream().map(DeckCardView::id).collect(Collectors.toSet())
                : new HashSet<>(cardIds);
        List<DeckCardView> selected = deckCards.stream().filter(card -> wanted.contains(card.id())).toList();
        if (selected.size() != wanted.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Có thẻ không thuộc bộ này");
        }
        long missing = selected.stream()
                .filter(card -> card.value(question) == null || card.value(answer) == null).count();
        if (missing > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Có " + missing + " thẻ chưa có đủ " + question.label()
                    + " và " + answer.label() + ". Bỏ chọn các thẻ đó hoặc đổi nội dung hỏi / đáp.");
        }
        List<GameWord> words = loadWords(userId, setId, question, answer).words().stream()
                .filter(word -> wanted.contains(word.cardId()))
                .toList();
        if (words.size() < minWords) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cần ít nhất " + minWords + " thẻ khác nhau để chơi "
                    + gameName + " (thẻ trùng câu hỏi hoặc trùng đáp án chỉ tính một).");
        }
        return new PickedDeck(question, answer, words, deckCards);
    }

    @Override
    public DeckWords loadWords(long authorityUserId, long setId, DeckField questionField, DeckField answerField) {
        FlashcardSet set = studyAccess.studyable(setId, authorityUserId);
        List<GameWord> words = toWords(cards.findBySetIdOrderByPositionAscIdAsc(setId), questionField, answerField);
        return new DeckWords(set.getId(), set.getTitle(), words);
    }

    /** Thẻ đủ 2 trường; bỏ thẻ trùng câu hỏi hoặc trùng đáp án để không có hai lựa chọn giống hệt nhau. */
    private static List<GameWord> toWords(List<FlashcardCard> setCards, DeckField questionField,
                                          DeckField answerField) {
        Map<String, GameWord> byPrompt = new LinkedHashMap<>();
        Set<String> answers = new HashSet<>();
        for (FlashcardCard card : setCards) {
            String prompt = questionField.read(card);
            String answer = answerField.read(card);
            if (prompt == null || answer == null) {
                continue;
            }
            String promptKey = prompt.toLowerCase(Locale.ROOT);
            String answerKey = answer.toLowerCase(Locale.ROOT);
            if (byPrompt.containsKey(promptKey) || answers.contains(answerKey)) {
                continue;
            }
            answers.add(answerKey);
            byPrompt.put(promptKey, new GameWord(card.getId(), prompt, answer, DeckField.KANJI.read(card),
                    DeckField.HIRAGANA.read(card), DeckField.MEANING.read(card), DeckField.HAN_VIET.read(card)));
        }
        return new ArrayList<>(byPrompt.values());
    }

    private static DeckSummaryView summary(SetRow row, CoverageRow coverageRow, long userId) {
        Map<DeckField, Integer> coverage = new EnumMap<>(DeckField.class);
        coverage.put(DeckField.KANJI, coverageRow == null ? 0 : coverageRow.kanji());
        coverage.put(DeckField.HIRAGANA, coverageRow == null ? 0 : coverageRow.hiragana());
        coverage.put(DeckField.MEANING, coverageRow == null ? 0 : coverageRow.meaning());
        coverage.put(DeckField.HAN_VIET, coverageRow == null ? 0 : coverageRow.hanViet());
        return new DeckSummaryView(row.id(), row.title(), row.description(), row.thumbnail(), row.visibility(),
                row.draft(), row.ownerId() == userId, row.ownerName(),
                coverageRow == null ? 0 : coverageRow.total(), coverage, row.lastUsedAt(), row.updatedAt());
    }
}
