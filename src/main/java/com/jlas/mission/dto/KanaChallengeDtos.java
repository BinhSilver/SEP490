package com.jlas.mission.dto;

import com.jlas.mission.util.KanaPuzzle.Cell;
import com.jlas.mission.util.MultiWordPuzzleGenerator.Solution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class KanaChallengeDtos {
    private KanaChallengeDtos() {}
    public record WordSlot(long id, int kanaCount, String expression, String reading, String meaning, String jlptLevel) {}
    /**
     * jlptLevel is the shared board's level (N5..N1 or MIX); defaultLevel is the learner's
     * learning-path level (MIX without a path), which the page opens first.
     */
    public record Today(long id, int version, LocalDate date, Instant expiresAt, Instant startedAt,
                        String jlptLevel, String defaultLevel, boolean mixedLevels, int gridSize, List<Cell> cells,
                        List<Integer> blockedCells, List<WordSlot> words, List<Long> solvedWordIds,
                        List<Solution> solvedPaths, boolean completed, Integer elapsedSeconds,
                        List<HintView> hints, Instant nextHintAt, long playedMs, boolean running) {}
    /** One revealed kana of an unsolved word: slot index within the word and its kana. */
    public record HintView(long wordId, int index, String kana) {}
    public record HintRequest(@NotNull Long challengeId) {}
    /** The empty slot to open: a word on the board and the kana position inside it (0-based). */
    public record HintPick(@NotNull Long challengeId, @NotNull Long wordId, @NotNull @Min(0) Integer index) {}
    public record Attempt(@NotNull Long challengeId,
                          @NotNull @Size(min = 2, max = 12) List<@NotNull Integer> path) {}
    public record Result(boolean correct, Today challenge) {}
    /** avatarFrame/nameStyle: khung avatar + màu tên người chơi đang dùng (null = không có). */
    public record Rank(int rank, String name, String avatarUrl, int elapsedSeconds, boolean me,
                       String avatarFrame, String nameStyle) {}
    /** Today's finishers of one board, fastest first; me is set once the viewer has finished it. */
    public record Leaderboard(String level, LocalDate date, int finishers, List<Rank> top, Rank me) {}
}
