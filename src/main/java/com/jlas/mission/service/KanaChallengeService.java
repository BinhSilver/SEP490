package com.jlas.mission.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.repository.AvatarFrameJdbcRepository.Style;
import com.jlas.game.service.AvatarFrameService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    static final int LEADERBOARD_SIZE = 5;
    static final Duration HINT_COOLDOWN = Duration.ofSeconds(30);

    private final KanaChallengeRepository repository;
    private final UserZoneService zones;
    private final MissionClock clock;
    private final ObjectMapper json;
    private final AvatarFrameService frames;
    public record Reveal(long wordId, int index) {}
    /**
     * Stored in solution_json. Play time is counted per board only while that board is on screen:
     * activeMs is the time already played, resumedAt (epoch millis) is set while the board runs and
     * null while it is paused. hintActiveMs is the play time at the last hint. Millis keep the JSON
     * free of a time module; unknown fields from older rows (lastHintAt) are ignored.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Progress(List<Long> solvedWordIds, List<Reveal> reveals, Long activeMs, Long resumedAt, Long hintActiveMs) {
        public Progress {
            solvedWordIds = solvedWordIds == null ? List.of() : solvedWordIds;
            reveals = reveals == null ? List.of() : reveals;
            activeMs = activeMs == null ? 0L : activeMs;
        }
        public Progress(List<Long> solvedWordIds) { this(solvedWordIds, List.of(), 0L, null, null); }
        public long playedMs(Instant now) {
            return activeMs + (resumedAt == null ? 0 : Math.max(0, now.toEpochMilli() - resumedAt));
        }
        public boolean running() { return resumedAt != null; }
        public Progress resumed(Instant now) {
            return running() ? this : new Progress(solvedWordIds, reveals, activeMs, now.toEpochMilli(), hintActiveMs);
        }
        public Progress paused(Instant now) {
            return running() ? new Progress(solvedWordIds, reveals, playedMs(now), null, hintActiveMs) : this;
        }
        Progress withSolved(List<Long> solved) { return new Progress(solved, reveals, activeMs, resumedAt, hintActiveMs); }
        Progress withHint(List<Reveal> all, Instant now) {
            return new Progress(solvedWordIds, all, activeMs, resumedAt, playedMs(now));
        }
        /** Play time still needed before the next hint opens (zero when it is open). */
        long hintWaitMs(Instant now) {
            long due = (hintActiveMs == null ? 0 : hintActiveMs) + HINT_COOLDOWN.toMillis();
            return Math.max(0, due - playedMs(now));
        }
    }

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
        // Only the board on screen keeps time: pause the learner's other boards of the day, run this one.
        Instant now = clock.now();
        for (Challenge other : repository.findForDay(userId, day)) {
            if (other.id() != challenge.id() && other.completedAt() == null) {
                Progress progress = read(other.solutionJson(), Progress.class);
                if (progress.running()) repository.saveProgress(other.id(), write(progress.paused(now)));
            }
        }
        if (challenge.completedAt() == null) {
            Progress progress = read(challenge.solutionJson(), Progress.class);
            if (!progress.running()) {
                repository.saveProgress(challenge.id(), write(progress.resumed(now)));
                challenge = repository.find(userId, board.id()).orElseThrow();
            }
        }
        return view(challenge, defaultLevel);
    }

    /** Stops this board's clock while the learner is away (tab hidden, page left). */
    @Transactional
    public void pause(Long userId, Long challengeId) {
        repository.lockUser(userId);
        Challenge challenge = repository.findById(userId, challengeId).orElse(null);
        if (challenge == null || challenge.completedAt() != null) return;
        Progress progress = read(challenge.solutionJson(), Progress.class);
        if (progress.running()) repository.saveProgress(challenge.id(), write(progress.paused(clock.now())));
    }

    @Transactional
    public Result submit(Long userId, Attempt attempt) {
        repository.lockUser(userId);
        Challenge challenge = current(userId, attempt.challengeId());
        String defaultLevel = defaultLevel(userId);
        if (challenge.completedAt() != null) return new Result(true, view(challenge, defaultLevel));
        Puzzle puzzle = read(challenge.board().puzzleJson(), Puzzle.class);
        Instant now = clock.now();
        // A move on a paused board means the learner is back: count from now on.
        Progress progress = read(challenge.solutionJson(), Progress.class).resumed(now);
        // Match actual cell IDs against an individually verified word path. Alternative
        // spelling paths cannot consume tiles belonging to an unsolved word and strand the board.
        Solution found = puzzle.solutions().stream().filter(s -> s.path().equals(attempt.path())).findFirst().orElse(null);
        if (found == null) return new Result(false, view(challenge, defaultLevel));
        if (progress.solvedWordIds().contains(found.wordId())) return new Result(true, view(challenge, defaultLevel));
        List<Long> solved = new ArrayList<>(progress.solvedWordIds());
        solved.add(found.wordId());
        Progress updated = progress.withSolved(List.copyOf(solved));
        if (solved.size() == puzzle.words().size()) {
            // The final time is play time on this board only, then the clock stops for good.
            int seconds = (int) Math.min(Integer.MAX_VALUE, updated.playedMs(now) / 1000);
            repository.saveProgress(challenge.id(), write(updated.paused(now)));
            repository.complete(challenge.id(), now, seconds);
        } else {
            repository.saveProgress(challenge.id(), write(updated));
        }
        return new Result(true, view(repository.findById(userId, challenge.id()).orElseThrow(), defaultLevel));
    }

    /**
     * Reveals the kana in the slot the learner picked: any still-empty slot of any unsolved word, so the
     * middle of a long word can be opened first. The first hint opens after 30 seconds of play on this
     * board, each next one 30 seconds later.
     */
    @Transactional
    public Today hint(Long userId, Long challengeId, long wordId, int index) {
        repository.lockUser(userId);
        Challenge challenge = current(userId, challengeId);
        if (challenge.completedAt() != null) throw new ApiException(ErrorCode.KANA_HINT_UNAVAILABLE);
        Puzzle puzzle = read(challenge.board().puzzleJson(), Puzzle.class);
        Instant now = clock.now();
        Progress progress = read(challenge.solutionJson(), Progress.class).resumed(now);
        if (!anyHidden(puzzle, progress)) throw new ApiException(ErrorCode.KANA_HINT_UNAVAILABLE);
        long waitMs = progress.hintWaitMs(now);
        if (waitMs > 0) {
            long seconds = Math.max(1, (waitMs + 999) / 1000);
            throw new ApiException(ErrorCode.KANA_HINT_NOT_READY, "Gợi ý tiếp theo mở sau " + seconds + " giây.");
        }
        Reveal pick = new Reveal(wordId, index);
        boolean open = puzzle.words().stream().anyMatch(w -> w.id() == wordId && index >= 0 && index < w.tokens().size())
                && !progress.solvedWordIds().contains(wordId) && !progress.reveals().contains(pick);
        if (!open) throw new ApiException(ErrorCode.KANA_HINT_UNAVAILABLE, "Ô này đã có chữ. Hãy chọn một ô trống khác.");
        List<Reveal> reveals = new ArrayList<>(progress.reveals());
        reveals.add(pick);
        repository.saveHint(challenge.id(), write(progress.withHint(List.copyOf(reveals), now)));
        return view(repository.findById(userId, challenge.id()).orElseThrow(), defaultLevel(userId));
    }

    /** Whether some unsolved word still has an empty slot a hint could open. */
    private static boolean anyHidden(Puzzle puzzle, Progress progress) {
        return puzzle.words().stream().anyMatch(w -> !progress.solvedWordIds().contains(w.id())
                && progress.reveals().stream().filter(r -> r.wordId() == w.id()).count() < w.tokens().size());
    }

    /** Today's ranking for one board; an untouched board simply has no finishers yet. */
    @Transactional(readOnly = true)
    public Leaderboard leaderboard(Long userId, String requestedLevel) {
        String level = requestedLevel == null || requestedLevel.isBlank() ? defaultLevel(userId) : normalize(requestedLevel);
        LocalDate day = clock.today(zones.zoneOf(userId));
        Board board = repository.findBoard(day, level).orElse(null);
        if (board == null) return new Leaderboard(level, day, 0, List.of(), null);
        List<RankRow> rows = repository.topFinishers(board.id(), LEADERBOARD_SIZE);
        Map<Long, Style> styles = frames.styles(rows.stream().map(RankRow::userId).toList());
        List<Rank> top = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            top.add(rank(i + 1, rows.get(i), userId, styles.getOrDefault(rows.get(i).userId(), Style.NONE)));
        }
        Rank me = top.stream().filter(Rank::me).findFirst().orElse(null);
        if (me == null) {
            Challenge mine = repository.find(userId, board.id()).filter(c -> c.completedAt() != null).orElse(null);
            if (mine != null) me = new Rank(repository.rankOf(board.id(), mine), "Bạn", null, mine.elapsedSeconds(), true,
                    null, null);
        }
        return new Leaderboard(level, day, repository.finisherCount(board.id()), top, me);
    }

    private static Rank rank(int position, RankRow row, Long viewer, Style style) {
        boolean me = row.userId() == viewer;
        String name = row.name() != null ? row.name() : "Học viên #" + row.userId();
        return new Rank(position, name, row.avatarUrl(), row.elapsedSeconds(), me, style.frameCode(), style.nameStyle());
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
        Map<Long, Entry> byId = new HashMap<>();
        puzzle.words().forEach(w -> byId.put(w.id(), w));
        // Only unsolved words need hints; a solved word already shows its full reading.
        List<HintView> hints = progress.reveals().stream()
                .filter(r -> !progress.solvedWordIds().contains(r.wordId()) && byId.containsKey(r.wordId()))
                .map(r -> new HintView(r.wordId(), r.index(), byId.get(r.wordId()).tokens().get(r.index()))).toList();
        Instant now = clock.now();
        long playedMs = c.completedAt() != null && c.elapsedSeconds() != null ? c.elapsedSeconds() * 1000L : progress.playedMs(now);
        Instant nextHint = c.completedAt() != null || !anyHidden(puzzle, progress) ? null
                : clock.toWallClock(now.plusMillis(progress.hintWaitMs(now)));
        String level = c.board().level();
        return new Today(c.id(), puzzle.version(), c.board().date(),
                clock.toWallClock(c.board().date().plusDays(1).atStartOfDay(zones.zoneOf(c.userId())).toInstant()),
                c.startedAt(),
                level, defaultLevel, MIX.equals(level), puzzle.gridSize(), puzzle.cells(),
                puzzle.cells().stream().filter(cell -> cell.kana() == null).map(KanaPuzzle.Cell::id).toList(),
                slots, progress.solvedWordIds(), solvedPaths, c.completedAt() != null, c.elapsedSeconds(),
                hints, nextHint, playedMs, progress.running() && c.completedAt() == null);
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
