package com.jlas.mission.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.mission.config.MissionClock;
import com.jlas.mission.dto.KanaChallengeDtos.Attempt;
import com.jlas.mission.repository.KanaChallengeRepository;
import com.jlas.mission.repository.KanaChallengeRepository.Board;
import com.jlas.mission.repository.KanaChallengeRepository.Challenge;
import com.jlas.mission.repository.KanaChallengeRepository.Word;
import com.jlas.mission.service.KanaChallengeService.Progress;
import com.jlas.mission.util.MultiWordPuzzleGenerator.Entry;
import com.jlas.mission.util.MultiWordPuzzleGenerator.Puzzle;
import com.jlas.mission.util.MultiWordPuzzleGenerator.Solution;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo shortcuts for Kana Connect, used only by the dev tools (mission.dev-tools=true). Every action
 * works on the caller's own progress row; demo players use the reserved .invalid e-mail domain.
 */
@Service
@RequiredArgsConstructor
public class KanaDemoService {
    static final String DEMO_EMAIL = "kana.demo%02d@jlas-demo.invalid";
    static final List<String> DEMO_NAMES = List.of("Minh Anh", "Gia Huy", "Bảo Ngọc", "Đức Thịnh", "Khánh Linh",
            "Quốc Bảo", "Thu Trang", "Hoàng Nam", "Phương Vy", "Tuấn Kiệt", "Hải Yến", "Nhật Minh");

    private final KanaChallengeService service;
    private final KanaChallengeRepository repository;
    private final JdbcTemplate jdbc;
    private final MissionClock clock;
    private final UserZoneService zones;
    private final ObjectMapper json;

    /** Makes the next hint available right now. */
    @Transactional
    public void hintReady(Long userId, Long challengeId) {
        Challenge challenge = own(userId, challengeId);
        Progress progress = read(challenge.solutionJson(), Progress.class);
        long readyAt = progress.playedMs(clock.now()) - KanaChallengeService.HINT_COOLDOWN.toMillis();
        repository.saveProgress(challenge.id(), write(new Progress(progress.solvedWordIds(), progress.reveals(),
                progress.activeMs(), progress.resumedAt(), readyAt)));
    }

    /** Solves the next unsolved word, or every remaining one, through the normal submit flow. */
    public int solve(Long userId, Long challengeId, boolean all) {
        Challenge challenge = own(userId, challengeId);
        Puzzle puzzle = read(challenge.board().puzzleJson(), Puzzle.class);
        List<Long> solved = read(challenge.solutionJson(), Progress.class).solvedWordIds();
        int count = 0;
        for (Solution solution : puzzle.solutions()) {
            if (solved.contains(solution.wordId())) continue;
            service.submit(userId, new Attempt(challenge.id(), solution.path()));
            count++;
            if (!all) break;
        }
        return count;
    }

    /** Adds play time to this board, so its clock and the hint cooldown move on. */
    @Transactional
    public void addPlayTime(Long userId, Long challengeId, int seconds) {
        Challenge challenge = own(userId, challengeId);
        if (challenge.completedAt() != null) return;
        Progress p = read(challenge.solutionJson(), Progress.class);
        repository.saveProgress(challenge.id(), write(new Progress(p.solvedWordIds(), p.reveals(),
                p.activeMs() + seconds * 1000L, p.resumedAt(), p.hintActiveMs())));
    }

    /** Drops the caller's progress on this board; opening it again starts fresh. */
    @Transactional
    public void restart(Long userId, Long challengeId) {
        Challenge challenge = own(userId, challengeId);
        jdbc.update("DELETE FROM daily_kana_challenge WHERE id = ? AND user_id = ?", challenge.id(), userId);
    }

    /** Adds twelve fictional finishers (and two still playing) to this week's board of one level. */
    @Transactional
    public int addDemoPlayers(Long userId, String level) {
        String boardLevel = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        LocalDate day = KanaChallengeService.weekOf(clock.today(zones.zoneOf(userId)));
        Board board = repository.findBoard(day, boardLevel)
                .orElseThrow(() -> new ApiException(ErrorCode.KANA_CHALLENGE_UNAVAILABLE, "Hãy mở bàn cấp này trước khi thêm người chơi demo."));
        Puzzle puzzle = read(board.puzzleJson(), Puzzle.class);
        Entry first = puzzle.words().getFirst();
        Word summary = new Word(first.id(), first.expression(), first.reading(), first.meaning(), first.level());
        List<Long> allWords = puzzle.words().stream().map(Entry::id).toList();
        Instant now = clock.now();
        int added = 0;
        for (int i = 0; i < DEMO_NAMES.size(); i++) {
            long demoUser = demoUser(i);
            if (repository.find(demoUser, board.id()).isPresent()) continue;
            boolean finished = i % 6 != 5;
            int elapsed = 45 + (int) ((i * 37L + board.id() * 53L) % 420);
            Instant started = now.minusSeconds(600 + i * 240L);
            repository.create(demoUser, board, summary,
                    write(new Progress(finished ? allWords : allWords.subList(0, 2))), started);
            if (finished) {
                Challenge row = repository.find(demoUser, board.id()).orElseThrow();
                repository.complete(row.id(), started.plusSeconds(elapsed), elapsed);
            }
            added++;
        }
        return added;
    }

    /** Removes every demo player and, through ON DELETE CASCADE, their progress. */
    @Transactional
    public int clearDemoPlayers() {
        return jdbc.update("DELETE FROM users WHERE email LIKE 'kana.demo%@jlas-demo.invalid'");
    }

    private long demoUser(int index) {
        String email = String.format(DEMO_EMAIL, index + 1);
        Timestamp created = Timestamp.from(clock.now().minusSeconds(30L * 86400));
        jdbc.update("""
                INSERT INTO users (role_id, email, username, password_hash, login_type, full_name, japanese_level,
                                   is_verified, created_at, updated_at)
                SELECT r.id, ?, ?, NULL, 'LOCAL', ?, 'N5', 0, ?, ? FROM roles r
                WHERE r.name = 'LEARNER' AND NOT EXISTS (SELECT 1 FROM users u WHERE u.email = ?)
                """, email, String.format("kana_demo%02d", index + 1), DEMO_NAMES.get(index), created, created, email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private Challenge own(Long userId, Long challengeId) {
        return repository.findById(userId, challengeId)
                .orElseThrow(() -> new ApiException(ErrorCode.KANA_CHALLENGE_EXPIRED));
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
