package com.jlas.game.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.config.GameProperties;
import com.jlas.game.dto.AirDefenseDtos.AirAttackResult;
import com.jlas.game.dto.AirDefenseDtos.AirAttackView;
import com.jlas.game.dto.AirDefenseDtos.AirConfigView;
import com.jlas.game.dto.AirDefenseDtos.AirKillResult;
import com.jlas.game.dto.AirDefenseDtos.AirPlayerView;
import com.jlas.game.dto.AirDefenseDtos.AirReportRequest;
import com.jlas.game.dto.AirDefenseDtos.AirStartRequest;
import com.jlas.game.dto.AirDefenseDtos.AirStateView;
import com.jlas.game.dto.AirDefenseDtos.AirWordView;
import com.jlas.game.dto.GameDtos.ArenaEvent;
import com.jlas.game.dto.GameDtos.ResultLine;
import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.entity.AirDefenseResult;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.SessionStatus;
import com.jlas.game.runtime.ArenaEventHub;
import com.jlas.game.runtime.ArenaEventType;
import com.jlas.game.runtime.ArenaRateLimiter;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.GameSessionRegistry;
import com.jlas.game.runtime.GameWord;
import com.jlas.game.runtime.LaunchRequest;
import com.jlas.game.runtime.PlayerSeat;
import com.jlas.game.runtime.airdefense.AirDefensePlayer;
import com.jlas.game.runtime.airdefense.AirDefenseSession;
import com.jlas.game.service.AirDefenseService;
import com.jlas.game.service.GameDeckService;
import com.jlas.game.util.GameAnswerMatcher;
import com.jlas.game.util.GameRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Engine Air Defence. Mô phỏng đợt địch chạy ở trình duyệt; server giữ dãy từ chung, kiểm lại từng
 * lần bắn hạ, chuyển đòn combo sang đối thủ và quyết định thắng thua (về 0 HP trước, hoặc hết giờ).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AirDefenseServiceImpl implements AirDefenseService {

    /** Đủ cho khoảng 30 đợt; hết dãy thì client quay vòng. */
    private static final int SEQUENCE_LENGTH = 320;
    /** Client chạy đoạn intro ~3,8 giây trước khi địch xuất hiện. */
    private static final long START_DELAY_MS = 4_000;
    private static final long TIME_GRACE_MS = 2_000;
    /** Luyện tập: thời gian chọn được ở màn cài đặt (0 = không giới hạn). */
    private static final List<Integer> SOLO_DURATIONS = List.of(0, 120, 180, 300);
    private static final List<Integer> ATTACK_MILESTONES = List.of(10, 15, 20);
    private static final int REPORT_PER_MINUTE = 600;
    private static final int MAX_HP_CAP = 1_000;

    private final GameSessionRegistry registry;
    private final GameSessionLifecycle lifecycle;
    private final ArenaEventHub hub;
    private final ArenaRateLimiter rateLimiter;
    private final GameProperties properties;
    private final GameDeckService decks;

    @Override
    public GameType type() {
        return GameType.AIR_DEFENSE;
    }

    // ------------------------------------------------------------------ launch

    @Override
    public GameSession launch(LaunchRequest request) {
        List<GameWord> sequence = buildSequence(request.words());
        int duration = request.options().durationSeconds() == null ? 0 : request.options().durationSeconds();
        Instant now = Instant.now();
        AirDefenseSession session = new AirDefenseSession(request, sequence, duration, now,
                now.plusMillis(START_DELAY_MS));
        for (PlayerSeat seat : request.seats()) {
            session.addPlayer(new AirDefensePlayer(seat));
        }
        // Luyện tập: chờ màn cài đặt, bấm "Bắt đầu" mới chạy (xem start).
        session.setAwaitingStart(!session.isVersus());
        registry.register(session);
        log.info("Air Defence {} bắt đầu: {} người, {} từ, {} giây", session.getSessionId(),
                request.seats().size(), request.words().size(), duration);
        return session;
    }

    /** Xáo từng vòng cả bộ rồi nối lại cho đủ dài; hai người cùng nhận một dãy. */
    private static List<GameWord> buildSequence(List<GameWord> words) {
        List<GameWord> sequence = new ArrayList<>(SEQUENCE_LENGTH + words.size());
        while (sequence.size() < Math.max(SEQUENCE_LENGTH, words.size())) {
            List<GameWord> cycle = new ArrayList<>(words);
            Collections.shuffle(cycle, ThreadLocalRandom.current());
            sequence.addAll(cycle);
        }
        return sequence;
    }

    @Override
    public AirStateView start(String sessionId, long userId, AirStartRequest request) {
        AirDefenseSession session = registry.require(sessionId, AirDefenseSession.class, userId);
        if (!session.withLock(session::isAwaitingStart)) {
            return state(sessionId, userId);
        }
        AirStartRequest r = request == null ? new AirStartRequest(null, null, null, null) : request;
        DeckField question = r.questionField() == null ? session.getQuestionField() : r.questionField();
        DeckField answer = r.answerField() == null ? session.getAnswerField() : r.answerField();
        int duration = r.durationSeconds() == null ? 0 : r.durationSeconds();
        if (!SOLO_DURATIONS.contains(duration)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thời gian không hợp lệ");
        }
        GameDeckService.PickedDeck picked = decks.pickCards(userId, session.getDeckId(), question, answer,
                r.cardIds(), GameRules.AIR_MIN_WORDS, GameRules.gameName(GameType.AIR_DEFENSE));
        List<GameWord> sequence = buildSequence(picked.words());
        boolean started = session.withLock(() -> {
            if (!session.isAwaitingStart() || session.getStatus() != SessionStatus.RUNNING) {
                return false;
            }
            session.useWords(question, answer, picked.words());
            session.restart(sequence, duration, Instant.now().plusMillis(START_DELAY_MS));
            session.setAwaitingStart(false);
            session.touch(Instant.now());
            return true;
        });
        if (started) {
            lifecycle.recorder().updateSettings(session.getMatchId(), settingsSnapshot(session));
        }
        return state(sessionId, userId);
    }

    private static Map<String, Object> settingsSnapshot(AirDefenseSession session) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("durationSeconds", session.getDurationSeconds());
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("setId", session.getDeckId());
        settings.put("setTitle", session.getDeckTitle());
        settings.put("questionField", session.getQuestionField());
        settings.put("answerField", session.getAnswerField());
        settings.put("cardCount", session.getWords().size());
        settings.put("options", options);
        return settings;
    }

    // ------------------------------------------------------------------ actions

    @Override
    public AirStateView state(String sessionId, long userId) {
        AirDefenseSession session = registry.require(sessionId, AirDefenseSession.class, userId);
        return session.withLock(() -> toState(session, userId));
    }

    @Override
    public AirKillResult kill(String sessionId, long userId, String wordId, String rawInput) {
        rateLimiter.check(userId, "air-kill", properties.arena().rateLimit().answerPerMinute());
        AirDefenseSession session = registry.require(sessionId, AirDefenseSession.class, userId);
        return session.withLock(() -> {
            AirDefensePlayer player = session.player(userId);
            GameWord word = session.word(wordId);
            if (session.getStatus() != SessionStatus.RUNNING || session.isAwaitingStart() || player.isFinished()
                    || word == null) {
                return new AirKillResult(false, player.getVerifiedKills());
            }
            session.touch(Instant.now());
            boolean verified = GameAnswerMatcher.matches(session.getAnswerField(), word.answer(), rawInput);
            if (verified) {
                player.verifiedKill();
            } else {
                player.rejectedKill();
            }
            return new AirKillResult(verified, player.getVerifiedKills());
        });
    }

    @Override
    public AirStateView report(String sessionId, long userId, AirReportRequest request) {
        rateLimiter.check(userId, "air-report", REPORT_PER_MINUTE);
        AirDefenseSession session = registry.require(sessionId, AirDefenseSession.class, userId);
        ResultSummary summary = session.withLock(() -> {
            AirDefensePlayer player = session.player(userId);
            if (session.getStatus() != SessionStatus.RUNNING || session.isAwaitingStart() || player.isFinished()) {
                return null;
            }
            Instant now = Instant.now();
            session.touch(now);
            apply(player, request);
            if (player.getHp() <= 0) {
                player.finish(now, false);
                return finishIfDone(session, now);
            }
            return null;
        });
        broadcastProgress(session, userId);
        if (summary != null) {
            complete(session, summary);
        }
        return state(sessionId, userId);
    }

    @Override
    public AirAttackResult attack(String sessionId, long userId, int milestone) {
        rateLimiter.check(userId, "air-attack", 30);
        AirDefenseSession session = registry.require(sessionId, AirDefenseSession.class, userId);
        if (!session.isVersus()) {
            return new AirAttackResult(false, "Chỉ gửi được trùm trong trận đấu tay đôi");
        }
        if (!ATTACK_MILESTONES.contains(milestone)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Mốc combo không hợp lệ");
        }
        AirAttackView attack = session.withLock(() -> {
            AirDefensePlayer player = session.player(userId);
            AirDefensePlayer opponent = session.opponentOf(userId);
            if (session.getStatus() != SessionStatus.RUNNING || player.isFinished() || opponent == null
                    || opponent.isFinished() || !player.useMilestone(milestone)) {
                return null;
            }
            List<String> ids = session.getSequence();
            String wordId = ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
            return new AirAttackView(UUID.randomUUID().toString(), userId, player.getSeat().displayName(),
                    milestone, wordView(wordId, session.word(wordId)), milestone >= 20 ? 4 : 3);
        });
        if (attack == null) {
            return new AirAttackResult(false, "Con trùm này đã được gửi rồi");
        }
        AirDefensePlayer opponent = session.withLock(() -> session.opponentOf(userId));
        hub.send(opponent.getUserId(), ArenaEvent.of(ArenaEventType.AIR_ATTACK, session.getRoomId(),
                session.getSessionId(), attack));
        return new AirAttackResult(true, "Đã gửi một con trùm sang " + opponent.getSeat().displayName());
    }

    @Override
    public AirStateView finish(String sessionId, long userId, AirReportRequest finalReport, boolean defeated) {
        AirDefenseSession session = registry.require(sessionId, AirDefenseSession.class, userId);
        ResultSummary summary = session.withLock(() -> {
            AirDefensePlayer player = session.player(userId);
            if (session.getStatus().isTerminal() || session.isAwaitingStart() || player.isFinished()) {
                return null;
            }
            Instant now = Instant.now();
            if (finalReport != null) {
                apply(player, finalReport);
            }
            if (defeated) {
                player.setHp(0);
                player.finish(now, false);
            } else if (session.isVersus()) {
                // Trận đối kháng chỉ kết thúc sớm khi về 0 HP; muốn dừng thì dùng "rời trận".
                return null;
            } else {
                player.finish(now, true);
            }
            return finishIfDone(session, now);
        });
        broadcastProgress(session, userId);
        if (summary != null) {
            complete(session, summary);
        }
        return state(sessionId, userId);
    }

    private static void apply(AirDefensePlayer player, AirReportRequest request) {
        int maxHp = clamp(request.maxHp() == null ? player.getMaxHp() : request.maxHp(), 1, MAX_HP_CAP);
        int hp = clamp(request.hp() == null ? player.getHp() : request.hp(), 0, maxHp);
        long score = Math.max(0, request.score() == null ? player.getScore() : request.score());
        int combo = Math.max(0, request.combo() == null ? player.getCombo() : request.combo());
        int bestCombo = Math.max(combo, request.bestCombo() == null ? player.getBestCombo() : request.bestCombo());
        int wave = Math.max(1, request.wave() == null ? player.getWave() : request.wave());
        int wrong = Math.max(0, request.wrongInputs() == null ? player.getWrongInputs() : request.wrongInputs());
        player.applyReport(hp, maxHp, score, combo, bestCombo, wave, wrong);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ----------------------------------------------------------------- finish

    /** Gọi trong lock. Đối kháng: một người về 0 HP (hoặc rời trận) là kết thúc. */
    private ResultSummary finishIfDone(AirDefenseSession session, Instant now) {
        if (session.getStatus().isTerminal()) {
            return null;
        }
        List<AirDefensePlayer> players = session.orderedPlayers();
        boolean anyDown = players.stream().anyMatch(player -> player.isFinished() && !player.isAlive());
        boolean allFinished = players.stream().allMatch(AirDefensePlayer::isFinished);
        boolean timeUp = session.getEndsAt() != null && !now.isBefore(session.getEndsAt());
        if (!(allFinished || timeUp || (session.isVersus() && anyDown))) {
            return null;
        }
        for (AirDefensePlayer player : players) {
            player.finish(now, player.getHp() > 0 && !player.isForfeited());
        }
        session.setStatus(SessionStatus.FINISHED);
        session.setFinishedAt(now);
        ResultSummary summary = summarize(session, now);
        session.setSummary(summary);
        return summary;
    }

    private static ResultSummary summarize(AirDefenseSession session, Instant now) {
        Comparator<AirDefensePlayer> order = Comparator
                .comparingInt((AirDefensePlayer player) -> player.isForfeited() ? 1 : 0)
                .thenComparingInt(player -> player.isAlive() ? 0 : 1)
                .thenComparing(AirDefensePlayer::getHp, Comparator.reverseOrder())
                .thenComparing(AirDefensePlayer::getScore, Comparator.reverseOrder());
        List<AirDefensePlayer> ranked = session.orderedPlayers().stream().sorted(order).toList();
        Long winner = null;
        boolean draw = false;
        if (session.isVersus() && ranked.size() > 1) {
            AirDefensePlayer first = ranked.get(0);
            AirDefensePlayer second = ranked.get(1);
            draw = !first.isForfeited() && !second.isForfeited() && order.compare(first, second) == 0;
            winner = draw || first.isForfeited() ? null : first.getUserId();
        } else if (!ranked.isEmpty() && ranked.get(0).isAlive()) {
            winner = ranked.get(0).getUserId();
        }
        List<ResultLine> lines = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            AirDefensePlayer player = ranked.get(i);
            String detail = "Đợt " + player.getWave() + " · máu " + player.getHp() + "/" + player.getMaxHp()
                    + " · " + player.getVerifiedKills() + " lần bắn trúng · combo " + player.getBestCombo()
                    + (player.isForfeited() ? " · rời trận" : "");
            lines.add(new ResultLine(player.getUserId(), player.getSeat().displayName(), draw ? 1 : i + 1,
                    player.getScore(), detail, winner != null && winner == player.getUserId()));
        }
        return new ResultSummary(session.getSessionId(), GameType.AIR_DEFENSE, session.getMode(), winner, draw,
                lines, now.toEpochMilli());
    }

    private void complete(AirDefenseSession session, ResultSummary summary) {
        Set<Long> forfeited = new HashSet<>();
        List<AirPlayerView> players = session.withLock(() -> {
            session.orderedPlayers().stream().filter(AirDefensePlayer::isForfeited)
                    .forEach(player -> forfeited.add(player.getUserId()));
            return session.orderedPlayers().stream().map(player -> playerView(session, player)).toList();
        });
        recordResults(session, summary);
        lifecycle.completed(session, summary, players, forfeited);
        log.info("Air Defence {} kết thúc, thắng: {}", session.getSessionId(), summary.winnerUserId());
    }

    private void recordResults(AirDefenseSession session, ResultSummary summary) {
        try {
            LocalDateTime finishedAt = LocalDateTime.ofInstant(
                    session.getFinishedAt() == null ? Instant.now() : session.getFinishedAt(), ZoneId.systemDefault());
            long durationMs = Math.max(0, Duration.between(session.getStartAt(),
                    session.getFinishedAt() == null ? Instant.now() : session.getFinishedAt()).toMillis());
            List<AirDefensePlayer> players = session.withLock(session::orderedPlayers);
            for (AirDefensePlayer player : players) {
                int correct = player.getVerifiedKills();
                int incorrect = player.getWrongInputs() + player.getRejectedKills();
                int answered = correct + incorrect;
                boolean won = summary.winnerUserId() != null && summary.winnerUserId() == player.getUserId();
                String outcome = player.isForfeited() ? "FORFEIT"
                        : session.isVersus() ? (summary.draw() ? "DRAW" : won ? "WIN" : "LOSE")
                        : player.isAlive() ? "SURVIVED" : "DEFEATED";
                lifecycle.recorder().recordAirDefense(AirDefenseResult.builder()
                        .sessionId(session.getSessionId())
                        .match(lifecycle.recorder().matchRef(session.getMatchId()))
                        .roomId(session.getRoomId())
                        .user(lifecycle.recorder().userRef(player.getUserId()))
                        .playMode(session.getMode().name())
                        .objective(session.isVersus() ? "VERSUS" : "ENDLESS")
                        .difficulty("STANDARD")
                        .answerMode(session.getQuestionField().name() + "_" + session.getAnswerField().name())
                        .jlptLevel("CUSTOM")
                        .outcome(outcome)
                        .hpRemaining(Math.max(0, player.getHp()))
                        .score((int) Math.min(Integer.MAX_VALUE, player.getScore()))
                        .questionsAnswered(answered)
                        .correctAnswers(correct)
                        .incorrectAnswers(incorrect)
                        .accuracyPercent(answered == 0 ? 0 : (int) Math.round(correct * 100.0 / answered))
                        .bestCombo(player.getBestCombo())
                        .durationMs(durationMs)
                        .ranked(session.isVersus())
                        .winner(won)
                        .finishedAt(finishedAt)
                        .build());
            }
        } catch (RuntimeException ex) {
            log.warn("Không lưu được kết quả Air Defence {}: {}", session.getSessionId(), ex.getMessage());
        }
    }

    // ------------------------------------------------------------ engine hooks

    @Override
    public void forfeit(GameSession raw, long userId) {
        if (!(raw instanceof AirDefenseSession session)) {
            return;
        }
        ResultSummary summary = session.withLock(() -> {
            AirDefensePlayer player = session.player(userId);
            if (player == null || session.getStatus().isTerminal()) {
                return null;
            }
            Instant now = Instant.now();
            player.setForfeited(true);
            player.finish(now, false);
            if (!session.isVersus()) {
                return finishIfDone(session, now);
            }
            // Đối kháng: người còn lại thắng ngay.
            for (AirDefensePlayer other : session.orderedPlayers()) {
                other.finish(now, !other.isForfeited() && other.getHp() > 0);
            }
            return finishIfDone(session, now);
        });
        if (summary != null) {
            complete(session, summary);
        }
    }

    @Override
    public void presenceChanged(GameSession session, long userId, boolean online) {
        boolean changed = session.withLock(() -> session.setOnline(userId, online));
        if (changed) {
            lifecycle.presence(session, userId, online);
        }
    }

    @Override
    public void tick(Instant now) {
        for (AirDefenseSession session : registry.all(AirDefenseSession.class)) {
            if (session.getEndsAt() == null) {
                continue;
            }
            ResultSummary summary = session.withLock(() ->
                    session.getStatus() == SessionStatus.RUNNING && !session.isAwaitingStart()
                            && now.isAfter(session.getEndsAt().plusMillis(TIME_GRACE_MS))
                            ? finishIfDone(session, now)
                            : null);
            if (summary != null) {
                complete(session, summary);
            }
        }
    }

    @Override
    public void abort(GameSession raw) {
        boolean changed = raw.withLock(() -> {
            if (raw.getStatus().isTerminal()) {
                return false;
            }
            raw.setStatus(SessionStatus.ABORTED);
            raw.setFinishedAt(Instant.now());
            return true;
        });
        if (changed) {
            lifecycle.aborted(raw);
        }
    }

    // -------------------------------------------------------------------- views

    private void broadcastProgress(AirDefenseSession session, long userId) {
        AirPlayerView view = session.withLock(() -> playerView(session, session.player(userId)));
        lifecycle.broadcast(session, ArenaEventType.AIR_PROGRESS, view);
    }

    private AirStateView toState(AirDefenseSession session, long userId) {
        AirConfigView config = new AirConfigView(session.getDurationSeconds(), session.getQuestionField(),
                session.getAnswerField(), session.getQuestionField().label(), session.getAnswerField().label(),
                session.isVersus(), ATTACK_MILESTONES);
        List<AirWordView> words = new ArrayList<>(session.getSequence().size());
        for (Map.Entry<String, GameWord> entry : session.getWordsById().entrySet()) {
            words.add(wordView(entry.getKey(), entry.getValue()));
        }
        List<AirPlayerView> players = session.orderedPlayers().stream()
                .map(player -> playerView(session, player)).toList();
        return new AirStateView(session.getSessionId(), session.getMode(), session.getRoomId(), session.getStatus(),
                session.getDeckId(), session.getDeckTitle(), config, words, userId, players,
                session.getStartAt().toEpochMilli(),
                session.getEndsAt() == null ? null : session.getEndsAt().toEpochMilli(), session.getSummary(),
                session.isAwaitingStart(), System.currentTimeMillis());
    }

    private static AirWordView wordView(String id, GameWord word) {
        return new AirWordView(id, word.prompt(), word.answer(), word.kanji(), word.reading(), word.meaning(),
                word.hanViet());
    }

    private static AirPlayerView playerView(AirDefenseSession session, AirDefensePlayer player) {
        PlayerSeat seat = player.getSeat();
        return new AirPlayerView(seat.userId(), seat.displayName(), seat.avatarUrl(), seat.slot(), player.getHp(),
                player.getMaxHp(), player.getScore(), player.getCombo(), player.getBestCombo(), player.getWave(),
                player.getVerifiedKills(), player.getWrongInputs(), player.isAlive(), player.isFinished(),
                session.isOnline(seat.userId()));
    }
}
