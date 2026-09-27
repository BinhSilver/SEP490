package com.jlas.game.runtime.neko;

import com.jlas.game.dto.NekoDtos.NekoMistakeView;
import com.jlas.game.runtime.PlayerSeat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Trạng thái của một người chơi Neko Catch. Chỉ sửa khi đang giữ lock của session. */
public class NekoPlayer {

    public static final String FINISH_LIVES = "LIVES";
    public static final String FINISH_TIME = "TIME";
    public static final String FINISH_FORFEIT = "FORFEIT";

    private final PlayerSeat seat;
    private final int maxLives;
    private final List<NekoMistakeView> mistakes = new ArrayList<>();
    private final Set<String> claimedBonuses = new HashSet<>();

    private long score;
    private int combo;
    private int maxCombo;
    private int correct;
    private int wrong;
    private int lives;
    private boolean shield;
    private int roundIndex;
    private Instant deadline;
    private String finishReason;
    private Instant finishedAt;

    public NekoPlayer(PlayerSeat seat, int lives, Instant deadline) {
        this.seat = seat;
        this.maxLives = lives;
        this.lives = lives;
        this.deadline = deadline;
    }

    public PlayerSeat getSeat() {
        return seat;
    }

    public long getUserId() {
        return seat.userId();
    }

    public void recordCorrect() {
        combo++;
        maxCombo = Math.max(maxCombo, combo);
        score += 10L + (combo / 3) * 10L;
        correct++;
    }

    public void recordWrong(NekoMistakeView mistake) {
        combo = 0;
        wrong++;
        lives = Math.max(0, lives - 1);
        mistakes.add(mistake);
    }

    public void addScore(long delta) {
        score += delta;
    }

    public void advance() {
        roundIndex++;
    }

    public void finish(String reason, Instant at) {
        if (finishReason == null) {
            finishReason = reason;
            finishedAt = at;
        }
    }

    public boolean isFinished() {
        return finishReason != null;
    }

    public boolean claimBonus(String roundId) {
        return claimedBonuses.add(roundId);
    }

    public long getScore() {
        return score;
    }

    public int getCombo() {
        return combo;
    }

    public int getMaxCombo() {
        return maxCombo;
    }

    public int getCorrect() {
        return correct;
    }

    public int getWrong() {
        return wrong;
    }

    public int getLives() {
        return lives;
    }

    public int getMaxLives() {
        return maxLives;
    }

    public boolean hasShield() {
        return shield;
    }

    public void setShield(boolean shield) {
        this.shield = shield;
    }

    public int getRoundIndex() {
        return roundIndex;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public List<NekoMistakeView> getMistakes() {
        return mistakes;
    }
}
