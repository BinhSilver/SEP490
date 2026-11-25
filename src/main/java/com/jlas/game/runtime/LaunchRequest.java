package com.jlas.game.runtime;

import com.jlas.game.dto.GameDtos.GameOptions;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.PlayMode;

import java.util.List;

/**
 * Mọi thứ một engine cần để mở trận: người chơi, bộ thẻ đã lọc theo 2 trường và tuỳ chọn đã chuẩn hoá.
 *
 * @param roomId null với chế độ chơi đơn
 */
public record LaunchRequest(String sessionId, PlayMode mode, String roomId, String roomCode, Long matchId,
                            Long deckId, String deckTitle, DeckField questionField, DeckField answerField,
                            GameOptions options, List<PlayerSeat> seats, List<GameWord> words, String jlpt) {
    public LaunchRequest(String sessionId, PlayMode mode, String roomId, String roomCode, Long matchId,
                         Long deckId, String deckTitle, DeckField questionField, DeckField answerField,
                         GameOptions options, List<PlayerSeat> seats, List<GameWord> words) {
        this(sessionId, mode, roomId, roomCode, matchId, deckId, deckTitle, questionField, answerField,
                options, seats, words, null);
    }
}
