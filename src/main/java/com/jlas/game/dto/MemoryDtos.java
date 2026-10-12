package com.jlas.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jlas.game.enums.DeckField;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * DTO của Memory Match. Hình dạng giữ như bản demo để bảng React dùng lại được; thẻ úp không
 * bao giờ mang nội dung và client không nhận pairId.
 */
public final class MemoryDtos {
    private MemoryDtos() { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoryCardView(String cardInstanceId, int position, String state, DeckField face, String content,
                                 Long matchedByUserId) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoryPlayerView(long userId, String displayName, String avatar, int slot, int pairsFound,
                                   int mistakes, int moves, int streak, int bestStreak, Integer accuracyPercent,
                                   Integer averageDecisionMs, boolean connected, boolean currentTurn) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoryConfigView(int boardSize, int pairCount, String objective, String pairMode, String level,
                                   int turnSeconds, Integer totalSeconds, Integer moveLimit, boolean keepTurnOnMatch,
                                   int revealDelayMs, DeckField questionField, DeckField answerField,
                                   boolean distinguishCardTypes) {
    }

    /** Một cặp đã dùng trên bàn, kèm đủ 4 trường để ôn lại. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoryTermView(String term, String reading, String meaning, String hanViet, String question,
                                 String answer, int mistakes) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoryResultView(boolean success, boolean ranked, Long winnerUserId, boolean draw, long durationMs,
                                   List<MemoryTermView> termsEncountered, List<MemoryTermView> strugglingTerms) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemoryStateView(String sessionId, String roomId, String playMode, String status,
                                  MemoryConfigView config, List<MemoryCardView> cards,
                                  List<MemoryPlayerView> players, long stateVersion, Long currentTurnUserId,
                                  boolean resolving, int pairsMatched, int pairsTotal, int movesUsed,
                                  Integer movesRemaining, Long turnStartedAt, Long turnDeadlineAt,
                                  Long totalDeadlineAt, long elapsedMs, String outcome, boolean ranked,
                                  MemoryResultView result, Long deckId, String deckTitle, boolean awaitingStart,
                                  long serverTime) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FlipRequest(@NotBlank(message = "Thiếu thẻ") String cardInstanceId) {
    }

    /** Payload SSE: loại sự kiện Memory (MEMORY_PAIR_MATCHED…) + snapshot mới nhất. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    /**
     * Cài đặt chọn ở màn cài đặt trước trận luyện tập (để trống thì giữ như cũ). Mọi thẻ trong {@code cardIds} phải có
     * đủ cả trường hỏi lẫn trường đáp. {@code totalSeconds} chỉ dùng khi {@code objective} = TIME_ATTACK.
     */
    public record MemoryStartRequest(DeckField questionField, DeckField answerField, List<Long> cardIds,
                                     Integer boardSize, String objective, Integer totalSeconds,
                                     Boolean distinguishCardTypes) {
    }

    public record MemoryEnvelope(String type, MemoryStateView payload) {
    }
}
