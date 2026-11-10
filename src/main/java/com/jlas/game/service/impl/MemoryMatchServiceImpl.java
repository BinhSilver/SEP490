package com.jlas.game.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.config.GameProperties;
import com.jlas.game.dto.GameDtos.ResultLine;
import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.dto.MemoryDtos.MemoryStartRequest;
import com.jlas.game.dto.MemoryDtos.CoinTossView;
import com.jlas.game.dto.MemoryDtos.MemoryCardView;
import com.jlas.game.dto.MemoryDtos.MemoryConfigView;
import com.jlas.game.dto.MemoryDtos.MemoryEnvelope;
import com.jlas.game.dto.MemoryDtos.MemoryPlayerView;
import com.jlas.game.dto.MemoryDtos.MemoryResultView;
import com.jlas.game.dto.MemoryDtos.MemoryStateView;
import com.jlas.game.dto.MemoryDtos.MemoryTermView;
import com.jlas.game.entity.MemoryMatchResult;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.SessionStatus;
import com.jlas.game.runtime.ArenaEventType;
import com.jlas.game.runtime.ArenaRateLimiter;
import com.jlas.game.runtime.GameScheduler;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.GameSessionRegistry;
import com.jlas.game.runtime.GameWord;
import com.jlas.game.runtime.LaunchRequest;
import com.jlas.game.runtime.PlayerSeat;
import com.jlas.game.runtime.memory.MemoryCard;
import com.jlas.game.runtime.memory.MemoryPlayer;
import com.jlas.game.runtime.memory.MemorySession;
import com.jlas.game.util.GameRules;
import com.jlas.game.service.GameDeckService;
import com.jlas.game.service.MemoryMatchService;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Engine Memory Match (port từ bản demo, dữ liệu lấy từ bộ flashcard). Client chỉ gửi "lật thẻ X";
 * mọi phán quyết (hợp lệ, khớp cặp, đổi lượt, hết giờ, kết ván) đều ở đây.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryMatchServiceImpl implements MemoryMatchService {

    static final String SESSION_STATE = "MEMORY_SESSION_STATE";
    static final String CARD_REVEALED = "MEMORY_CARD_REVEALED";
    static final String PAIR_MATCHED = "MEMORY_PAIR_MATCHED";
    static final String PAIR_MISMATCH = "MEMORY_PAIR_MISMATCH";
    static final String NEXT_TURN = "MEMORY_NEXT_TURN";
    static final String TURN_TIMEOUT = "MEMORY_TURN_TIMEOUT";
    static final String PLAYER_UPDATED = "MEMORY_PLAYER_UPDATED";
    static final String PAUSED = "MEMORY_PAUSED";
    static final String RESUMED = "MEMORY_RESUMED";
    static final String GAME_OVER = "MEMORY_GAME_OVER";
    /** Quăng đồng xu, hạ xuống và công bố kết quả trước khi đồng hồ lượt chạy. */
    private static final long VERSUS_INTRO_MS = 5_500;
    /** Luyện tập: bấm "Bắt đầu" rồi đếm ngược 3-2-1 giữa màn hình. */
    private static final long SOLO_COUNTDOWN_MS = 3_000;

    private record FlipOutcome(String eventType, boolean scheduleResolve, boolean finished, boolean turnRestarted) {
    }

    private final GameSessionRegistry registry;
    private final GameSessionLifecycle lifecycle;
    private final GameScheduler scheduler;
    private final ArenaRateLimiter rateLimiter;
    private final GameProperties properties;
    private final GameDeckService decks;

    /** sessionId → task úp lại cặp sai / hết lượt / hết tổng thời gian. */
    private final Map<String, ScheduledFuture<?>> resolveTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> turnTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> totalTasks = new ConcurrentHashMap<>();

    @Override
    public GameType type() {
        return GameType.MEMORY_MATCH;
    }

    // ------------------------------------------------------------------ launch

    @Override
    public GameSession launch(LaunchRequest request) {
        MemorySession.Config config = new MemorySession.Config(
                Math.min(request.options().boardSize(), GameRules.MEMORY_MAX_PAIRS * 2),
                request.options().objective(), request.options().turnSeconds(),
                request.options().totalSeconds(), Boolean.TRUE.equals(request.options().keepTurnOnMatch()),
                properties.memory().revealDelayMs(), Boolean.TRUE.equals(request.options().distinguishCardTypes()));

        Board board = buildBoard(request.words(), config.pairCount(), request.questionField(), request.answerField());
        List<MemoryCard> cards = board.cards();

        Instant now = Instant.now();
        MemorySession session = new MemorySession(request, config, cards, board.pairWords(), now);
        for (PlayerSeat seat : request.seats()) {
            session.addPlayer(new MemoryPlayer(seat));
        }
        if (!session.isVersus()) {
            // Luyện tập: chờ người chơi chọn cài đặt ở màn cài đặt rồi bấm "Bắt đầu" (xem start).
            session.withLock(() -> {
                session.setAwaitingStart(true);
                session.setCurrentTurnUserId(request.seats().get(0).userId());
                session.bumpVersion();
            });
            registry.register(session);
            log.info("Memory Match {} chờ bắt đầu ({} thẻ)", session.getSessionId(), cards.size());
            return session;
        }
        // Hai người chọn mặt trước; chưa chạy bất kỳ đồng hồ lượt/tổng nào ở đây.
        session.withLock(session::bumpVersion);
        registry.register(session);
        log.info("Memory Match {} bắt đầu ({} thẻ, {} người chơi)", session.getSessionId(), cards.size(),
                request.seats().size());
        return session;
    }

    private record Board(List<MemoryCard> cards, Map<Integer, GameWord> pairWords) {
    }

    /** Chọn ngẫu nhiên {@code pairCount} từ, mỗi từ thành một cặp thẻ (hỏi + đáp), xáo vị trí trên lưới. */
    private static Board buildBoard(List<GameWord> source, int pairCount, DeckField questionField,
                                    DeckField answerField) {
        List<GameWord> words = new ArrayList<>(source);
        Collections.shuffle(words, ThreadLocalRandom.current());
        List<GameWord> chosen = words.subList(0, Math.min(pairCount, words.size()));
        List<MemoryCard> unplaced = new ArrayList<>();
        Map<Integer, GameWord> pairWords = new LinkedHashMap<>();
        for (int pairId = 0; pairId < chosen.size(); pairId++) {
            GameWord word = chosen.get(pairId);
            pairWords.put(pairId, word);
            unplaced.add(new MemoryCard(UUID.randomUUID().toString(), pairId, -1, questionField, word.prompt()));
            unplaced.add(new MemoryCard(UUID.randomUUID().toString(), pairId, -1, answerField, word.answer()));
        }
        Collections.shuffle(unplaced, ThreadLocalRandom.current());
        // Gán position sau khi xáo để trùng thứ tự hiển thị trên lưới.
        List<MemoryCard> cards = new ArrayList<>(unplaced.size());
        for (int position = 0; position < unplaced.size(); position++) {
            MemoryCard card = unplaced.get(position);
            cards.add(new MemoryCard(card.getCardInstanceId(), card.getPairId(), position, card.getFace(),
                    card.getContent()));
        }
        return new Board(cards, pairWords);
    }

    // ------------------------------------------------------------------- start

    @Override
    public MemoryStateView start(String sessionId, long userId, MemoryStartRequest request) {
        MemorySession session = registry.require(sessionId, MemorySession.class, userId);
        if (!session.withLock(session::isAwaitingStart)) {
            return state(sessionId, userId);
        }
        MemoryStartRequest r = request == null ? new MemoryStartRequest(null, null, null, null, null, null, null) : request;
        MemorySession.Config current = session.withLock(session::getConfig);
        DeckField question = r.questionField() == null ? session.getQuestionField() : r.questionField();
        DeckField answer = r.answerField() == null ? session.getAnswerField() : r.answerField();
        // Luyện tập: mỗi từ thành một cặp, tối đa 10 cặp (20 thẻ trên bàn).
        Integer requestedBoard = r.boardSize();
        if (requestedBoard != null && (requestedBoard % 2 != 0 || requestedBoard < GameRules.MEMORY_MIN_PAIRS * 2
                || requestedBoard > GameRules.MEMORY_MAX_PAIRS * 2)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Số thẻ trên bàn không hợp lệ");
        }
        String objective = r.objective() == null ? current.objective() : r.objective();
        if (!GameRules.MEMORY_OBJECTIVES.contains(objective)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mục tiêu không hợp lệ");
        }
        Integer totalSeconds = null;
        if ("TIME_ATTACK".equals(objective)) {
            totalSeconds = r.totalSeconds() != null ? r.totalSeconds()
                    : current.totalSeconds() != null ? current.totalSeconds() : GameRules.MEMORY_TOTAL_SECONDS.get(1);
            if (!GameRules.MEMORY_TOTAL_SECONDS.contains(totalSeconds)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thời gian không hợp lệ");
            }
        }
        // Mỗi cặp trên bàn là một thẻ khác nhau: số thẻ đã chọn phải đủ cho số cặp.
        int needPairs = requestedBoard == null ? GameRules.MEMORY_MIN_PAIRS : requestedBoard / 2;
        GameDeckService.PickedDeck picked = decks.pickCards(userId, session.getDeckId(), question, answer,
                r.cardIds(), needPairs, requestedBoard == null ? GameRules.gameName(GameType.MEMORY_MATCH)
                        : GameRules.gameName(GameType.MEMORY_MATCH) + " với bàn " + requestedBoard + " thẻ");
        int boardSize = requestedBoard != null ? requestedBoard
                : Math.min(picked.words().size(), GameRules.MEMORY_MAX_PAIRS) * 2;
        MemorySession.Config config = new MemorySession.Config(boardSize, objective, current.turnSeconds(),
                totalSeconds, current.keepTurnOnMatch(), current.revealDelayMs(),
                r.distinguishCardTypes() == null ? current.distinguishCardTypes() : r.distinguishCardTypes());
        Board board = buildBoard(picked.words(), config.pairCount(), question, answer);
        boolean started = session.withLock(() -> {
            if (!session.isAwaitingStart()) {
                return false;
            }
            Instant startAt = Instant.now().plusMillis(SOLO_COUNTDOWN_MS);
            session.useWords(question, answer, picked.words());
            session.rebuild(config, board.cards(), board.pairWords());
            session.setAwaitingStart(false);
            session.startTurn(startAt);
            session.setPlayStartedAt(startAt);
            if ("TIME_ATTACK".equals(config.objective()) && config.totalSeconds() != null) {
                session.setTotalDeadlineAt(startAt.plusSeconds(config.totalSeconds()));
            }
            session.bumpVersion();
            return true;
        });
        if (!started) {
            return state(sessionId, userId);
        }
        scheduleTurnTimeout(session);
        scheduleTotalTimeout(session);
        lifecycle.recorder().updateSettings(session.getMatchId(), settingsSnapshot(session, config));
        return broadcast(session, SESSION_STATE);
    }

    private static Map<String, Object> settingsSnapshot(MemorySession session, MemorySession.Config config) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("boardSize", config.boardSize());
        options.put("objective", config.objective());
        options.put("totalSeconds", config.totalSeconds());
        options.put("turnSeconds", config.turnSeconds());
        options.put("distinguishCardTypes", config.distinguishCardTypes());
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("setId", session.getDeckId());
        settings.put("setTitle", session.getDeckTitle());
        settings.put("questionField", session.getQuestionField());
        settings.put("answerField", session.getAnswerField());
        settings.put("cardCount", session.getWords().size());
        settings.put("options", options);
        return settings;
    }

    // ------------------------------------------------------------------- state

    @Override
    public MemoryStateView state(String sessionId, long userId) {
        MemorySession session = registry.require(sessionId, MemorySession.class, userId);
        return session.withLock(() -> toStateView(session));
    }

    @Override
    public MemoryStateView chooseCoinSide(String sessionId, long userId, String side) {
        rateLimiter.check(userId, "memory-coin", properties.arena().rateLimit().readyPerMinute());
        MemorySession session = registry.require(sessionId, MemorySession.class, userId);
        boolean started = session.withLock(() -> {
            if (!session.isVersus() || session.getStatus() != SessionStatus.RUNNING) {
                throw new ApiException(ErrorCode.CONFLICT, "Không chọn mặt đồng xu ở ván này");
            }
            if (!List.of("JLAS", "MASCOT").contains(side == null ? "" : side)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn mặt KotsuKotsu hoặc mascot");
            }
            if (session.getCoinStartedAt() != null) {
                if (side.equals(session.getCoinChoices().get(userId))) return false;
                throw new ApiException(ErrorCode.CONFLICT, "Đồng xu đã được tung");
            }
            boolean taken = session.getCoinChoices().entrySet().stream()
                    .anyMatch(entry -> entry.getKey() != userId && side.equals(entry.getValue()));
            if (taken) throw new ApiException(ErrorCode.CONFLICT, "Đối thủ đã chọn mặt này. Chọn mặt còn lại nhé");
            session.getCoinChoices().put(userId, side);
            Instant now = Instant.now();
            session.touch(now);
            session.bumpVersion();
            if (session.getCoinChoices().size() < 2) return false;
            // Ghép nhanh giữ quyền đi trước đã chốt ở bước draft; phòng bạn bè tung ngẫu nhiên trên server.
            Long drafted = session.getDraftedFirstUserId();
            long starter = drafted != null && session.getCoinChoices().containsKey(drafted) ? drafted
                    : session.orderedPlayers().get(ThreadLocalRandom.current().nextInt(2)).getUserId();
            session.startCoin(session.getCoinChoices().get(starter), starter, now);
            Instant playAt = now.plusMillis(VERSUS_INTRO_MS);
            session.setCurrentTurnUserId(starter);
            session.startTurn(playAt);
            session.setPlayStartedAt(playAt);
            if ("TIME_ATTACK".equals(session.getConfig().objective())) {
                session.setTotalDeadlineAt(playAt.plusSeconds(session.getConfig().totalSeconds()));
            }
            return true;
        });
        if (started) { scheduleTurnTimeout(session); scheduleTotalTimeout(session); }
        return broadcast(session, SESSION_STATE);
    }

    @Override
    public MemoryStateView flip(String sessionId, long userId, String cardInstanceId) {
        rateLimiter.check(userId, "memory-flip", properties.arena().rateLimit().flipPerMinute());
        MemorySession session = registry.require(sessionId, MemorySession.class, userId);
        FlipOutcome outcome = session.withLock(() -> applyFlip(session, userId, cardInstanceId));
        MemoryStateView state = broadcast(session, outcome.eventType());
        if (outcome.finished()) {
            completeSession(session);
            return state;
        }
        if (outcome.scheduleResolve()) {
            scheduleMismatchResolve(session);
        }
        if (outcome.turnRestarted()) {
            scheduleTurnTimeout(session);
        }
        return state;
    }

    /** Toàn bộ phán quyết chạy trong lock để hai lần lật đồng thời không cùng thấy một thẻ úp. */
    private FlipOutcome applyFlip(MemorySession session, long userId, String cardInstanceId) {
        if (session.getStatus() != SessionStatus.RUNNING) {
            throw new ApiException(ErrorCode.CONFLICT, "Ván đã kết thúc hoặc đang tạm dừng");
        }
        if (session.isAwaitingStart()) {
            throw new ApiException(ErrorCode.CONFLICT, "Bấm \"Bắt đầu\" để vào ván");
        }
        if (session.isVersus() && session.getCoinStartedAt() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "Hai người cần chọn mặt đồng xu trước");
        }
        if (session.getPlayStartedAt() != null && Instant.now().isBefore(session.getPlayStartedAt().minusMillis(300))) {
            throw new ApiException(ErrorCode.CONFLICT, "Ván chưa bắt đầu");
        }
        MemoryPlayer player = session.player(userId);
        if (!session.isMyTurn(userId)) {
            throw new ApiException(ErrorCode.CONFLICT, "Chưa tới lượt của bạn");
        }
        if (session.isResolving()) {
            throw new ApiException(ErrorCode.CONFLICT, "Đang chờ úp thẻ, thử lại ngay sau");
        }
        MemoryCard card = session.findCard(cardInstanceId)
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST, "Thẻ không tồn tại"));
        if (!MemoryCard.HIDDEN.equals(card.getState())) {
            throw new ApiException(ErrorCode.CONFLICT, "Thẻ này đã được mở");
        }

        Instant now = Instant.now();
        session.touch(now);
        card.setState(MemoryCard.TEMP_REVEALED);
        session.getSelection().add(card.getCardInstanceId());

        // Thẻ đầu: mở và chờ thẻ thứ hai, chưa tính là một lượt lật.
        if (session.getSelection().size() == 1) {
            session.setFirstFlipAt(now);
            session.bumpVersion();
            return new FlipOutcome(CARD_REVEALED, false, false, false);
        }

        if (session.getFirstFlipAt() != null) {
            player.recordDecision(Duration.between(session.getFirstFlipAt(), now).toMillis());
        }
        session.incrementMoves();
        player.recordMove();

        MemoryCard first = session.findCard(session.getSelection().get(0))
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Thẻ đã mở không còn trên bàn"));
        if (first.getPairId() == card.getPairId()) {
            return resolveMatch(session, player, first, card, now);
        }
        // Cặp sai: giữ hai thẻ mở trong revealDelay để người chơi kịp ghi nhớ.
        player.recordMistake();
        session.recordPairMistake(first.getPairId());
        session.recordPairMistake(card.getPairId());
        session.setResolving(true);
        session.bumpVersion();
        return new FlipOutcome(PAIR_MISMATCH, true, false, false);
    }

    private FlipOutcome resolveMatch(MemorySession session, MemoryPlayer player, MemoryCard first,
                                     MemoryCard second, Instant now) {
        for (MemoryCard card : List.of(first, second)) {
            card.setState(MemoryCard.MATCHED);
            card.setMatchedByUserId(player.getUserId());
        }
        session.getSelection().clear();
        session.incrementPairsMatched();
        player.recordMatch();

        if (session.isBoardCleared()) {
            finishInLock(session, MemorySession.OUTCOME_CLEARED, now);
            return new FlipOutcome(GAME_OVER, false, true, false);
        }
        boolean turnChanged = false;
        if (!session.getConfig().keepTurnOnMatch()) {
            session.setCurrentTurnUserId(session.nextTurnUserId());
            turnChanged = true;
        }
        session.startTurn(now);
        session.bumpVersion();
        return new FlipOutcome(turnChanged ? NEXT_TURN : PAIR_MATCHED, false, false, true);
    }

    // ------------------------------------------------------------------ timers

    private void scheduleMismatchResolve(MemorySession session) {
        replaceTask(resolveTasks, session.getSessionId(),
                scheduler.after(session.getConfig().revealDelayMs(), () -> hideMismatch(session)));
    }

    private void hideMismatch(MemorySession session) {
        resolveTasks.remove(session.getSessionId());
        Boolean changed = session.withLock(() -> {
            if (session.getStatus() != SessionStatus.RUNNING || !session.isResolving()) {
                return false;
            }
            hideSelection(session);
            session.setResolving(false);
            session.setCurrentTurnUserId(session.nextTurnUserId());
            session.startTurn(Instant.now());
            session.bumpVersion();
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            broadcast(session, NEXT_TURN);
            scheduleTurnTimeout(session);
        }
    }

    /** Úp mọi thẻ đang mở tạm của lượt hiện tại. Gọi trong lock. */
    private static void hideSelection(MemorySession session) {
        for (String cardId : List.copyOf(session.getSelection())) {
            session.findCard(cardId).ifPresent(card -> {
                if (MemoryCard.TEMP_REVEALED.equals(card.getState())) {
                    card.setState(MemoryCard.HIDDEN);
                }
            });
        }
        session.getSelection().clear();
    }

    /** Hết thời gian lượt: úp thẻ đang mở, mất lượt. */
    private void scheduleTurnTimeout(MemorySession session) {
        Instant deadline = session.withLock(session::getTurnDeadlineAt);
        if (deadline == null) {
            return;
        }
        replaceTask(turnTasks, session.getSessionId(),
                scheduler.at(deadline, () -> handleTurnTimeout(session, deadline)));
    }

    private void handleTurnTimeout(MemorySession session, Instant expectedDeadline) {
        turnTasks.remove(session.getSessionId());
        Boolean changed = session.withLock(() -> {
            // Deadline đã đổi nghĩa là lượt này đã được xử lý bởi lần lật khác.
            if (session.getStatus() != SessionStatus.RUNNING
                    || !expectedDeadline.equals(session.getTurnDeadlineAt())) {
                return false;
            }
            MemoryPlayer player = session.player(session.getCurrentTurnUserId());
            if (player != null && !session.getSelection().isEmpty()) {
                player.recordMistake();
            }
            hideSelection(session);
            session.setResolving(false);
            session.setCurrentTurnUserId(session.nextTurnUserId());
            session.startTurn(Instant.now());
            session.bumpVersion();
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            broadcast(session, TURN_TIMEOUT);
            scheduleTurnTimeout(session);
        }
    }

    private void scheduleTotalTimeout(MemorySession session) {
        Instant deadline = session.withLock(session::getTotalDeadlineAt);
        if (deadline == null) {
            return;
        }
        replaceTask(totalTasks, session.getSessionId(), scheduler.at(deadline, () -> {
            totalTasks.remove(session.getSessionId());
            boolean finished = session.withLock(() -> {
                if (session.getStatus().isTerminal()) {
                    return false;
                }
                finishInLock(session, MemorySession.OUTCOME_TIME_UP, Instant.now());
                return true;
            });
            if (finished) {
                broadcast(session, GAME_OVER);
                completeSession(session);
            }
        }));
    }

    // ------------------------------------------------------------------ finish

    private void finishInLock(MemorySession session, String outcome, Instant now) {
        session.setStatus(SessionStatus.FINISHED);
        session.setOutcome(outcome);
        session.setFinishedAt(now);
        session.setResolving(false);
        session.setSummary(summarize(session, now));
        session.bumpVersion();
    }

    /** Dọn timer, lưu kết quả, báo phòng. Session vẫn nằm trong registry để client xem kết quả. */
    private void completeSession(MemorySession session) {
        cancelTasks(session.getSessionId());
        Set<Long> forfeited = new HashSet<>();
        MemoryStateView state = session.withLock(() -> {
            session.orderedPlayers().stream().filter(MemoryPlayer::isForfeited)
                    .forEach(player -> forfeited.add(player.getUserId()));
            return toStateView(session);
        });
        recordResults(session, state);
        lifecycle.completed(session, session.getSummary(), state.result(), forfeited);
        log.info("Memory Match {} kết thúc: {} ({}/{} cặp)", session.getSessionId(), session.getOutcome(),
                session.getPairsMatched(), session.getConfig().pairCount());
    }

    private static ResultSummary summarize(MemorySession session, Instant now) {
        Comparator<MemoryPlayer> order = Comparator
                .comparingInt((MemoryPlayer player) -> player.isForfeited() ? 1 : 0)
                .thenComparing(MemoryPlayer::getPairsFound, Comparator.reverseOrder())
                .thenComparingInt(MemoryPlayer::getMistakes);
        List<MemoryPlayer> ranked = session.orderedPlayers().stream().sorted(order).toList();
        Long winner = null;
        boolean draw = false;
        if (session.isVersus() && ranked.size() > 1) {
            MemoryPlayer first = ranked.get(0);
            MemoryPlayer second = ranked.get(1);
            draw = !first.isForfeited() && !second.isForfeited() && order.compare(first, second) == 0;
            winner = draw || first.isForfeited() ? null : first.getUserId();
        } else if (!ranked.isEmpty() && session.isBoardCleared()) {
            winner = ranked.get(0).getUserId();
        }
        List<ResultLine> lines = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            MemoryPlayer player = ranked.get(i);
            String detail = player.getPairsFound() + " cặp · " + player.getMistakes() + " lần sai · "
                    + player.getMoves() + " lượt" + (player.isForfeited() ? " · rời trận" : "");
            lines.add(new ResultLine(player.getUserId(), player.getSeat().displayName(), draw ? 1 : i + 1,
                    player.getPairsFound(), detail, winner != null && winner == player.getUserId()));
        }
        return new ResultSummary(session.getSessionId(), GameType.MEMORY_MATCH, session.getMode(), winner, draw,
                lines, now.toEpochMilli());
    }

    private void recordResults(MemorySession session, MemoryStateView state) {
        try {
            LocalDateTime finishedAt = LocalDateTime.ofInstant(
                    session.getFinishedAt() == null ? Instant.now() : session.getFinishedAt(), ZoneId.systemDefault());
            ResultSummary summary = session.getSummary();
            for (MemoryPlayerView player : state.players()) {
                lifecycle.recorder().recordMemory(MemoryMatchResult.builder()
                        .sessionId(session.getSessionId())
                        .match(lifecycle.recorder().matchRef(session.getMatchId()))
                        .roomId(session.getRoomId())
                        .user(lifecycle.recorder().userRef(player.userId()))
                        .playMode(session.getMode().name())
                        .objective(session.getConfig().objective())
                        .pairMode(state.config().pairMode())
                        .jlptLevel("CUSTOM")
                        .outcome(session.getOutcome())
                        .boardSize(session.getConfig().boardSize())
                        .pairsFound(player.pairsFound())
                        .mistakes(player.mistakes())
                        .moves(player.moves())
                        .bestStreak(player.bestStreak())
                        .accuracyPercent(player.accuracyPercent())
                        .averageDecisionMs(player.averageDecisionMs())
                        .durationMs(state.elapsedMs())
                        .ranked(session.isVersus())
                        .winner(summary != null && summary.winnerUserId() != null
                                && summary.winnerUserId() == player.userId())
                        .finishedAt(finishedAt)
                        .build());
            }
        } catch (RuntimeException ex) {
            log.warn("Không lưu được kết quả Memory {}: {}", session.getSessionId(), ex.getMessage());
        }
    }

    // ------------------------------------------------------------ engine hooks

    @Override
    public MemoryStateView setPaused(String sessionId, long userId, boolean paused) {
        MemorySession session = registry.require(sessionId, MemorySession.class, userId);
        Boolean changed = session.withLock(() -> {
            if (session.isVersus()) {
                throw new ApiException(ErrorCode.CONFLICT, "Ván đối kháng không thể tạm dừng");
            }
            if (session.isAwaitingStart()) {
                return false;
            }
            Instant now = Instant.now();
            if (paused && session.getStatus() == SessionStatus.RUNNING) {
                session.setStatus(SessionStatus.PAUSED);
                session.setPausedAt(now);
            } else if (!paused && session.getStatus() == SessionStatus.PAUSED) {
                Duration away = Duration.between(session.getPausedAt(), now);
                session.addPausedMs(away.toMillis());
                session.shiftDeadlines(away);
                session.setPausedAt(null);
                session.setStatus(SessionStatus.RUNNING);
            } else {
                return false;
            }
            session.bumpVersion();
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            if (paused) {
                cancelTasks(sessionId);
            } else {
                scheduleTurnTimeout(session);
                scheduleTotalTimeout(session);
            }
            return broadcast(session, paused ? PAUSED : RESUMED);
        }
        return state(sessionId, userId);
    }

    @Override
    public void forfeit(GameSession raw, long userId) {
        if (!(raw instanceof MemorySession session)) {
            return;
        }
        boolean finished = session.withLock(() -> {
            MemoryPlayer player = session.player(userId);
            if (player == null || session.getStatus().isTerminal()) {
                return false;
            }
            player.setForfeited(true);
            finishInLock(session, MemorySession.OUTCOME_ABANDONED, Instant.now());
            return true;
        });
        if (finished) {
            broadcast(session, GAME_OVER);
            completeSession(session);
        }
    }

    @Override
    public void presenceChanged(GameSession raw, long userId, boolean online) {
        if (!(raw instanceof MemorySession session)) {
            return;
        }
        boolean changed = session.withLock(() -> {
            boolean flipped = session.setOnline(userId, online);
            if (flipped) {
                session.bumpVersion();
            }
            return flipped;
        });
        if (changed) {
            broadcast(session, PLAYER_UPDATED);
        }
    }

    /** Timer của Memory chạy bằng task riêng, không cần nhịp mỗi giây. */
    @Override
    public void tick(Instant now) {
        // no-op
    }

    @Override
    public void abort(GameSession raw) {
        if (!(raw instanceof MemorySession session)) {
            return;
        }
        boolean changed = session.withLock(() -> {
            if (session.getStatus().isTerminal()) {
                return false;
            }
            session.setStatus(SessionStatus.ABORTED);
            session.setOutcome(MemorySession.OUTCOME_ABANDONED);
            session.setFinishedAt(Instant.now());
            session.bumpVersion();
            return true;
        });
        cancelTasks(session.getSessionId());
        if (changed) {
            broadcast(session, GAME_OVER);
            lifecycle.aborted(session);
        }
    }

    private MemoryStateView broadcast(MemorySession session, String eventType) {
        MemoryStateView state = session.withLock(() -> toStateView(session));
        lifecycle.broadcast(session, ArenaEventType.MEMORY_STATE, new MemoryEnvelope(eventType, state));
        return state;
    }

    private static void replaceTask(Map<String, ScheduledFuture<?>> holder, String sessionId,
                                    ScheduledFuture<?> task) {
        ScheduledFuture<?> previous = holder.put(sessionId, task);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void cancelTasks(String sessionId) {
        for (Map<String, ScheduledFuture<?>> holder : List.of(resolveTasks, turnTasks, totalTasks)) {
            ScheduledFuture<?> task = holder.remove(sessionId);
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    // -------------------------------------------------------------------- views

    /** Chốt duy nhất đảm bảo thẻ úp không lộ nội dung và pairId không rời server. */
    private MemoryStateView toStateView(MemorySession session) {
        Instant now = Instant.now();
        MemorySession.Config config = session.getConfig();
        MemoryConfigView configView = new MemoryConfigView(config.boardSize(), config.pairCount(),
                config.objective(), session.getQuestionField().name() + "_" + session.getAnswerField().name(),
                session.getDeckTitle(), config.turnSeconds(), config.totalSeconds(), null,
                config.keepTurnOnMatch(), config.revealDelayMs(), session.getQuestionField(),
                session.getAnswerField(), config.distinguishCardTypes());
        List<MemoryCardView> cards = session.getCards().stream().map(card -> {
            boolean visible = card.isContentVisible();
            return new MemoryCardView(card.getCardInstanceId(), card.getPosition(), card.getState(),
                    visible || config.distinguishCardTypes() ? card.getFace() : null,
                    visible ? card.getContent() : null,
                    MemoryCard.MATCHED.equals(card.getState()) ? card.getMatchedByUserId() : null);
        }).toList();
        List<MemoryPlayerView> players = session.orderedPlayers().stream().map(player -> new MemoryPlayerView(
                player.getUserId(), player.getSeat().displayName(), player.getSeat().avatarUrl(),
                player.getSeat().slot(), player.getPairsFound(), player.getMistakes(), player.getMoves(),
                player.getStreak(), player.getBestStreak(), player.accuracyPercent(), player.averageDecisionMs(),
                session.isOnline(player.getUserId()), session.isMyTurn(player.getUserId()))).toList();
        boolean finished = session.getStatus() == SessionStatus.FINISHED;
        return new MemoryStateView(session.getSessionId(), session.getRoomId(),
                session.isVersus() ? "MULTIPLAYER" : "SOLO", session.getStatus().name(), configView, cards, players,
                session.getStateVersion(), session.getCurrentTurnUserId() == 0 ? null : session.getCurrentTurnUserId(),
                session.isResolving(), session.getPairsMatched(), config.pairCount(), session.getMovesUsed(), null,
                millis(session.getTurnStartedAt()), millis(session.getTurnDeadlineAt()),
                millis(session.getTotalDeadlineAt()), session.elapsedMs(now), session.getOutcome(),
                session.isVersus(), finished ? toResultView(session, now) : null, session.getDeckId(),
                session.getDeckTitle(), session.isAwaitingStart(), now.toEpochMilli(), session.isVersus()
                ? new CoinTossView(Map.copyOf(session.getCoinChoices()), session.getCoinResult(), session.getCoinStarterUserId(),
                    millis(session.getCoinStartedAt()), millis(session.getPlayStartedAt())) : null);
    }

    /** Chỉ gọi khi ván đã xong: danh sách từ chính là đáp án của cả bàn. */
    private static MemoryResultView toResultView(MemorySession session, Instant now) {
        ResultSummary summary = session.getSummary();
        Map<Integer, Integer> mistakes = session.getMistakesByPair();
        List<MemoryTermView> terms = session.getPairWords().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    GameWord word = entry.getValue();
                    return new MemoryTermView(word.kanji(), word.reading(), word.meaning(), word.hanViet(),
                            word.prompt(), word.answer(), mistakes.getOrDefault(entry.getKey(), 0));
                })
                .toList();
        List<MemoryTermView> struggling = terms.stream().filter(term -> term.mistakes() > 0)
                .sorted(Comparator.comparingInt(MemoryTermView::mistakes).reversed())
                .limit(5)
                .toList();
        return new MemoryResultView(session.isBoardCleared(), session.isVersus(),
                summary == null ? null : summary.winnerUserId(), summary != null && summary.draw(),
                session.elapsedMs(now), terms, struggling);
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
