package com.jlas.game.dto;

import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.enums.GameType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class PuzzleDtos {
    private PuzzleDtos() { }

    public record RoundAction(@Positive int round) { }
    public record AnswerAction(@Positive int round, @NotBlank @Size(max = 100) String answer) { }
    public record AnswerReply(boolean correct, int round) { }
    /** One piece of a stroke; {@code fill} is a paint-bucket fill at (x, y) in {@code color}, replayed in order. */
    public record Segment(@Min(0) @Max(10000) int x, @Min(0) @Max(10000) int y,
                          @Min(0) @Max(10000) int toX, @Min(0) @Max(10000) int toY,
                          @Min(0) @Max(6) int color, @Min(2) @Max(24) int width, boolean erase, boolean fill) {
        public Segment(int x, int y, int toX, int toY, int color, int width, boolean erase) {
            this(x, y, toX, toY, color, width, erase, false);
        }
    }
    public enum InkEdit { UNDO, REDO }

    public record InkAction(@Positive int round, boolean clear, @NotNull @Size(max = 48) List<@NotNull @Valid Segment> segments,
                            @Positive Long strokeId, InkEdit edit) {
        public InkAction(int round, boolean clear, List<Segment> segments) {
            this(round, clear, segments, null, null);
        }
    }

    public record InkDelta(int round, long sequence, boolean clear, List<Segment> segments, boolean canUndo, boolean canRedo) { }
    public record Player(long userId, String displayName, String avatarUrl, boolean online, int score, boolean solved) { }
    public record Word(String answer, String reading, String meaning, String explanation) { }
    /** Points one player took in one round. */
    public record RoundPoint(long userId, int points) { }
    /** A finished round for the result screen: the word, who solved it and the points it gave. */
    public record RoundResult(int round, String answer, String reading, String meaning, List<Long> solvedBy,
                              List<RoundPoint> points, Long drawerUserId) { }
    /** A guess typed in Draw & Guess, shown to both players like a chat line. */
    public record Guess(long userId, String text, boolean correct) { }
    public record View(GameType gameType, String jlpt, String map, int round, int totalRounds, String phase,
                       long version, long serverTime, Long nextHintAt, Long deadline, List<String> images,
                       List<String> hints, String prompt, Word word, int answerSlots, boolean canAnswer, boolean canDraw,
                       boolean canSkip, boolean readyForNext, Long roundWinner, List<Player> players,
                       long inkSequence, List<Segment> segments, boolean canUndo, boolean canRedo, ResultSummary result,
                       List<RoundResult> rounds, List<Guess> guesses, Long drawerUserId, int streak, int bestStreak,
                       Integer roundSeconds) { }
}
