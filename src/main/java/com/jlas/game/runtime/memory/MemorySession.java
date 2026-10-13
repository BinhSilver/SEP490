package com.jlas.game.runtime.memory;

import com.jlas.game.enums.GameType;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.GameWord;
import com.jlas.game.runtime.LaunchRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ván Memory Match authoritative, giữ trong bộ nhớ server. Đối kháng: hai người chung một bàn,
 * lần lượt lật thẻ theo thứ tự chỗ ngồi.
 */
public class MemorySession extends GameSession {

    public static final String OUTCOME_CLEARED = "CLEARED";
    public static final String OUTCOME_TIME_UP = "TIME_UP";
    public static final String OUTCOME_ABANDONED = "ABANDONED";

    /**
     * @param totalSeconds chỉ có khi objective = TIME_ATTACK
     * @param distinguishCardTypes cho biết loại thẻ khi úp để client phân biệt bằng màu, không lộ nội dung
     */
    public record Config(int boardSize, String objective, int turnSeconds, Integer totalSeconds,
                         boolean keepTurnOnMatch, int revealDelayMs, boolean distinguishCardTypes) {
        public int pairCount() {
            return boardSize / 2;
        }
    }

    private Config config;
    /** Chỉ số = position để tra O(1). */
    private List<MemoryCard> cards;
    private final Map<String, MemoryCard> cardsById = new LinkedHashMap<>();
    /** pairId → từ gốc, dùng cho màn kết quả (không gửi giữa ván). */
    private Map<Integer, GameWord> pairWords;
    /** Luyện tập: chưa bấm "Bắt đầu" (bàn và cài đặt còn đổi được ở màn cài đặt). */
    private boolean awaitingStart;
    /** Lúc bắt đầu tính giờ chơi (sau màn đếm ngược / tung đồng xu). */
    private Instant playStartedAt;
    private final Map<Long, String> coinChoices = new LinkedHashMap<>();
    private String coinResult;
    private Instant coinStartedAt;
    private Long coinStarterUserId;
    private final Long draftedFirstUserId;

    public Map<Long, String> getCoinChoices() { return coinChoices; }
    public String getCoinResult() { return coinResult; }
    public Instant getCoinStartedAt() { return coinStartedAt; }
    public Long getCoinStarterUserId() { return coinStarterUserId; }
    public Long getDraftedFirstUserId() { return draftedFirstUserId; }
    public void startCoin(String result, long starter, Instant now) {
        coinResult = result; coinStarterUserId = starter; coinStartedAt = now;
    }
    private final Map<Long, MemoryPlayer> players = new LinkedHashMap<>();
    private final List<Long> turnOrder = new ArrayList<>();
    private final Map<Integer, Integer> mistakesByPair = new LinkedHashMap<>();
    private final List<String> selection = new ArrayList<>();

    private long stateVersion;
    private long currentTurnUserId;
    private boolean resolving;
    private Instant turnStartedAt;
    private Instant turnDeadlineAt;
    private Instant firstFlipAt;
    private Instant totalDeadlineAt;
    private Instant pausedAt;
    private long pausedTotalMs;
    private int movesUsed;
    private int pairsMatched;
    private String outcome;

    public MemorySession(LaunchRequest launch, Config config, List<MemoryCard> cards,
                         Map<Integer, GameWord> pairWords, Instant createdAt) {
        super(GameType.MEMORY_MATCH, launch, createdAt);
        draftedFirstUserId = launch.options().firstUserId();
        rebuild(config, cards, pairWords);
    }

    /** Đặt bàn mới (lúc mở trận, hoặc khi người chơi đổi cài đặt ở màn cài đặt trước trận). Gọi trong lock. */
    public final void rebuild(Config config, List<MemoryCard> cards, Map<Integer, GameWord> pairWords) {
        this.config = config;
        this.cards = List.copyOf(cards);
        this.pairWords = Map.copyOf(pairWords);
        cardsById.clear();
        for (MemoryCard card : this.cards) {
            cardsById.put(card.getCardInstanceId(), card);
        }
    }

    public boolean isAwaitingStart() {
        return awaitingStart;
    }

    public void setAwaitingStart(boolean awaitingStart) {
        this.awaitingStart = awaitingStart;
    }

    public Instant getPlayStartedAt() {
        return playStartedAt;
    }

    public void setPlayStartedAt(Instant playStartedAt) {
        this.playStartedAt = playStartedAt;
    }

    public Config getConfig() {
        return config;
    }

    public List<MemoryCard> getCards() {
        return cards;
    }

    public Optional<MemoryCard> findCard(String cardInstanceId) {
        return cardInstanceId == null ? Optional.empty() : Optional.ofNullable(cardsById.get(cardInstanceId));
    }

    public Map<Integer, GameWord> getPairWords() {
        return pairWords;
    }

    public void addPlayer(MemoryPlayer player) {
        players.put(player.getUserId(), player);
        turnOrder.add(player.getUserId());
        if (turnOrder.size() == 1) {
            currentTurnUserId = player.getUserId();
        }
    }

    public MemoryPlayer player(long userId) {
        return players.get(userId);
    }

    public List<MemoryPlayer> orderedPlayers() {
        List<MemoryPlayer> ordered = new ArrayList<>(players.values());
        ordered.sort(Comparator.comparingInt(player -> player.getSeat().slot()));
        return ordered;
    }

    public Map<Integer, Integer> getMistakesByPair() {
        return mistakesByPair;
    }

    public void recordPairMistake(int pairId) {
        mistakesByPair.merge(pairId, 1, Integer::sum);
    }

    public long getStateVersion() {
        return stateVersion;
    }

    public void bumpVersion() {
        stateVersion++;
    }

    public long getCurrentTurnUserId() {
        return currentTurnUserId;
    }

    public void setCurrentTurnUserId(long currentTurnUserId) {
        this.currentTurnUserId = currentTurnUserId;
    }

    public boolean isMyTurn(long userId) {
        return currentTurnUserId == userId;
    }

    /** Người kế tiếp trong thứ tự lượt. */
    public long nextTurnUserId() {
        if (turnOrder.isEmpty()) {
            return currentTurnUserId;
        }
        int index = turnOrder.indexOf(currentTurnUserId);
        return turnOrder.get((index + 1) % turnOrder.size());
    }

    public List<String> getSelection() {
        return selection;
    }

    public boolean isResolving() {
        return resolving;
    }

    public void setResolving(boolean resolving) {
        this.resolving = resolving;
    }

    public int getMovesUsed() {
        return movesUsed;
    }

    public void incrementMoves() {
        movesUsed++;
    }

    public int getPairsMatched() {
        return pairsMatched;
    }

    public void incrementPairsMatched() {
        pairsMatched++;
    }

    public boolean isBoardCleared() {
        return pairsMatched >= config.pairCount();
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Instant getTurnStartedAt() {
        return turnStartedAt;
    }

    public Instant getTurnDeadlineAt() {
        return turnDeadlineAt;
    }

    public Instant getTotalDeadlineAt() {
        return totalDeadlineAt;
    }

    public void setTotalDeadlineAt(Instant totalDeadlineAt) {
        this.totalDeadlineAt = totalDeadlineAt;
    }

    public Instant getFirstFlipAt() {
        return firstFlipAt;
    }

    public void setFirstFlipAt(Instant firstFlipAt) {
        this.firstFlipAt = firstFlipAt;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }

    public void addPausedMs(long millis) {
        pausedTotalMs += Math.max(0, millis);
    }

    public void startTurn(Instant now) {
        turnStartedAt = now;
        turnDeadlineAt = now.plusSeconds(config.turnSeconds());
        firstFlipAt = null;
    }

    /** Dời mọi mốc giờ sau khi tiếp tục chơi. */
    public void shiftDeadlines(Duration by) {
        if (turnDeadlineAt != null) {
            turnDeadlineAt = turnDeadlineAt.plus(by);
        }
        if (turnStartedAt != null) {
            turnStartedAt = turnStartedAt.plus(by);
        }
        if (totalDeadlineAt != null) {
            totalDeadlineAt = totalDeadlineAt.plus(by);
        }
    }

    public long elapsedMs(Instant now) {
        if (awaitingStart || (isVersus() && playStartedAt == null)) {
            return 0;
        }
        Instant start = playStartedAt != null ? playStartedAt : getCreatedAt();
        Instant end = getFinishedAt() != null ? getFinishedAt() : now;
        return Math.max(0, Duration.between(start, end).toMillis() - pausedTotalMs);
    }
}
