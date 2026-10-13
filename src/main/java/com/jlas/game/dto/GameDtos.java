package com.jlas.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.PlayMode;
import com.jlas.game.enums.RoomStatus;
import com.jlas.game.enums.RoomVisibility;
import com.jlas.game.enums.SessionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** DTO chung của Game Arena: bộ thẻ, cài đặt trận, phòng, sự kiện realtime. Mốc thời gian tính bằng epoch ms. */
public final class GameDtos {
    private GameDtos() { }

    // ----------------------------------------------------------------- settings

    /**
     * Cài đặt một trận. {@code setTitle} do server điền; client chỉ gửi setId + 2 trường + options.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GameSettings(Long setId, String setTitle, DeckField questionField, DeckField answerField,
                               GameOptions options) {
        public GameSettings withTitle(String title) {
            return new GameSettings(setId, title, questionField, answerField, options);
        }

        public GameSettings withOptions(GameOptions value) {
            return new GameSettings(setId, setTitle, questionField, answerField, value);
        }
    }

    /** Tuỳ chọn riêng từng game; trường nào không dùng cho game đó thì để null. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GameOptions(String difficulty, Integer durationSeconds, Integer boardSize, Integer turnSeconds,
                              String objective, Integer totalSeconds, Boolean keepTurnOnMatch, Long firstUserId,
                              Integer lives, Integer choices, List<Long> cardIds, Boolean distinguishCardTypes) {
        public GameOptions(String difficulty, Integer durationSeconds, Integer boardSize, Integer turnSeconds,
                           String objective, Integer totalSeconds, Boolean keepTurnOnMatch, Long firstUserId,
                           Integer lives, Integer choices, List<Long> cardIds) {
            this(difficulty, durationSeconds, boardSize, turnSeconds, objective, totalSeconds, keepTurnOnMatch,
                    firstUserId, lives, choices, cardIds, null);
        }
        public GameOptions(String difficulty, Integer durationSeconds, Integer boardSize, Integer turnSeconds,
                           String objective, Integer totalSeconds, Boolean keepTurnOnMatch, Long firstUserId) {
            this(difficulty, durationSeconds, boardSize, turnSeconds, objective, totalSeconds, keepTurnOnMatch,
                    firstUserId, null, null, null);
        }
    }

    /** Một thẻ của bộ với 4 trường đã chuẩn hoá (null nếu thẻ không có trường đó), cho bảng chọn thẻ. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DeckCardView(long id, String kanji, String reading, String meaning, String hanViet) {
        public String value(DeckField field) {
            return switch (field) {
                case KANJI -> kanji;
                case HIRAGANA -> reading;
                case MEANING -> meaning;
                case HAN_VIET -> hanViet;
            };
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CatalogItem(GameType type, String slug, String name, String tagline, String image,
                              int minCards,
                              List<DeckField> answerFields, GameOptions defaults,
                              Map<String, List<Object>> choices) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FieldView(DeckField key, String label, boolean japanese) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CatalogView(List<CatalogItem> games, List<FieldView> fields) {
    }

    // -------------------------------------------------------------------- decks

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DeckSummaryView(Long id, String title, String description, String thumbnail, String visibility,
                                  boolean draft, boolean ownedByMe, String ownerName, int cardCount,
                                  Map<DeckField, Integer> coverage, LocalDateTime lastUsedAt,
                                  LocalDateTime updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DeckPage(String tab, String query, List<DeckSummaryView> items, int page, boolean hasMore) {
    }

    /** Số thẻ dùng được khi hỏi {@code questionField} và đáp {@code answerField}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DeckPairView(DeckField questionField, DeckField answerField, int usableCards,
                               int distinctAnswers) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DeckSampleCard(String kanji, String reading, String meaning, String hanViet) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DeckDetailView(DeckSummaryView deck, List<DeckPairView> pairs, List<DeckSampleCard> samples) {
    }

    // -------------------------------------------------------------------- rooms

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CreateRoomRequest(@NotNull(message = "Chọn game") GameType gameType, RoomVisibility visibility,
                                    @NotNull(message = "Chọn bộ flashcard") @Valid GameSettings settings) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record JoinRoomRequest(@NotBlank(message = "Nhập mã phòng")
                                  @Pattern(regexp = "[0-9]{6}",
                                          message = "Mã phòng gồm 6 chữ số") String code) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReadyRequest(boolean ready) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SoloRequest(@NotNull(message = "Chọn game") GameType gameType,
                              @NotNull(message = "Chọn bộ flashcard") @Valid GameSettings settings) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LaunchView(String sessionId, GameType gameType, String playUrl) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RoomPlayerView(long userId, String displayName, String avatarUrl, int slot, boolean host,
                                 boolean ready, boolean online) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ResultLine(long userId, String displayName, int rank, long score, String detail, boolean winner) {
    }

    /** Tóm tắt kết quả một trận, dùng chung cho mọi game (phòng chờ + màn kết quả). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ResultSummary(String sessionId, GameType gameType, PlayMode mode, Long winnerUserId, boolean draw,
                                List<ResultLine> lines, long finishedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RoomView(String roomId, String code, GameType gameType, String gameName, RoomVisibility visibility,
                           RoomStatus status, long hostUserId, GameSettings settings, List<RoomPlayerView> players,
                           int maxPlayers, Long countdownEndsAt, String sessionId, String playUrl,
                           ResultSummary lastResult, boolean quickMatch, DraftView draft, long version,
                           long serverTime) {
    }

    /**
     * @param searching số người đang ghép trận nhanh theo từng game
     * @param quickMatch lượt tìm trận của chính người gọi (null nếu không tìm)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LobbyView(int onlinePlayers, int activeMatches, String myRoomId, Map<GameType, Integer> searching,
                            QuickMatchView quickMatch) {
    }

    // -------------------------------------------------------------- quick match

    /** Ghép trận nhanh chỉ cần cùng game, cùng bộ; các cài đặt còn lại do hai bên tung đồng xu chọn. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record QuickMatchRequest(@NotNull(message = "Chọn game") GameType gameType,
                                    @NotNull(message = "Chọn bộ flashcard") Long setId) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftChoiceRequest(@NotBlank(message = "Thiếu cài đặt") String stepKey, String value) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftOptionView(String value, String label, String hint) {
    }

    /**
     * @param phase         TOSS (đồng xu đang lật) / CHOOSE (đang chọn) / DONE / WAITING (chưa tới)
     * @param tossWinnerUserId người ra mặt ngửa
     * @param chooserUserId người đang giữ quyền chọn (khác người thắng nếu đã nhường)
     * @param options       chỉ có ở cài đặt đang chọn
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftStepView(String key, String title, String phase, Long tossWinnerUserId, Long chooserUserId,
                                boolean passed, String chosenValue, String chosenLabel, Long chosenByUserId,
                                boolean timedOut, List<DraftOptionView> options, String recommended) {
    }

    /** @param phase INTRO / TOSS / CHOOSE / DONE của cả lượt chọn */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftView(String phase, int index, Long phaseEndsAt, List<DraftStepView> steps) {
    }

    /**
     * @param status            SEARCHING / MATCHED / IDLE
     * @param searchingSameGame số người khác đang tìm trận cùng game (mọi bộ thẻ)
     * @param roomId            phòng vừa ghép (status = MATCHED)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record QuickMatchView(String status, GameType gameType, String gameName, Long setId, String setTitle,
                                 long waitedMs, int searchingSameGame, String roomId) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record QuickMatchFoundPayload(String roomId, GameType gameType, String opponentName, String setTitle) {
    }

    // ------------------------------------------------------------------ session

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SessionPlayerView(long userId, String displayName, String avatarUrl, int slot, boolean online) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SessionInfoView(String sessionId, GameType gameType, PlayMode mode, String roomId, String roomCode,
                                  SessionStatus status, Long deckId, String deckTitle, DeckField questionField,
                                  DeckField answerField, List<SessionPlayerView> players, long meUserId,
                                  String playUrl, String backUrl, long serverTime) {
    }

    // ------------------------------------------------------------------- events

    /** Một sự kiện SSE gửi tới trình duyệt. {@code payload} tuỳ theo {@code type}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ArenaEvent(String type, String roomId, String sessionId, Object payload, long serverTime) {
        public static ArenaEvent of(String type, String roomId, String sessionId, Object payload) {
            return new ArenaEvent(type, roomId, sessionId, payload, System.currentTimeMillis());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RoomStartedPayload(String sessionId, GameType gameType, String playUrl) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RoomClosedPayload(String message) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PresencePayload(long userId, boolean online) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SessionFinishedPayload(ResultSummary summary, Object detail) {
    }
}
