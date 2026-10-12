package com.jlas.game.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.config.GameProperties;
import com.jlas.game.dto.GameDtos.ResultLine;
import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.dto.GameDtos.DeckCardView;
import com.jlas.game.dto.NekoDtos.NekoActionResult;
import com.jlas.game.dto.NekoDtos.NekoChoiceView;
import com.jlas.game.dto.NekoDtos.NekoConfigView;
import com.jlas.game.dto.NekoDtos.NekoMistakeView;
import com.jlas.game.dto.NekoDtos.NekoPlayerView;
import com.jlas.game.dto.NekoDtos.NekoRoundView;
import com.jlas.game.dto.NekoDtos.NekoStartRequest;
import com.jlas.game.dto.NekoDtos.NekoStateView;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.PlayMode;
import com.jlas.game.enums.SessionStatus;
import com.jlas.game.runtime.ArenaEventType;
import com.jlas.game.runtime.ArenaRateLimiter;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.GameSessionRegistry;
import com.jlas.game.runtime.GameWord;
import com.jlas.game.runtime.LaunchRequest;
import com.jlas.game.runtime.PlayerSeat;
import com.jlas.game.runtime.neko.NekoPlayer;
import com.jlas.game.runtime.neko.NekoRound;
import com.jlas.game.runtime.neko.NekoSession;
import com.jlas.game.service.GameDeckService;
import com.jlas.game.service.NekoCatchService;
import com.jlas.game.util.GameRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Engine Neko Catch. Client chỉ báo "mèo chạm con cá X" hoặc "cá chạm đất"; server giữ đáp án đúng,
 * cộng điểm, trừ tim, tính giờ và xếp hạng. Đối kháng: hai người cùng dãy lượt, chơi song song.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NekoCatchServiceImpl implements NekoCatchService {

    /** Đủ cho 120 giây chơi nhanh nhất; hết dãy thì quay vòng. */
    private static final int ROUND_COUNT = 80;
    private static final double BONUS_CHANCE = 0.32;
    private static final List<String> BONUSES = List.of("GOLDEN_FISH", "CLOCK", "SHIELD", "FREEZE");
    /** Client đếm 3-2-1 trước khi cá bắt đầu rơi. */
    private static final long START_DELAY_MS = 3_000;
    /** Giới hạn cho cài đặt luyện tập tự chọn ở màn chuẩn bị. */
    private static final int MAX_LIVES = 5;
    private static final int MAX_CHOICES = 7;
    /** Bù độ trễ mạng khi so với đồng hồ server. */
    private static final long TIME_GRACE_MS = 1_500;
    /** Cá thưởng có thể còn đang rơi sau khi đã sang vài lượt mới. */
    private static final int BONUS_LOOKBACK = 4;
    private static final long GOLDEN_FISH_POINTS = 50;
    private static final long CLOCK_BONUS_MS = 5_000;
    private static final long FREEZE_BONUS_MS = 2_000;

    private enum Action { CATCH, MISS, BONUS }

    private record Outcome(NekoActionResult result, boolean progressChanged, ResultSummary summary) {
    }

    private final GameSessionRegistry registry;
    private final GameDeckService decks;
    private final GameSessionLifecycle lifecycle;
    private final ArenaRateLimiter rateLimiter;
    private final GameProperties properties;

    @Override
    public GameType type() {
        return GameType.NEKO_CATCH;
    }

    // ------------------------------------------------------------------ launch

    @Override
    public GameSession launch(LaunchRequest request) {
        NekoSession.Config config = config(request.options().difficulty(), request.options().durationSeconds());
        List<String> answerPool = request.words().stream().map(GameWord::answer).distinct().toList();
        List<NekoRound> rounds = buildRounds(request.words(), answerPool, config, ThreadLocalRandom.current());
        Instant now = Instant.now();
        Instant startAt = now.plusMillis(START_DELAY_MS);
        NekoSession session = new NekoSession(request, config, rounds, now, startAt);
        // Luyện tập chờ người chơi bấm "Bắt đầu"; đối kháng đếm ngược luôn để hai bên vào cùng lúc.
        session.setAwaitingStart(request.mode() == PlayMode.SOLO);
        Instant deadline = startAt.plusSeconds(config.durationSeconds());
        for (PlayerSeat seat : request.seats()) {
            session.addPlayer(new NekoPlayer(seat, config.lives(), deadline));
        }
        registry.register(session);
        log.info("Neko Catch {} bắt đầu: {} người, {} từ, {}", session.getSessionId(), request.seats().size(),
                request.words().size(), config.difficulty());
        return session;
    }

    private static NekoSession.Config config(String difficulty, Integer durationSeconds) {
        int duration = durationSeconds == null ? 60 : durationSeconds;
        return switch (difficulty == null ? "NORMAL" : difficulty) {
            case "EASY" -> new NekoSession.Config("EASY", 5, 2, duration);
            case "HARD" -> new NekoSession.Config("HARD", 2, 6, duration);
            default -> new NekoSession.Config("NORMAL", 3, 4, duration);
        };
    }

    /**
     * Dãy lượt: xáo các thẻ trong trận theo từng vòng; cá sai lấy từ {@code answerPool} (đáp án của các thẻ khác
     * cùng bộ, kể cả thẻ không được chọn làm câu hỏi).
     */
    static List<NekoRound> buildRounds(List<GameWord> words, List<String> answerPool, NekoSession.Config config,
                                       Random random) {
        int total = Math.max(ROUND_COUNT, words.size());
        List<GameWord> order = new ArrayList<>(total + words.size());
        while (order.size() < total) {
            List<GameWord> cycle = new ArrayList<>(words);
            Collections.shuffle(cycle, random);
            if (!order.isEmpty() && cycle.size() > 1 && cycle.get(0).equals(order.get(order.size() - 1))) {
                Collections.swap(cycle, 0, cycle.size() - 1);
            }
            order.addAll(cycle);
        }
        List<NekoRound> rounds = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            GameWord word = order.get(index);
            List<String> pool = new ArrayList<>(answerPool);
            pool.removeIf(answer -> answer.equalsIgnoreCase(word.answer()));
            Collections.shuffle(pool, random);
            List<String> texts = new ArrayList<>();
            texts.add(word.answer());
            texts.addAll(pool.subList(0, Math.min(config.distractors(), pool.size())));
            Collections.shuffle(texts, random);

            String roundId = "r" + index;
            List<NekoChoiceView> choices = new ArrayList<>(texts.size());
            String correctId = null;
            for (int k = 0; k < texts.size(); k++) {
                String choiceId = roundId + "c" + k;
                choices.add(new NekoChoiceView(choiceId, texts.get(k)));
                if (correctId == null && texts.get(k).equals(word.answer())) {
                    correctId = choiceId;
                }
            }
            String bonus = index > 0 && random.nextDouble() < BONUS_CHANCE
                    ? BONUSES.get(random.nextInt(BONUSES.size()))
                    : null;
            rounds.add(new NekoRound(roundId, index, word, List.copyOf(choices), correctId, bonus));
        }
        return rounds;
    }

    // ------------------------------------------------------------------- state

    @Override
    public NekoStateView state(String sessionId, long userId) {
        return state(sessionId, userId, MAX_CHOICES);
    }

    @Override
    public NekoStateView state(String sessionId, long userId, int choiceLimit) {
        if (choiceLimit < 2 || choiceLimit > MAX_CHOICES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Số cá mỗi lượt từ 2 đến " + MAX_CHOICES);
        }
        NekoSession session = registry.require(sessionId, NekoSession.class, userId);
        return session.withLock(() -> toState(session, userId, choiceLimit));
    }

    @Override
    public NekoActionResult catchChoice(String sessionId, long userId, String roundId, String choiceId) {
        return act(sessionId, userId, roundId, Action.CATCH, choiceId);
    }

    @Override
    public NekoActionResult miss(String sessionId, long userId, String roundId) {
        return act(sessionId, userId, roundId, Action.MISS, null);
    }

    @Override
    public NekoActionResult bonus(String sessionId, long userId, String roundId) {
        return act(sessionId, userId, roundId, Action.BONUS, null);
    }

    @Override
    public NekoStateView timeUp(String sessionId, long userId) {
        NekoSession session = registry.require(sessionId, NekoSession.class, userId);
        Outcome outcome = session.withLock(() -> {
            NekoPlayer player = session.player(userId);
            Instant now = Instant.now();
            if (session.getStatus() != SessionStatus.RUNNING || session.isAwaitingStart() || player.isFinished()
                    || now.isBefore(player.getDeadline().minusMillis(TIME_GRACE_MS))) {
                return null;
            }
            player.finish(NekoPlayer.FINISH_TIME, now);
            return new Outcome(null, true, finishIfDone(session, now));
        });
        publish(session, outcome, userId);
        return state(sessionId, userId);
    }

    @Override
    public NekoStateView setPaused(String sessionId, long userId, boolean paused) {
        NekoSession session = registry.require(sessionId, NekoSession.class, userId);
        return session.withLock(() -> {
            if (session.isVersus()) {
                throw new ApiException(ErrorCode.CONFLICT, "Trận đối kháng không thể tạm dừng");
            }
            if (session.isAwaitingStart()) {
                return toState(session, userId);
            }
            Instant now = Instant.now();
            if (paused && session.getStatus() == SessionStatus.RUNNING) {
                session.setStatus(SessionStatus.PAUSED);
                session.setPausedAt(now);
            } else if (!paused && session.getStatus() == SessionStatus.PAUSED) {
                Duration away = Duration.between(session.getPausedAt(), now);
                for (NekoPlayer player : session.orderedPlayers()) {
                    player.setDeadline(player.getDeadline().plus(away));
                }
                session.setPausedAt(null);
                session.setStatus(SessionStatus.RUNNING);
            }
            session.touch(now);
            return toState(session, userId);
        });
    }

    @Override
    public NekoStateView start(String sessionId, long userId, NekoStartRequest request) {
        NekoSession session = registry.require(sessionId, NekoSession.class, userId);
        PickedWords picked = session.isAwaitingStart() ? pickWords(session, userId, request) : null;
        record Started(NekoStateView view, NekoSession.Config changed) {
        }
        Started started = session.withLock(() -> {
            if (!session.isAwaitingStart() || session.getStatus() != SessionStatus.RUNNING) {
                return new Started(toState(session, userId), null);
            }
            Instant now = Instant.now();
            if (picked != null) {
                session.useWords(picked.questionField(), picked.answerField(), picked.words());
                session.setAnswerPool(picked.answerPool());
            }
            NekoSession.Config chosen = chosenConfig(session, request);
            boolean changed = picked != null || !chosen.equals(session.getConfig());
            if (changed) {
                session.reconfigure(chosen, buildRounds(session.getWords(), session.getAnswerPool(), chosen,
                        ThreadLocalRandom.current()));
            }
            Instant startAt = now.plusMillis(START_DELAY_MS);
            Instant deadline = startAt.plusSeconds(chosen.durationSeconds());
            session.setStartAt(startAt);
            // Chưa ai chơi lượt nào: tạo lại người chơi để số tim theo cài đặt mới.
            for (NekoPlayer player : session.orderedPlayers()) {
                session.addPlayer(new NekoPlayer(player.getSeat(), chosen.lives(), deadline));
            }
            session.setAwaitingStart(false);
            session.touch(now);
            return new Started(toState(session, userId), changed ? chosen : null);
        });
        if (started.changed() != null) {
            lifecycle.recorder().updateSettings(session.getMatchId(), settingsSnapshot(session, started.changed()));
        }
        return started.view();
    }

    /** Cài đặt người chơi chọn ở màn chuẩn bị; chọn độ khó thì số tim / số cá lấy theo độ khó nếu không chọn riêng. */
    private static NekoSession.Config chosenConfig(NekoSession session, NekoStartRequest request) {
        NekoSession.Config current = session.getConfig();
        if (request == null) {
            return current;
        }
        String difficulty = request.difficulty() == null ? current.difficulty() : request.difficulty();
        if (!GameRules.NEKO_DIFFICULTIES.contains(difficulty)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Độ khó không hợp lệ");
        }
        int duration = request.durationSeconds() == null ? current.durationSeconds() : request.durationSeconds();
        if (!(session.isVersus() ? GameRules.NEKO_DURATIONS : GameRules.NEKO_SOLO_DURATIONS).contains(duration)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thời gian không hợp lệ");
        }
        NekoSession.Config preset = config(difficulty, duration);
        int maxChoices = maxChoices(session);
        int lives = request.lives() == null ? preset.lives() : request.lives();
        if (lives < 1 || lives > MAX_LIVES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Số tim từ 1 đến " + MAX_LIVES);
        }
        int choices = request.choices() == null ? Math.min(preset.distractors() + 1, maxChoices) : request.choices();
        if (choices < 2 || choices > maxChoices) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Số cá mỗi lượt từ 2 đến " + maxChoices);
        }
        return new NekoSession.Config(difficulty, lives, choices - 1, duration);
    }

    private record PickedWords(DeckField questionField, DeckField answerField, List<GameWord> words,
                               List<String> answerPool) {
    }

    /**
     * Cặp hỏi – đáp và các thẻ người chơi chọn. Mọi thẻ đã chọn phải có đủ cả hai trường (thiếu dù một thẻ thì
     * phải bỏ chọn thẻ đó hoặc đổi trường). Null nếu không đổi gì.
     */
    private PickedWords pickWords(NekoSession session, long userId, NekoStartRequest request) {
        if (request == null || (request.questionField() == null && request.answerField() == null
                && request.cardIds() == null)) {
            return null;
        }
        DeckField question = request.questionField() == null ? session.getQuestionField() : request.questionField();
        DeckField answer = request.answerField() == null ? session.getAnswerField() : request.answerField();
        GameDeckService.PickedDeck picked = decks.pickCards(userId, session.getDeckId(), question, answer,
                request.cardIds(), GameRules.minWords(GameType.NEKO_CATCH, null), GameRules.gameName(GameType.NEKO_CATCH));
        List<GameWord> words = picked.words();
        // Cá sai lấy từ đáp án của cả bộ, không chỉ các thẻ đã chọn làm câu hỏi (bỏ trùng không phân biệt hoa thường).
        Map<String, String> pool = new LinkedHashMap<>();
        for (DeckCardView card : picked.deckCards()) {
            String value = card.value(answer);
            if (value != null) {
                pool.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
            }
        }
        return new PickedWords(question, answer, words, List.copyOf(pool.values()));
    }

    /** Số cá tối đa mỗi lượt: bằng số đáp án khác nhau của bộ thẻ, không quá 7. */
    private static int maxChoices(NekoSession session) {
        return Math.min(MAX_CHOICES, session.getAnswerPool().size());
    }

    private static Map<String, Object> settingsSnapshot(NekoSession session, NekoSession.Config config) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("difficulty", config.difficulty());
        options.put("durationSeconds", config.durationSeconds());
        options.put("lives", config.lives());
        options.put("choices", config.distractors() + 1);
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

    private NekoActionResult act(String sessionId, long userId, String roundId, Action action, String choiceId) {
        rateLimiter.check(userId, "neko", properties.arena().rateLimit().answerPerMinute());
        NekoSession session = registry.require(sessionId, NekoSession.class, userId);
        Outcome outcome = session.withLock(() -> apply(session, userId, roundId, action, choiceId));
        publish(session, outcome, userId);
        return outcome.result();
    }

    private Outcome apply(NekoSession session, long userId, String roundId, Action action, String choiceId) {
        Instant now = Instant.now();
        session.touch(now);
        NekoPlayer player = session.player(userId);
        if (session.getStatus() == SessionStatus.PAUSED) {
            throw new ApiException(ErrorCode.CONFLICT, "Trận đang tạm dừng");
        }
        if (session.isAwaitingStart()) {
            throw new ApiException(ErrorCode.CONFLICT, "Bấm \"Bắt đầu\" để vào trận");
        }
        if (session.getStatus().isTerminal() || player.isFinished()) {
            return new Outcome(result(session, player, false, false, null, false, null, false), false, null);
        }
        if (now.isBefore(session.getStartAt().minusMillis(500))) {
            throw new ApiException(ErrorCode.CONFLICT, "Trận chưa bắt đầu");
        }
        if (now.isAfter(player.getDeadline().plusMillis(TIME_GRACE_MS))) {
            player.finish(NekoPlayer.FINISH_TIME, now);
            ResultSummary summary = finishIfDone(session, now);
            return new Outcome(result(session, player, false, false, null, false, null, false), true, summary);
        }

        NekoRound current = session.roundAt(player.getRoundIndex());
        boolean correct = false;
        boolean shieldBlocked = false;
        boolean advanced = false;
        String bonusApplied = null;
        String revealText = null;
        String revealId = null;

        switch (action) {
            case CATCH -> {
                String chosenText = current.id().equals(roundId) ? current.textOf(choiceId) : null;
                if (chosenText == null) {
                    return stale(session, player);
                }
                revealText = current.word().answer();
                revealId = current.correctChoiceId();
                if (current.correctChoiceId().equals(choiceId)) {
                    correct = true;
                    player.recordCorrect();
                    player.advance();
                    advanced = true;
                } else if (player.hasShield()) {
                    // Khiên chặn lần bắt nhầm; lượt vẫn tiếp tục, cá còn lại tiếp tục rơi.
                    player.setShield(false);
                    shieldBlocked = true;
                } else {
                    player.recordWrong(new NekoMistakeView(current.word().prompt(), current.word().answer(),
                            chosenText));
                    player.advance();
                    advanced = true;
                }
            }
            case MISS -> {
                if (!current.id().equals(roundId)) {
                    return stale(session, player);
                }
                revealText = current.word().answer();
                revealId = current.correctChoiceId();
                if (player.hasShield()) {
                    player.setShield(false);
                    shieldBlocked = true;
                } else {
                    player.recordWrong(new NekoMistakeView(current.word().prompt(), current.word().answer(), null));
                }
                player.advance();
                advanced = true;
            }
            case BONUS -> {
                NekoRound round = recentRound(session, player, roundId);
                if (round == null || round.bonus() == null || !player.claimBonus(roundId)) {
                    return stale(session, player);
                }
                bonusApplied = round.bonus();
                applyBonus(player, round.bonus(), now, session.getConfig().durationSeconds());
            }
        }

        if (!advanced) {
            // Lượt chưa xong (khiên chặn lần bắt nhầm): không lộ đáp án khi cá đúng còn đang rơi.
            revealText = null;
            revealId = null;
        }
        if (player.getLives() == 0) {
            player.finish(NekoPlayer.FINISH_LIVES, now);
        }
        ResultSummary summary = player.isFinished() ? finishIfDone(session, now) : null;
        NekoActionResult result = result(session, player, true, correct, revealId, shieldBlocked, bonusApplied,
                advanced);
        return new Outcome(withReveal(result, revealText), true, summary);
    }

    private static NekoActionResult withReveal(NekoActionResult result, String correctText) {
        return new NekoActionResult(result.accepted(), result.correct(), result.correctChoiceId(), correctText,
                result.shieldBlocked(), result.bonusApplied(), result.advanced(), result.me(), result.finished(),
                result.serverTime());
    }

    private Outcome stale(NekoSession session, NekoPlayer player) {
        return new Outcome(result(session, player, false, false, null, false, null, false), false, null);
    }

    private static NekoRound recentRound(NekoSession session, NekoPlayer player, String roundId) {
        for (int back = 0; back <= BONUS_LOOKBACK; back++) {
            int index = player.getRoundIndex() - back;
            if (index < 0) {
                break;
            }
            NekoRound round = session.roundAt(index);
            if (round.id().equals(roundId)) {
                return round;
            }
        }
        return null;
    }

    private static void applyBonus(NekoPlayer player, String bonus, Instant now, int durationSeconds) {
        switch (bonus) {
            case "GOLDEN_FISH" -> player.addScore(GOLDEN_FISH_POINTS);
            case "CLOCK" -> {
                Instant extended = player.getDeadline().plusMillis(CLOCK_BONUS_MS);
                Instant cap = now.plusSeconds(durationSeconds);
                player.setDeadline(extended.isAfter(cap) ? cap : extended);
            }
            case "SHIELD" -> player.setShield(true);
            // Đóng băng dừng cả cá lẫn đồng hồ ở client trong 2 giây.
            case "FREEZE" -> player.setDeadline(player.getDeadline().plusMillis(FREEZE_BONUS_MS));
            default -> { }
        }
    }

    // ----------------------------------------------------------------- finish

    /** Gọi trong lock. Trả tóm tắt nếu trận vừa kết thúc. */
    private ResultSummary finishIfDone(NekoSession session, Instant now) {
        if (session.getStatus().isTerminal()) {
            return null;
        }
        boolean forfeited = session.orderedPlayers().stream()
                .anyMatch(player -> NekoPlayer.FINISH_FORFEIT.equals(player.getFinishReason()));
        if (!session.allFinished() && !forfeited) {
            return null;
        }
        session.setStatus(SessionStatus.FINISHED);
        session.setFinishedAt(now);
        ResultSummary summary = summarize(session, now);
        session.setSummary(summary);
        return summary;
    }

    private static ResultSummary summarize(NekoSession session, Instant now) {
        Comparator<NekoPlayer> order = Comparator
                .comparingInt((NekoPlayer player) -> isForfeit(player) ? 1 : 0)
                .thenComparing(NekoPlayer::getScore, Comparator.reverseOrder())
                .thenComparing(NekoPlayer::getCorrect, Comparator.reverseOrder())
                .thenComparingInt(NekoPlayer::getWrong);
        List<NekoPlayer> ranked = session.orderedPlayers().stream().sorted(order).toList();

        Long winner = null;
        boolean draw = false;
        if (session.isVersus() && ranked.size() > 1) {
            NekoPlayer first = ranked.get(0);
            NekoPlayer second = ranked.get(1);
            draw = !isForfeit(first) && !isForfeit(second) && order.compare(first, second) == 0;
            winner = draw || isForfeit(first) ? null : first.getUserId();
        } else if (!ranked.isEmpty()) {
            NekoPlayer solo = ranked.get(0);
            winner = solo.getLives() > 0 && !isForfeit(solo) ? solo.getUserId() : null;
        }

        List<ResultLine> lines = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            NekoPlayer player = ranked.get(i);
            String detail = player.getCorrect() + " đúng · " + player.getWrong() + " sai · combo cao nhất "
                    + player.getMaxCombo() + (isForfeit(player) ? " · rời trận" : "");
            lines.add(new ResultLine(player.getUserId(), player.getSeat().displayName(), draw ? 1 : i + 1,
                    player.getScore(), detail, winner != null && winner == player.getUserId()));
        }
        return new ResultSummary(session.getSessionId(), GameType.NEKO_CATCH, session.getMode(), winner, draw,
                lines, now.toEpochMilli());
    }

    private static boolean isForfeit(NekoPlayer player) {
        return NekoPlayer.FINISH_FORFEIT.equals(player.getFinishReason());
    }

    private void publish(NekoSession session, Outcome outcome, long userId) {
        if (outcome == null) {
            return;
        }
        if (outcome.progressChanged()) {
            NekoPlayerView view = session.withLock(() -> playerView(session, session.player(userId)));
            lifecycle.broadcast(session, ArenaEventType.NEKO_PROGRESS, view);
        }
        if (outcome.summary() != null) {
            complete(session, outcome.summary());
        }
    }

    private void complete(NekoSession session, ResultSummary summary) {
        Set<Long> forfeited = new HashSet<>();
        List<NekoPlayerView> players = session.withLock(() -> {
            for (NekoPlayer player : session.orderedPlayers()) {
                if (isForfeit(player)) {
                    forfeited.add(player.getUserId());
                }
            }
            return session.orderedPlayers().stream().map(player -> playerView(session, player)).toList();
        });
        lifecycle.completed(session, summary, players, forfeited);
        log.info("Neko Catch {} kết thúc, thắng: {}", session.getSessionId(), summary.winnerUserId());
    }

    // ------------------------------------------------------------ engine hooks

    @Override
    public void forfeit(GameSession raw, long userId) {
        if (!(raw instanceof NekoSession session)) {
            return;
        }
        ResultSummary summary = session.withLock(() -> {
            NekoPlayer player = session.player(userId);
            if (player == null || session.getStatus().isTerminal()) {
                return null;
            }
            Instant now = Instant.now();
            player.finish(NekoPlayer.FINISH_FORFEIT, now);
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
        for (NekoSession session : registry.all(NekoSession.class)) {
            List<Long> expired = new ArrayList<>();
            ResultSummary summary = session.withLock(() -> {
                if (session.getStatus() != SessionStatus.RUNNING || session.isAwaitingStart()) {
                    return null;
                }
                for (NekoPlayer player : session.orderedPlayers()) {
                    if (!player.isFinished() && now.isAfter(player.getDeadline().plusMillis(TIME_GRACE_MS))) {
                        player.finish(NekoPlayer.FINISH_TIME, now);
                        expired.add(player.getUserId());
                    }
                }
                return expired.isEmpty() ? null : finishIfDone(session, now);
            });
            for (Long userId : expired) {
                NekoPlayerView view = session.withLock(() -> playerView(session, session.player(userId)));
                lifecycle.broadcast(session, ArenaEventType.NEKO_PROGRESS, view);
            }
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

    private NekoStateView toState(NekoSession session, long userId) {
        return toState(session, userId, MAX_CHOICES);
    }

    /** Small screens receive fewer distractors; the correct fish always stays, without exposing its ID. */
    static List<NekoChoiceView> visibleChoices(NekoRound round, int limit) {
        if (round.choices().size() <= limit) return round.choices();
        List<NekoChoiceView> choices = new ArrayList<>(round.choices().stream()
                .filter(choice -> !choice.id().equals(round.correctChoiceId())).limit(limit - 1).toList());
        choices.add(round.choices().stream().filter(choice -> choice.id().equals(round.correctChoiceId())).findFirst().orElseThrow());
        java.util.Collections.shuffle(choices, new Random(round.id().hashCode()));
        return List.copyOf(choices);
    }

    private NekoStateView toState(NekoSession session, long userId, int choiceLimit) {
        NekoSession.Config config = session.getConfig();
        NekoConfigView configView = new NekoConfigView(config.difficulty(), config.lives(),
                Math.min(config.distractors() + 1, Math.min(choiceLimit, maxChoices(session))), Math.min(choiceLimit, maxChoices(session)), config.durationSeconds(), session.getQuestionField(), session.getAnswerField(),
                session.getQuestionField().label(), session.getAnswerField().label(),
                session.getMode() == PlayMode.SOLO);
        List<NekoRoundView> rounds = session.getRounds().stream()
                .map(round -> new NekoRoundView(round.id(), round.index(), round.word().prompt(), visibleChoices(round, choiceLimit),
                        round.bonus()))
                .toList();
        List<NekoPlayerView> players = session.orderedPlayers().stream()
                .map(player -> playerView(session, player)).toList();
        NekoPlayer me = session.player(userId);
        return new NekoStateView(session.getSessionId(), session.getMode(), session.getRoomId(),
                session.getStatus(), session.getDeckId(), session.getDeckTitle(), configView, rounds, userId,
                players, session.getStartAt().toEpochMilli(), session.isAwaitingStart(),
                session.getPausedAt() == null ? null : session.getPausedAt().toEpochMilli(),
                me == null ? List.of() : List.copyOf(me.getMistakes()), session.getSummary(),
                System.currentTimeMillis());
    }

    private static NekoPlayerView playerView(NekoSession session, NekoPlayer player) {
        PlayerSeat seat = player.getSeat();
        return new NekoPlayerView(seat.userId(), seat.displayName(), seat.avatarUrl(), seat.slot(),
                player.getScore(), player.getCombo(), player.getMaxCombo(), player.getCorrect(), player.getWrong(),
                player.getLives(), player.getMaxLives(), player.hasShield(), player.getRoundIndex(),
                player.getDeadline().toEpochMilli(), player.isFinished(), player.getFinishReason(),
                session.isOnline(seat.userId()));
    }

    private NekoActionResult result(NekoSession session, NekoPlayer player, boolean accepted, boolean correct,
                                    String correctChoiceId, boolean shieldBlocked, String bonusApplied,
                                    boolean advanced) {
        return new NekoActionResult(accepted, correct, correctChoiceId, null, shieldBlocked, bonusApplied, advanced,
                playerView(session, player), player.isFinished() || session.getStatus().isTerminal(),
                System.currentTimeMillis());
    }
}
