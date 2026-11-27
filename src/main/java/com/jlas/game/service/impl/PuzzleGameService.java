package com.jlas.game.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.dto.PuzzleDtos.AnswerAction;
import com.jlas.game.dto.PuzzleDtos.AnswerReply;
import com.jlas.game.dto.PuzzleDtos.InkAction;
import com.jlas.game.dto.PuzzleDtos.InkDelta;
import com.jlas.game.dto.PuzzleDtos.View;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.SessionStatus;
import com.jlas.game.runtime.ArenaEventType;
import com.jlas.game.runtime.ArenaRateLimiter;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.GameSessionRegistry;
import com.jlas.game.runtime.LaunchRequest;
import com.jlas.game.runtime.puzzle.PuzzleContent;
import com.jlas.game.runtime.puzzle.PuzzleSession;
import com.jlas.game.service.GameEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

@Service
@RequiredArgsConstructor
public class PuzzleGameService {
    private static final List<String> SPHINX_MAPS = List.of("desert", "night");
    private final GameSessionRegistry sessions;
    private final GameSessionLifecycle lifecycle;
    private final ArenaRateLimiter rateLimiter;
    private final PuzzleContent content;

    @Bean
    public GameEngine sphinxEngine() { return new Engine(GameType.SPHINX_RIDDLE); }

    @Bean
    public GameEngine pictureRebusEngine() { return new Engine(GameType.PICTURE_REBUS); }

    @Bean
    public GameEngine drawGuessEngine() { return new Engine(GameType.DRAW_GUESS); }

    private final class Engine implements GameEngine {
        private final GameType type;

        private Engine(GameType type) { this.type = type; }

        @Override
        public GameType type() { return type; }

        @Override
        public GameSession launch(LaunchRequest request) {
            List<PuzzleContent.Entry> entries = new ArrayList<>(content.forLevel(type, request.jlpt()));
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Collections.shuffle(entries, random);
            int rounds = Math.min(entries.size(), type == GameType.DRAW_GUESS ? 4 : 5);
            String map = type == GameType.SPHINX_RIDDLE ? SPHINX_MAPS.get(random.nextInt(SPHINX_MAPS.size())) : null;
            int drawer = type == GameType.DRAW_GUESS ? random.nextInt(2) : 0;
            PuzzleSession session = new PuzzleSession(type, request, Instant.now(), entries.subList(0, rounds), map, drawer);
            sessions.register(session);
            return session;
        }

        @Override
        public void forfeit(GameSession session, long userId) {
            Set<Long> forfeited = type == GameType.DRAW_GUESS ? Set.of() : Set.of(userId);
            update((PuzzleSession) session, null, forfeited, (game, now) -> {
                game.finish(now, userId);
                return null;
            });
        }

        @Override
        public void presenceChanged(GameSession session, long userId, boolean online) {
            update((PuzzleSession) session, null, Set.of(), (game, now) -> {
                game.presence(userId, online);
                return null;
            });
        }

        @Override
        public void tick(Instant now) {
            for (GameSession session : sessions.of(type)) {
                update((PuzzleSession) session, now, Set.of(), (game, instant) -> {
                    game.advance(instant);
                    return null;
                });
            }
        }

        @Override
        public void abort(GameSession session) {
            update((PuzzleSession) session, null, Set.of(), (game, now) -> {
                game.abort(now);
                return null;
            });
        }
    }

    public View state(long userId, String id) {
        return withCurrentRound(userId, id, (game, now) -> game.view(userId, now));
    }

    public AnswerReply answer(long userId, String id, AnswerAction action) {
        rateLimiter.check(userId, "puzzle-answer", 100);
        return withCurrentRound(userId, id, (game, now) ->
                new AnswerReply(game.answer(userId, action.round(), action.answer(), now), action.round()));
    }

    public void skip(long userId, String id, int round) {
        rateLimiter.check(userId, "puzzle-round-action", 60);
        withCurrentRound(userId, id, (game, now) -> {
            game.skip(userId, round, now);
            return null;
        });
    }

    public void ready(long userId, String id, int round) {
        rateLimiter.check(userId, "puzzle-round-action", 60);
        withCurrentRound(userId, id, (game, now) -> {
            game.ready(userId, round, now);
            return null;
        });
    }

    public InkDelta ink(long userId, String id, InkAction action) {
        rateLimiter.check(userId, "puzzle-ink", 800);
        PuzzleSession session = sessions.require(id, PuzzleSession.class, userId);
        InkDelta delta = session.withLock(() -> session.ink(userId, action, Instant.now()));
        lifecycle.broadcast(session, ArenaEventType.PUZZLE_INK, delta);
        return delta;
    }

    public Resource image(long userId, String id, int round, String name) {
        boolean available = withCurrentRound(userId, id, (game, now) -> game.canReadImage(round, name));
        if (!available) throw new ApiException(ErrorCode.NOT_FOUND, "Ảnh chưa có trong lượt này");
        return new ClassPathResource("game-content/rebus/" + name);
    }

    private <T> T withCurrentRound(long userId, String id, BiFunction<PuzzleSession, Instant, T> action) {
        PuzzleSession session = sessions.require(id, PuzzleSession.class, userId);
        return update(session, null, Set.of(), (game, now) -> {
            game.advance(now);
            return action.apply(game, now);
        });
    }

    private <T> T update(PuzzleSession session, Instant tickTime, Set<Long> forfeited,
                         BiFunction<PuzzleSession, Instant, T> action) {
        // Carry the transition outside the lock. Even a late answer may discover that the round has expired.
        Transition[] update = new Transition[1];
        try {
            return session.withLock(() -> {
                long version = session.version();
                SessionStatus before = session.getStatus();
                try {
                    return action.apply(session, tickTime == null ? Instant.now() : tickTime);
                } finally {
                    if (version != session.version()) {
                        update[0] = new Transition(session.version(), before, session.getStatus(), session.getSummary());
                    }
                }
            });
        } finally {
            Transition change = update[0];
            if (change != null) {
                lifecycle.broadcast(session, ArenaEventType.PUZZLE_STATE, Map.of("version", change.version()));
                if (!change.before().isTerminal() && change.after() == SessionStatus.FINISHED) {
                    lifecycle.completed(session, change.result(), null, forfeited);
                } else if (!change.before().isTerminal() && change.after() == SessionStatus.ABORTED) {
                    lifecycle.aborted(session);
                }
            }
        }
    }

    private record Transition(long version, SessionStatus before, SessionStatus after, ResultSummary result) { }
}
