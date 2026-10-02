package com.jlas.game.runtime;

import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.PlayMode;
import com.jlas.game.enums.SessionStatus;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Phần chung của một trận đang chạy trong bộ nhớ. Mỗi game kế thừa để giữ luật riêng; mọi thay đổi
 * chạy trong {@link #withLock} vì thao tác của hai người chơi và bộ đếm giờ có thể tới cùng lúc.
 */
public abstract class GameSession {

    private final ReentrantLock lock = new ReentrantLock();
    private final GameType gameType;
    private final LaunchRequest launch;
    private final Instant createdAt;
    private final Set<Long> offline = new HashSet<>();

    private SessionStatus status = SessionStatus.RUNNING;
    private DeckField questionField;
    private DeckField answerField;
    private List<GameWord> words;
    private Instant lastActivityAt;
    private Instant finishedAt;
    private ResultSummary summary;

    protected GameSession(GameType gameType, LaunchRequest launch, Instant createdAt) {
        this.gameType = gameType;
        this.launch = launch;
        this.createdAt = createdAt;
        this.lastActivityAt = createdAt;
        this.questionField = launch.questionField();
        this.answerField = launch.answerField();
        this.words = launch.words();
    }

    public <T> T withLock(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public GameType getGameType() {
        return gameType;
    }

    public String getSessionId() {
        return launch.sessionId();
    }

    public PlayMode getMode() {
        return launch.mode();
    }

    public String getRoomId() {
        return launch.roomId();
    }

    public String getRoomCode() {
        return launch.roomCode();
    }

    public Long getMatchId() {
        return launch.matchId();
    }

    /** Thẻ đang dùng cho trận (đủ cả trường hỏi lẫn trường đáp). */
    public List<GameWord> getWords() {
        return words;
    }

    /** Luyện tập đổi cặp hỏi – đáp / chọn thẻ ở màn cài đặt trước khi bắt đầu. Gọi khi đang giữ lock. */
    public void useWords(DeckField questionField, DeckField answerField, List<GameWord> words) {
        this.questionField = questionField;
        this.answerField = answerField;
        this.words = List.copyOf(words);
    }

    public Long getDeckId() {
        return launch.deckId();
    }

    public String getDeckTitle() {
        return launch.deckTitle();
    }

    public DeckField getQuestionField() {
        return questionField;
    }

    public DeckField getAnswerField() {
        return answerField;
    }

    public List<PlayerSeat> getSeats() {
        return launch.seats();
    }

    public List<Long> userIds() {
        return launch.seats().stream().map(PlayerSeat::userId).toList();
    }

    public boolean isParticipant(long userId) {
        return launch.seats().stream().anyMatch(seat -> seat.userId() == userId);
    }

    public Optional<PlayerSeat> seatOf(long userId) {
        return launch.seats().stream().filter(seat -> seat.userId() == userId).findFirst();
    }

    public boolean isVersus() {
        return launch.mode() == PlayMode.VERSUS;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void touch(Instant now) {
        lastActivityAt = now;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public ResultSummary getSummary() {
        return summary;
    }

    public void setSummary(ResultSummary summary) {
        this.summary = summary;
    }

    public boolean isOnline(long userId) {
        return !offline.contains(userId);
    }

    /** @return true nếu trạng thái thực sự đổi. */
    public boolean setOnline(long userId, boolean online) {
        return online ? offline.remove(userId) : offline.add(userId);
    }
}
