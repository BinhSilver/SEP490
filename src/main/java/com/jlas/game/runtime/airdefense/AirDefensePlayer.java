package com.jlas.game.runtime.airdefense;

import com.jlas.game.runtime.PlayerSeat;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Hành tinh của một người chơi Air Defence. Chỉ sửa khi đang giữ lock của session. */
public class AirDefensePlayer {

    public static final int DEFAULT_HP = 100;

    private final PlayerSeat seat;
    private final Set<String> killedWordIds = new HashSet<>();
    /** Mốc combo đã gửi đòn trong chuỗi combo hiện tại; reset khi combo tụt. */
    private final Set<Integer> usedMilestones = new HashSet<>();

    private int hp = DEFAULT_HP;
    private int maxHp = DEFAULT_HP;
    private long score;
    private int combo;
    private int bestCombo;
    private int wave = 1;
    private int verifiedKills;
    private int rejectedKills;
    private int wrongInputs;
    private boolean alive = true;
    private boolean forfeited;
    private Instant finishedAt;

    public AirDefensePlayer(PlayerSeat seat) {
        this.seat = seat;
    }

    public PlayerSeat getSeat() {
        return seat;
    }

    public long getUserId() {
        return seat.userId();
    }

    /** @return false nếu mục tiêu này đã được tính rồi. */
    public boolean markKilled(String wordId) {
        return killedWordIds.add(wordId);
    }

    public void verifiedKill() {
        verifiedKills++;
    }

    public void rejectedKill() {
        rejectedKills++;
    }

    public void applyReport(int hp, int maxHp, long score, int combo, int bestCombo, int wave, int wrongInputs) {
        if (combo < this.combo) {
            usedMilestones.clear();
        }
        this.maxHp = maxHp;
        this.hp = Math.min(hp, maxHp);
        this.score = score;
        this.combo = combo;
        this.bestCombo = Math.max(this.bestCombo, bestCombo);
        this.wave = wave;
        this.wrongInputs = wrongInputs;
    }

    public boolean useMilestone(int milestone) {
        return usedMilestones.add(milestone);
    }

    public void finish(Instant at, boolean survived) {
        if (finishedAt == null) {
            finishedAt = at;
            alive = survived;
        }
    }

    public boolean isFinished() {
        return finishedAt != null;
    }

    public int getHp() {
        return hp;
    }

    public void setHp(int hp) {
        this.hp = hp;
    }

    public int getMaxHp() {
        return maxHp;
    }

    public long getScore() {
        return score;
    }

    public int getCombo() {
        return combo;
    }

    public int getBestCombo() {
        return bestCombo;
    }

    public int getWave() {
        return wave;
    }

    public int getVerifiedKills() {
        return verifiedKills;
    }

    public int getRejectedKills() {
        return rejectedKills;
    }

    public int getWrongInputs() {
        return wrongInputs;
    }

    public boolean isAlive() {
        return alive;
    }

    public boolean isForfeited() {
        return forfeited;
    }

    public void setForfeited(boolean forfeited) {
        this.forfeited = forfeited;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
