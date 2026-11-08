package com.jlas.game.runtime.airdefense;

import com.jlas.game.enums.GameType;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.GameWord;
import com.jlas.game.runtime.LaunchRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Một trận Air Defence. Hai người nhận cùng một dãy từ (theo thứ tự) để đợt địch giống nhau;
 * mỗi người tự mô phỏng hành tinh của mình và báo lên server.
 */
public class AirDefenseSession extends GameSession {

    /** id từ ("w12") → từ; id ổn định để client báo lại mục tiêu đã bắn hạ. */
    private final Map<String, GameWord> wordsById = new LinkedHashMap<>();
    private List<String> sequence;
    private int durationSeconds;
    private Instant startAt;
    private Instant endsAt;
    private final Map<Long, AirDefensePlayer> players = new LinkedHashMap<>();
    private Instant pausedAt;
    /** Luyện tập: chờ người chơi cài đặt và bấm "Bắt đầu" (xem {@link #restart}). */
    private boolean awaitingStart;

    public AirDefenseSession(LaunchRequest launch, List<GameWord> sequence, int durationSeconds, Instant createdAt,
                             Instant startAt) {
        super(GameType.AIR_DEFENSE, launch, createdAt);
        restart(sequence, durationSeconds, startAt);
    }

    /** Gọi trong lock. Thay dãy từ và thời lượng (màn cài đặt của chơi đơn); địch xuất hiện từ {@code startAt}. */
    public final void restart(List<GameWord> sequence, int durationSeconds, Instant startAt) {
        wordsById.clear();
        List<String> ids = new ArrayList<>(sequence.size());
        for (int i = 0; i < sequence.size(); i++) {
            String id = "w" + i;
            wordsById.put(id, sequence.get(i));
            ids.add(id);
        }
        this.sequence = List.copyOf(ids);
        this.durationSeconds = durationSeconds;
        this.startAt = startAt;
        this.endsAt = durationSeconds > 0 ? startAt.plusSeconds(durationSeconds) : null;
    }

    public boolean isAwaitingStart() {
        return awaitingStart;
    }

    public void setAwaitingStart(boolean awaitingStart) {
        this.awaitingStart = awaitingStart;
    }

    public Map<String, GameWord> getWordsById() {
        return wordsById;
    }

    public List<String> getSequence() {
        return sequence;
    }

    public GameWord word(String wordId) {
        return wordsById.get(wordId);
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public Instant getStartAt() {
        return startAt;
    }

    /** null = vô tận (chơi đơn). */
    public Instant getEndsAt() {
        return endsAt;
    }

    public void addPlayer(AirDefensePlayer player) {
        players.put(player.getUserId(), player);
    }

    public AirDefensePlayer player(long userId) {
        return players.get(userId);
    }

    public List<AirDefensePlayer> orderedPlayers() {
        List<AirDefensePlayer> ordered = new ArrayList<>(players.values());
        ordered.sort(Comparator.comparingInt(player -> player.getSeat().slot()));
        return ordered;
    }

    public AirDefensePlayer opponentOf(long userId) {
        return players.values().stream().filter(player -> player.getUserId() != userId).findFirst().orElse(null);
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }
}
