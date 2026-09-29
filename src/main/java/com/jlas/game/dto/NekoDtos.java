package com.jlas.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.PlayMode;
import com.jlas.game.enums.SessionStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * DTO của Neko Catch. Round gửi cho client KHÔNG kèm đáp án đúng; server chấm từng lượt bắt.
 */
public final class NekoDtos {
    private NekoDtos() { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NekoChoiceView(String id, String text) {
    }

    /** {@code bonus}: GOLDEN_FISH/CLOCK/SHIELD/FREEZE rơi cùng lượt, hoặc null. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NekoRoundView(String id, int index, String prompt, List<NekoChoiceView> choices, String bonus) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    /** {@code maxChoices}: số cá tối đa mỗi lượt mà bộ thẻ cho phép (số đáp án khác nhau, tối đa 7). */
    public record NekoConfigView(String difficulty, int lives, int choices, int maxChoices, int durationSeconds,
                                 DeckField questionField, DeckField answerField, String questionLabel,
                                 String answerLabel, boolean pausable) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NekoPlayerView(long userId, String displayName, String avatarUrl, int slot, long score, int combo,
                                 int maxCombo, int correct, int wrong, int lives, int maxLives, boolean shield,
                                 int roundIndex, long deadlineAt, boolean finished, String finishReason,
                                 boolean online) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NekoMistakeView(String prompt, String correctText, String chosenText) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NekoStateView(String sessionId, PlayMode mode, String roomId, SessionStatus status,
                                Long deckId, String deckTitle, NekoConfigView config, List<NekoRoundView> rounds,
                                long meUserId, List<NekoPlayerView> players, long startAt, boolean awaitingStart,
                                Long pausedAt,
                                List<NekoMistakeView> myMistakes, ResultSummary result, long serverTime) {
    }

    /**
     * Cài đặt người chơi chọn ở màn chuẩn bị của trận luyện tập; để trống thì giữ như cũ. {@code cardIds} là các
     * thẻ muốn chơi (mọi thẻ đã chọn phải có đủ cả trường hỏi lẫn trường đáp).
     */
    public record NekoStartRequest(String difficulty, Integer durationSeconds, Integer lives, Integer choices,
                                   DeckField questionField, DeckField answerField, List<Long> cardIds) {
    }

    /** {@code choiceId} chỉ cần cho lượt bắt; miss/bonus chỉ gửi roundId. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NekoActionRequest(@NotBlank(message = "Thiếu lượt chơi") String roundId, String choiceId) {
    }

    /**
     * @param accepted      false nếu lượt đã cũ (client cần tải lại state)
     * @param shieldBlocked khiên đã chặn một lỗi, không mất tim
     * @param advanced      server đã chuyển sang lượt kế tiếp
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NekoActionResult(boolean accepted, boolean correct, String correctChoiceId, String correctText,
                                   boolean shieldBlocked, String bonusApplied, boolean advanced,
                                   NekoPlayerView me, boolean finished, long serverTime) {
    }
}
