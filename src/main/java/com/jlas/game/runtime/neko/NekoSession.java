package com.jlas.game.runtime.neko;

import com.jlas.game.enums.GameType;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.LaunchRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Một trận Neko Catch: cùng một dãy lượt cho mọi người chơi, mỗi người một đồng hồ riêng. */
public class NekoSession extends GameSession {

    /**
     * @param distractors số cá sai rơi cùng cá đúng
     */
    public record Config(String difficulty, int lives, int distractors, int durationSeconds) {
    }

    private Config config;
    private List<NekoRound> rounds;
    /** Đáp án dùng làm cá sai (đã bỏ trùng): mặc định là đáp án của các thẻ trong trận. */
    private List<String> answerPool;
    private Instant startAt;
    private final Map<Long, NekoPlayer> players = new LinkedHashMap<>();
    private Instant pausedAt;
    /** Luyện tập: chưa bấm "Bắt đầu". {@code startAt} và hạn giờ chỉ là tạm, đặt lại lúc bấm. */
    private boolean awaitingStart;

    public NekoSession(LaunchRequest launch, Config config, List<NekoRound> rounds, Instant createdAt,
                       Instant startAt) {
        super(GameType.NEKO_CATCH, launch, createdAt);
        this.config = config;
        this.rounds = List.copyOf(rounds);
        this.startAt = startAt;
        this.answerPool = launch.words().stream().map(word -> word.answer()).distinct().toList();
    }

    public Config getConfig() {
        return config;
    }

    /** Luyện tập: người chơi đổi cài đặt ở màn chuẩn bị (chỉ khi chưa bắt đầu). */
    public void reconfigure(Config config, List<NekoRound> rounds) {
        this.config = config;
        this.rounds = List.copyOf(rounds);
    }

    public List<String> getAnswerPool() {
        return answerPool;
    }

    public void setAnswerPool(List<String> answerPool) {
        this.answerPool = List.copyOf(answerPool);
    }

    public List<NekoRound> getRounds() {
        return rounds;
    }

    /** Hết dãy thì quay vòng lại từ đầu. */
    public NekoRound roundAt(int index) {
        return rounds.get(Math.floorMod(index, rounds.size()));
    }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public boolean isAwaitingStart() {
        return awaitingStart;
    }

    public void setAwaitingStart(boolean awaitingStart) {
        this.awaitingStart = awaitingStart;
    }

    public void addPlayer(NekoPlayer player) {
        players.put(player.getUserId(), player);
    }

    public NekoPlayer player(long userId) {
        return players.get(userId);
    }

    public List<NekoPlayer> orderedPlayers() {
        List<NekoPlayer> ordered = new ArrayList<>(players.values());
        ordered.sort(Comparator.comparingInt(player -> player.getSeat().slot()));
        return ordered;
    }

    public boolean allFinished() {
        return players.values().stream().allMatch(NekoPlayer::isFinished);
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }
}
