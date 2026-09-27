package com.jlas.game.runtime.memory;

import com.jlas.game.runtime.PlayerSeat;

/** Thống kê của một người chơi Memory. Server là nơi duy nhất cộng điểm. */
public class MemoryPlayer {

    private final PlayerSeat seat;
    private int pairsFound;
    private int mistakes;
    private int moves;
    private int streak;
    private int bestStreak;
    private long totalDecisionMs;
    private int decisionSamples;
    private boolean forfeited;

    public MemoryPlayer(PlayerSeat seat) {
        this.seat = seat;
    }

    public PlayerSeat getSeat() {
        return seat;
    }

    public long getUserId() {
        return seat.userId();
    }

    public void recordMove() {
        moves++;
    }

    public void recordMatch() {
        pairsFound++;
        streak++;
        bestStreak = Math.max(bestStreak, streak);
    }

    public void recordMistake() {
        mistakes++;
        streak = 0;
    }

    public void recordDecision(long millis) {
        if (millis > 0) {
            totalDecisionMs += millis;
            decisionSamples++;
        }
    }

    public Integer averageDecisionMs() {
        return decisionSamples == 0 ? null : (int) (totalDecisionMs / decisionSamples);
    }

    /** Độ chính xác theo số lượt ghép (mỗi lượt = 2 thẻ). */
    public Integer accuracyPercent() {
        int attempts = pairsFound + mistakes;
        return attempts == 0 ? null : (int) Math.round(pairsFound * 100.0 / attempts);
    }

    public int getPairsFound() {
        return pairsFound;
    }

    public int getMistakes() {
        return mistakes;
    }

    public int getMoves() {
        return moves;
    }

    public int getStreak() {
        return streak;
    }

    public int getBestStreak() {
        return bestStreak;
    }

    public boolean isForfeited() {
        return forfeited;
    }

    public void setForfeited(boolean forfeited) {
        this.forfeited = forfeited;
    }
}
