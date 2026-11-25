package com.jlas.game.runtime.puzzle;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.util.RomajiKana;
import com.jlas.game.dto.GameDtos.ResultLine;
import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.dto.PuzzleDtos.InkAction;
import com.jlas.game.dto.PuzzleDtos.InkDelta;
import com.jlas.game.dto.PuzzleDtos.Player;
import com.jlas.game.dto.PuzzleDtos.View;
import com.jlas.game.dto.PuzzleDtos.Word;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.SessionStatus;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.LaunchRequest;
import com.jlas.game.runtime.PlayerSeat;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Trạng thái lượt. Engine giữ lock của phiên khi truy cập hoặc cập nhật. */
public final class PuzzleSession extends GameSession {
    private static final int HINT_SECONDS = 30;
    private static final int RIDDLE_SECONDS = 30;
    private static final int REVEAL_SECONDS = 7;

    private final String jlpt;
    private final String map;
    private final List<PuzzleContent.Entry> entries;
    private final int firstDrawer;
    private final Map<Long, Integer> scores = new HashMap<>();
    private final Set<Long> solved = new HashSet<>();
    private final Set<Long> skipVotes = new HashSet<>();
    private final Set<Long> ready = new HashSet<>();
    private final PuzzleDrawing board = new PuzzleDrawing();
    private int index;
    private int visibleHintCount;
    private long version;
    private Long roundWinner;
    private Instant roundStarted;
    private Instant revealedAt;

    public PuzzleSession(GameType type, LaunchRequest request, Instant now, List<PuzzleContent.Entry> entries,
                         String map, int firstDrawer) {
        super(type, request, now);
        this.jlpt = request.jlpt();
        this.entries = List.copyOf(entries);
        this.map = map;
        this.firstDrawer = firstDrawer;
        this.roundStarted = now;
    }

    public long version() { return version; }

    public int round() { return index + 1; }

    public void advance(Instant now) {
        if (getStatus().isTerminal()) return;
        if (revealedAt != null) {
            if (!isDrawing() && isVersus() && !now.isBefore(revealedAt.plusSeconds(REVEAL_SECONDS))) next(now);
            return;
        }
        Instant deadline = deadline();
        if (deadline != null && !now.isBefore(deadline)) {
            reveal(now);
            return;
        }
        int opened = (int) Math.min(entry().hints().size(), Math.max(0,
                Duration.between(roundStarted, now).toSeconds() / HINT_SECONDS));
        if (opened > visibleHintCount) {
            visibleHintCount = opened;
            version++;
        }
    }

    public boolean answer(long userId, int round, String supplied, Instant now) {
        requireRound(round);
        if (!canAnswer(userId)) throw unavailable();
        touch(now);
        String input = Normalizer.normalize(supplied, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", "");
        if (input.matches("[A-Za-z'-]+")) input = RomajiKana.toHiragana(input.toLowerCase(Locale.ROOT));
        String kana = foldKana(input);
        if (entry().accepted().stream().noneMatch(answer -> foldKana(answer).equals(kana))) return false;
        solved.add(userId);
        version++;
        if (getGameType() == GameType.SPHINX_RIDDLE) {
            scores.merge(userId, isVersus() && now.isBefore(roundStarted.plusSeconds(10)) ? 3 : 2, Integer::sum);
            if (solved.size() == getSeats().size()) reveal(now);
        } else {
            if (!isDrawing()) {
                roundWinner = userId;
                scores.merge(userId, 1, Integer::sum);
            }
            reveal(now);
        }
        return true;
    }

    private static String foldKana(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", "")
                .codePoints().map(c -> c >= 'ァ' && c <= 'ヶ' ? c - 0x60 : c)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    public void skip(long userId, int round, Instant now) {
        requireRound(round);
        if (!canSkip(userId)) throw unavailable();
        touch(now);
        skipVotes.add(userId);
        version++;
        if (isDrawing() || skipVotes.size() == getSeats().size()) reveal(now);
    }

    public void ready(long userId, int round, Instant now) {
        requireRound(round);
        if (revealedAt == null) throw unavailable();
        touch(now);
        if (ready.add(userId)) version++;
        if (ready.size() == getSeats().size()) next(now);
    }

    private void reveal(Instant now) {
        revealedAt = now;
        version++;
        touch(now);
    }

    private void next(Instant now) {
        if (round() == entries.size()) {
            finish(now, null);
            return;
        }
        index++;
        roundStarted = now;
        revealedAt = null;
        roundWinner = null;
        visibleHintCount = 0;
        solved.clear();
        ready.clear();
        skipVotes.clear();
        board.reset();
        version++;
        touch(now);
    }

    public InkDelta ink(long userId, InkAction action, Instant now) {
        requireRound(action.round());
        if (!canDraw(userId)) throw unavailable();
        InkDelta delta = board.apply(action);
        touch(now);
        return delta;
    }

    public boolean canReadImage(int round, String image) {
        return round == round() && images().contains(image);
    }

    public void finish(Instant now, Long forfeitingUser) {
        if (getStatus().isTerminal()) return;
        List<PlayerSeat> ranked = getSeats().stream()
                .sorted(Comparator.comparingInt((PlayerSeat seat) -> score(seat.userId())).reversed())
                .toList();
        Long winner = null;
        boolean tied = false;
        if (isVersus() && !isDrawing()) {
            if (forfeitingUser != null) {
                winner = ranked.stream().map(PlayerSeat::userId).filter(id -> !id.equals(forfeitingUser))
                        .findFirst().orElse(null);
            } else {
                tied = score(ranked.get(0).userId()) == score(ranked.get(1).userId());
                if (!tied) winner = ranked.getFirst().userId();
            }
        }
        List<ResultLine> lines = new ArrayList<>();
        if (!isDrawing()) {
            Long winningSeat = winner;
            boolean draw = tied;
            for (PlayerSeat seat : ranked) {
                boolean won = winningSeat != null && winningSeat == seat.userId();
                lines.add(new ResultLine(seat.userId(), seat.displayName(), draw || won || !isVersus() ? 1 : 2,
                        score(seat.userId()), null, won));
            }
        }
        setSummary(new ResultSummary(getSessionId(), getGameType(), getMode(), winner, tied, List.copyOf(lines), now.toEpochMilli()));
        setStatus(SessionStatus.FINISHED);
        setFinishedAt(now);
        version++;
    }

    public void abort(Instant now) {
        if (getStatus().isTerminal()) return;
        setStatus(SessionStatus.ABORTED);
        setFinishedAt(now);
        version++;
    }

    public void presence(long userId, boolean online) {
        if (!getStatus().isTerminal() && setOnline(userId, online)) version++;
    }

    public View view(long userId, Instant now) {
        PuzzleContent.Entry entry = entry();
        boolean canDraw = canDraw(userId);
        boolean wordVisible = revealedAt != null || canDraw;
        Word word = wordVisible ? new Word(entry.answer(), entry.reading(), entry.meaning(), entry.explanation()) : null;
        List<Player> players = getSeats().stream().map(seat -> new Player(seat.userId(), seat.displayName(),
                seat.avatarUrl(), isOnline(seat.userId()), score(seat.userId()), solved.contains(seat.userId()))).toList();
        Instant nextHint = revealedAt == null && !getStatus().isTerminal() && visibleHintCount < entry.hints().size()
                ? roundStarted.plusSeconds((long) (visibleHintCount + 1) * HINT_SECONDS) : null;
        List<String> hints = entry.hints().subList(0, visibleHintCount).stream().map(PuzzleContent.Hint::text)
                .filter(value -> value != null).toList();
        String phase = getStatus().isTerminal() ? getStatus().name() : revealedAt == null ? "PLAYING" : "REVEALED";
        return new View(getGameType(), jlpt, map, round(), entries.size(), phase, version, now.toEpochMilli(),
                millis(nextHint), revealedAt == null && !getStatus().isTerminal() ? millis(deadline()) : null,
                images(), hints, entry.prompt(), word, entry.reading().codePointCount(0, entry.reading().length()),
                canAnswer(userId), canDraw, canSkip(userId), ready.contains(userId),
                roundWinner, players, board.sequence(), board.segments(), canDraw && board.canUndo(), canDraw && board.canRedo(), getSummary());
    }

    private List<String> images() {
        List<String> images = new ArrayList<>(entry().images());
        entry().hints().subList(0, visibleHintCount).stream().map(PuzzleContent.Hint::image)
                .filter(value -> value != null).forEach(images::add);
        return images;
    }

    private boolean canAnswer(long userId) {
        return activeRound() && !solved.contains(userId) && (!isDrawing() || !canDraw(userId));
    }

    private boolean canDraw(long userId) {
        return isDrawing() && activeRound() && getSeats().get((firstDrawer + index) % 2).userId() == userId;
    }

    private boolean canSkip(long userId) {
        return activeRound() && !skipVotes.contains(userId)
                && (!isVersus() || isDrawing() || getGameType() == GameType.PICTURE_REBUS
                && visibleHintCount == entry().hints().size());
    }

    private boolean activeRound() { return revealedAt == null && !getStatus().isTerminal(); }

    private boolean isDrawing() { return getGameType() == GameType.DRAW_GUESS; }

    private int score(long userId) { return scores.getOrDefault(userId, 0); }

    private PuzzleContent.Entry entry() { return entries.get(index); }

    private Instant deadline() {
        return isVersus() && getGameType() == GameType.SPHINX_RIDDLE ? roundStarted.plusSeconds(RIDDLE_SECONDS) : null;
    }

    private static Long millis(Instant instant) { return instant == null ? null : instant.toEpochMilli(); }

    private void requireRound(int round) {
        if (round != round() || getStatus().isTerminal()) throw unavailable();
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.CONFLICT, "Lượt này không còn nhận thao tác");
    }
}
