package com.jlas.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.PlayMode;
import com.jlas.game.enums.SessionStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * DTO của Air Defence. Mô phỏng sóng địch chạy ở client; server kiểm lại từng lần bắn trúng,
 * giữ HP/điểm để đối thủ thấy và chuyển tiếp đòn tấn công combo.
 */
public final class AirDefenseDtos {
    private AirDefenseDtos() { }

    /** {@code prompt} hiện trên địch, {@code answer} là thứ người chơi phải gõ. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirWordView(String id, String prompt, String answer, String kanji, String reading, String meaning,
                              String hanViet) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirConfigView(int durationSeconds, DeckField questionField, DeckField answerField,
                                String questionLabel, String answerLabel, boolean versus,
                                List<Integer> attackMilestones) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirPlayerView(long userId, String displayName, String avatarUrl, int slot, int hp, int maxHp,
                                long score, int combo, int bestCombo, int wave, int verifiedKills, int wrongInputs,
                                boolean alive, boolean finished, boolean online) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirAttackView(String attackId, long fromUserId, String fromName, int milestone, AirWordView word,
                                int bossHp) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirStateView(String sessionId, PlayMode mode, String roomId, SessionStatus status, Long deckId,
                               String deckTitle, AirConfigView config, List<AirWordView> words, long meUserId,
                               List<AirPlayerView> players, long startAt, Long endsAt, ResultSummary result,
                               boolean awaitingStart, long serverTime) {
    }

    /** Luyện tập: cài đặt trước trận. {@code cardIds} null = cả bộ; {@code durationSeconds} 0 = không giới hạn. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirStartRequest(DeckField questionField, DeckField answerField, List<Long> cardIds,
                                  Integer durationSeconds) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirKillRequest(@NotBlank(message = "Thiếu mục tiêu") String wordId, String rawInput) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirKillResult(boolean verified, int verifiedKills) {
    }

    /** Client báo trạng thái hành tinh của mình; server kẹp giá trị vào khoảng hợp lệ. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirReportRequest(Integer hp, Integer maxHp, Long score, Integer combo, Integer bestCombo,
                                   Integer wave, Integer wrongInputs) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirAttackRequest(int milestone) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AirAttackResult(boolean sent, String message) {
    }
}
