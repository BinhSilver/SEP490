package com.jlas.game.service.impl;

import com.jlas.auth.repository.UserRepository;
import com.jlas.flashcard.repository.FlashcardSetRepository;
import com.jlas.game.dto.GameDtos.ResultLine;
import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.entity.AirDefenseResult;
import com.jlas.game.entity.GameMatch;
import com.jlas.game.entity.GameMatchPlayer;
import com.jlas.game.entity.MemoryMatchResult;
import com.jlas.game.enums.GameType;
import com.jlas.game.repository.AirDefenseResultRepository;
import com.jlas.game.repository.GameMatchPlayerRepository;
import com.jlas.game.repository.GameMatchRepository;
import com.jlas.game.repository.MemoryMatchResultRepository;
import com.jlas.game.runtime.PlayerSeat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lưu trận vào DB: chỉ lưu lúc bắt đầu và lúc kết thúc (người chơi, điểm, thắng/thua), không lưu từng
 * thao tác. Trận chơi đơn cũng được lưu để bộ đã chơi hiện ở mục "Xem gần đây".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameMatchRecorder {

    /** game_match.room_code của trận chơi đơn (không có phòng). */
    public static final String SOLO_ROOM_CODE = "SOLO";

    private final GameMatchRepository matches;
    private final GameMatchPlayerRepository matchPlayers;
    private final MemoryMatchResultRepository memoryResults;
    private final AirDefenseResultRepository airResults;
    private final FlashcardSetRepository sets;
    private final UserRepository users;
    private final ObjectMapper objectMapper;

    @Transactional
    public Long start(GameType type, String roomId, String roomCode, Long setId, List<PlayerSeat> seats,
                      Object settings) {
        LocalDateTime now = LocalDateTime.now();
        GameMatch match = matches.save(GameMatch.builder()
                .flashcardSet(sets.getReferenceById(setId))
                .roomId(roomId)
                .roomCode(roomCode)
                .gameType(type.name())
                .status("IN_PROGRESS")
                .startedAt(now)
                .settingsSnapshot(json(settings))
                .build());
        for (PlayerSeat seat : seats) {
            matchPlayers.save(GameMatchPlayer.builder()
                    .match(match)
                    .user(users.getReferenceById(seat.userId()))
                    .displayName(truncate(seat.displayName(), 150))
                    .slot(seat.slot())
                    .joinedAt(now)
                    .build());
        }
        return match.getMatchId();
    }

    /**
     * @param forfeited người chơi đã rời trận giữa chừng
     */
    @Transactional
    public void finish(Long matchId, ResultSummary summary, boolean aborted, Set<Long> forfeited,
                       Set<Long> disconnected) {
        if (matchId == null) {
            return;
        }
        GameMatch match = matches.findById(matchId).orElse(null);
        if (match == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        match.setStatus(aborted ? "ABORTED" : "COMPLETED");
        match.setEndedAt(now.isBefore(match.getStartedAt()) ? match.getStartedAt() : now);
        if (!aborted && summary != null && summary.winnerUserId() != null) {
            match.setWinnerUser(users.getReferenceById(summary.winnerUserId()));
        }
        Map<Long, ResultLine> lines = summary == null ? Map.of()
                : summary.lines().stream().collect(Collectors.toMap(ResultLine::userId, Function.identity(),
                (first, second) -> first));
        for (GameMatchPlayer player : matchPlayers.findByMatchMatchId(matchId)) {
            long userId = player.getUser().getId();
            ResultLine line = lines.get(userId);
            if (line != null) {
                player.setScore((int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, line.score())));
            }
            player.setDisconnected(disconnected.contains(userId));
            player.setResult(resultOf(userId, summary, aborted, forfeited));
        }
    }

    @Transactional
    public void recordMemory(MemoryMatchResult result) {
        memoryResults.save(result);
    }

    @Transactional
    public void recordAirDefense(AirDefenseResult result) {
        airResults.save(result);
    }

    public GameMatch matchRef(Long matchId) {
        return matchId == null ? null : matches.getReferenceById(matchId);
    }

    public com.jlas.auth.entity.User userRef(long userId) {
        return users.getReferenceById(userId);
    }

    private static String resultOf(long userId, ResultSummary summary, boolean aborted, Set<Long> forfeited) {
        if (forfeited.contains(userId)) {
            return "FORFEIT";
        }
        if (aborted || summary == null) {
            return "PENDING";
        }
        if (summary.draw()) {
            return "DRAW";
        }
        return summary.winnerUserId() != null && summary.winnerUserId() == userId ? "WIN" : "LOSE";
    }

    /** Luyện tập đổi cài đặt ngay trước khi bắt đầu: ghi lại cài đặt thật của trận. */
    @Transactional
    public void updateSettings(Long matchId, Object settings) {
        if (matchId == null) {
            return;
        }
        matches.findById(matchId).ifPresent(match -> match.setSettingsSnapshot(json(settings)));
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            log.warn("Không ghi được settings snapshot: {}", ex.getMessage());
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "Người chơi";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
