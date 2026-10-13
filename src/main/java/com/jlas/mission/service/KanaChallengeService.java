package com.jlas.mission.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.mission.config.MissionClock;
import com.jlas.mission.dto.KanaChallengeDtos.*;
import com.jlas.mission.repository.KanaChallengeRepository;
import com.jlas.mission.repository.KanaChallengeRepository.Board;
import com.jlas.mission.repository.KanaChallengeRepository.Challenge;
import com.jlas.mission.repository.KanaChallengeRepository.RankRow;
import com.jlas.mission.repository.KanaChallengeRepository.Word;
import com.jlas.mission.util.KanaPuzzle;
import com.jlas.mission.util.MultiWordPuzzleGenerator;
import com.jlas.mission.util.MultiWordPuzzleGenerator.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kana Connect: every learner plays the same board for a given local day and level
 * (N5..N1 or MIX). Progress and the timer are per learner; finishers are ranked per board.
 */
@Service
@RequiredArgsConstructor
public class KanaChallengeService {
    public static final String MIX = "MIX";
    static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1", MIX);
    static final int LEADERBOARD_SIZE = 10;

    private final KanaChallengeRepository repository;
    private final UserZoneService zones;
    private final MissionClock clock;
    private final ObjectMapper json;
    public record Progress(List<Long> solvedWordIds) {}

    /** @param requestedLevel N5..N1 or MIX; null opens the learner's learning-path level (MIX without one). */
    @Transactional
    public Today today(Long userId, String requestedLevel) {
        repository.lockUser(userId);
        String defaultLevel = defaultLevel(userId);
        String level = requestedLevel == null || requestedLevel.isBlank() ? defaultLevel : normalize(requestedLevel);
        LocalDate day = clock.today(zones.zoneOf(userId));
        Board board = board(day, level);
        Challenge challenge = repository.find(userId, board.id()).orElse(null);
        if (challenge == null) {
            Entry first = read(board.puzzleJson(), Puzzle.class).words().getFirst();
            repository.create(userId, board, new Word(first.id(), first.expression(), first.reading(), first.meaning(),
                    first.level()), write(new Progress(List.of())), clock.now());
            challenge = repository.find(userId, board.id()).orElseThrow();
        }
        return view(challenge, defaultLevel);
    }

    @Transactional
    public Result submit(Long userId, Attempt attempt) {
        repository.lockUser(userId);
        Challenge challenge = current(userId, attempt.challengeId());
        String defaultLevel = defaultLevel(userId);
        if (challenge.completedAt() != null) return new Result(true, view(challenge, defaultLevel));
        Puzzle puzzle = read(challenge.board().puzzleJson(), Puzzle.class);
        Progress progress = read(challenge.solutionJson(), Progress.class);
        // Match actual cell IDs against an individually verified word path. Alternative
        // spelling paths cannot consume tiles belonging to an unsolved word and strand the board.
        Solution found = puzzle.solutions().stream().filter(s -> s.path().equals(attempt.path())).findFirst().orElse(null);
        if (found == null) return new Result(false, view(challenge, defaultLevel));
        if (progress.solvedWordIds().contains(found.wordId())) return new Result(true, view(challenge, defaultLevel));
        List<Long> solved = new ArrayList<>(progress.solvedWordIds());
        solved.add(found.wordId());
        repository.saveProgress(challenge.id(), write(new Progress(List.copyOf(solved))));
        if (solved.size() == puzzle.words().size()) {
            int seconds = (int) Math.min(Integer.MAX_VALUE, Math.max(0,
                    Duration.between(challenge.startedAt(), clock.now()).getSeconds()));
            repository.complete(challenge.id(), clock.now(), seconds);
        }
        return new Result(true, view(repository.findById(userId, challenge.id()).orElseThrow(), defaultLevel));
    }

    /** Today's ranking for one board; an untouched board simply has no finishers yet. */
    @Transactional(readOnly = true)
    public Leaderboard leaderboard(Long userId, String requestedLevel) {
        String level = requestedLevel == null || requestedLevel.isBlank() ? defaultLevel(userId) : normalize(requestedLevel);
        LocalDate day = clock.today(zones.zoneOf(userId));
        Board board = repository.findBoard(day, level).orElse(null);
        if (board == null) return new Leaderboard(level, day, 0, List.of(), null);
        List<RankRow> rows = repository.topFinishers(board.id(), LEADERBOARD_SIZE);
        List<Rank> top = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) top.add(rank(i + 1, rows.get(i), userId));
        Rank me = top.stream().filter(Rank::me).findFirst().orElse(null);
        if (me == null) {
            Challenge mine = repository.find(userId, board.id()).filter(c -> c.completedAt() != null).orElse(null);
            if (mine != null) me = new Rank(repository.rankOf(board.id(), mine), "Bạn", null, mine.elapsedSeconds(), true);
        }
        return new Leaderboard(level, day, repository.finisherCount(board.id()), top, me);
    }

    private static Rank rank(int position, RankRow row, Long viewer) {
        boolean me = row.userId() == viewer;
        String name = row.name() != null ? row.name() : "Học viên #" + row.userId();
        return new Rank(position, name, row.avatarUrl(), row.elapsedSeconds(), me);
    }

    private String defaultLevel(Long userId) {
        return repository.levelFor(userId).orElse(MIX);
    }

    private static String normalize(String level) {
        String value = level.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(value)) throw new ApiException(ErrorCode.KANA_CHALLENGE_LEVEL_INVALID);
        return value;
    }

    /** The first player of the day creates the shared board; everyone else reuses the stored copy. */
    private Board board(LocalDate day, String level) {
        Board existing = repository.findBoard(day, level).orElse(null);
        if (existing != null) return existing;
        String puzzle = write(generate(day, level));
        try {
            repository.createBoard(day, level, puzzle, clock.now());
        } catch (DuplicateKeyException raced) {
            // Another learner created it concurrently; their committed copy is the shared board.
        }
        return repository.findBoardForUpdate(day, level).orElseThrow();
    }

    private Puzzle generate(LocalDate day, String level) {
        boolean mixed = MIX.equals(level);
        List<Entry> words = repository.words(mixed ? null : level).stream().map(w -> new Entry(w.id(), w.expression(),
                w.reading(), w.meaning(), w.level(), KanaPuzzle.tokenize(w.reading()))).toList();
        // Deterministic per day and level, independent of who opens it first.
        long seed = day.toEpochDay() * 1_000_003L + LEVELS.indexOf(level);
        Puzzle puzzle = null;
        // Daily play always contains at least eight words. Grow the grid for longer readings,
        // never reduce the word count or silently substitute a different JLPT level.
        for (int size = 6; size <= 8 && puzzle == null; size++) {
            puzzle = MultiWordPuzzleGenerator.generate(words, new Limits(size, 8, 15, 5000), seed + size, mixed).orElse(null);
        }
        if (puzzle == null) throw new ApiException(ErrorCode.KANA_CHALLENGE_UNAVAILABLE,
                "Chưa đủ ít nhất 8 từ vựng đã duyệt để tạo bàn chơi " + (mixed ? "trộn cấp độ" : level)
                        + ". Bạn hãy chọn cấp độ khác hoặc quay lại sau nhé.");
        return puzzle;
    }

    private Challenge current(Long userId, Long id) {
        Challenge challenge = repository.findById(userId, id)
                .orElseThrow(() -> new ApiException(ErrorCode.KANA_CHALLENGE_EXPIRED));
        if (!challenge.board().date().equals(clock.today(zones.zoneOf(userId)))) {
            throw new ApiException(ErrorCode.KANA_CHALLENGE_EXPIRED);
        }
        return challenge;
    }

    private Today view(Challenge c, String defaultLevel) {
        Puzzle puzzle = read(c.board().puzzleJson(), Puzzle.class);
        Progress progress = read(c.solutionJson(), Progress.class);
        List<WordSlot> slots = puzzle.words().stream().map(w -> {
            boolean solved = progress.solvedWordIds().contains(w.id());
            return new WordSlot(w.id(), w.tokens().size(), solved ? w.expression() : null,
                    solved ? w.reading() : null, solved ? w.meaning() : null, w.level());
        }).toList();
        List<Solution> solvedPaths = puzzle.solutions().stream().filter(s -> progress.solvedWordIds().contains(s.wordId())).toList();
        String level = c.board().level();
        return new Today(c.id(), puzzle.version(), c.board().date(),
                c.board().date().plusDays(1).atStartOfDay(zones.zoneOf(c.userId())).toInstant(), c.startedAt(),
                level, defaultLevel, MIX.equals(level), puzzle.gridSize(), puzzle.cells(),
                puzzle.cells().stream().filter(cell -> cell.kana() == null).map(KanaPuzzle.Cell::id).toList(),
                slots, progress.solvedWordIds(), solvedPaths, c.completedAt() != null, c.elapsedSeconds());
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new ApiException(ErrorCode.INTERNAL_ERROR); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException ex) { throw new ApiException(ErrorCode.INTERNAL_ERROR); }
    }
}
