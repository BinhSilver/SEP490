package com.jlas.game.service;

import com.jlas.game.enums.GameType;
import com.jlas.game.dto.GameDtos.DeckCardView;
import com.jlas.game.dto.GameDtos.DeckDetailView;
import com.jlas.game.dto.GameDtos.DeckPage;
import com.jlas.game.enums.DeckField;
import com.jlas.game.runtime.GameWord;

import java.util.List;

/** Đọc bộ flashcard làm dữ liệu cho game. */
public interface GameDeckService {

    /** Tab MINE (bộ của tôi), RECENT (xem gần đây) hoặc ALL (của tôi + công khai); lọc theo tên. */
    /** {@code game} khác null: chỉ trả bộ chơi được game đó (đã xuất bản, có cặp hỏi – đáp đủ thẻ tối thiểu). */
    DeckPage decks(long userId, String tab, String query, int page, GameType game);

    DeckDetailView deck(long userId, long setId);

    /** Các thẻ của bộ (theo thứ tự trong bộ) với 4 trường, cho bảng chọn thẻ trước trận. */
    List<DeckCardView> cards(long userId, long setId);

    /**
     * Thẻ có đủ cả trường hỏi lẫn trường đáp, bỏ thẻ trùng. Quyền đọc kiểm theo {@code authorityUserId}
     * (chủ phòng), nên khách trong phòng vẫn chơi được bộ riêng tư chủ phòng đã chọn.
     */
    DeckWords loadWords(long authorityUserId, long setId, DeckField questionField, DeckField answerField);

    record DeckWords(Long setId, String title, List<GameWord> words) {
    }

    /**
     * Luyện tập: cặp hỏi – đáp và các thẻ người chơi chọn ở màn cài đặt trước trận ({@code cardIds} null = cả bộ).
     * Mọi thẻ đã chọn phải có đủ cả hai trường; cần ít nhất {@code minWords} từ khác nhau.
     */
    PickedDeck pickCards(long userId, long setId, DeckField questionField, DeckField answerField, List<Long> cardIds,
                         int minWords, String gameName);

    /** {@code deckCards}: mọi thẻ của bộ (để lấy đáp án gây nhiễu từ cả bộ, không chỉ thẻ đã chọn). */
    record PickedDeck(DeckField questionField, DeckField answerField, List<GameWord> words,
                      List<DeckCardView> deckCards) {
    }
}
