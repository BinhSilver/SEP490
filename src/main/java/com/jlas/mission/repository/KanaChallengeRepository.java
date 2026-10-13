package com.jlas.mission.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class KanaChallengeRepository {
    private final JdbcTemplate jdbc;

    /** Same JDBC-only, create-if-missing convention as the other mission tables (see migration 029). */
    public static final String CREATE_PUZZLE_TABLE = """
            CREATE TABLE IF NOT EXISTS daily_kana_puzzle (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
                challenge_date DATE NOT NULL,
                board_level VARCHAR(10) NOT NULL,
                puzzle_json TEXT NOT NULL,
                created_at TIMESTAMP(6) NOT NULL,
                UNIQUE KEY uk_daily_kana_puzzle_day_level (challenge_date, board_level)
            )
            """;

    public static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS daily_kana_challenge (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT UNSIGNED NOT NULL,
                challenge_date DATE NOT NULL,
                puzzle_id BIGINT UNSIGNED NULL,
                vocabulary_id BIGINT UNSIGNED NOT NULL,
                jlpt_level VARCHAR(10) NOT NULL,
                mixed_levels BOOLEAN NOT NULL DEFAULT FALSE,
                expression VARCHAR(255) NOT NULL,
                reading VARCHAR(255) NOT NULL,
                meaning TEXT NOT NULL,
                cells_json TEXT NOT NULL,
                solution_json TEXT NOT NULL,
                hint_count INT NOT NULL DEFAULT 0,
                started_at TIMESTAMP(6) NOT NULL,
                completed_at TIMESTAMP(6) NULL,
                elapsed_seconds INT NULL,
                UNIQUE KEY uk_daily_kana_user_puzzle (user_id, puzzle_id),
                KEY idx_daily_kana_board_rank (puzzle_id, elapsed_seconds, completed_at),
                CONSTRAINT fk_daily_kana_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                CONSTRAINT fk_daily_kana_puzzle FOREIGN KEY (puzzle_id) REFERENCES daily_kana_puzzle(id) ON DELETE CASCADE
            )
            """;

    public record Word(long id, String expression, String reading, String meaning, String level) {}
    /** One shared board for a local day and level (N5..N1 or MIX). */
    public record Board(long id, LocalDate date, String level, String puzzleJson) {}
    /** A learner's progress on one shared board. */
    public record Challenge(long id, long userId, Board board, String solutionJson, Instant startedAt,
                            Instant completedAt, Integer elapsedSeconds) {}
    public record RankRow(long userId, String name, String avatarUrl, int elapsedSeconds, Instant completedAt) {}

    public void lockUser(Long userId) {
        List<Long> users = jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
        if (users.isEmpty()) throw new com.jlas.common.exception.ApiException(
                com.jlas.common.exception.ErrorCode.UNAUTHORIZED);
    }

    public Optional<Board> findBoard(LocalDate day, String level) {
        return jdbc.query("SELECT * FROM daily_kana_puzzle WHERE challenge_date = ? AND board_level = ?",
                (rs, row) -> board(rs), day, level).stream().findFirst();
    }

    /** Locking read: sees a board committed by a concurrent first player after our insert lost the race. */
    public Optional<Board> findBoardForUpdate(LocalDate day, String level) {
        return jdbc.query("SELECT * FROM daily_kana_puzzle WHERE challenge_date = ? AND board_level = ? FOR UPDATE",
                (rs, row) -> board(rs), day, level).stream().findFirst();
    }

    /** Throws DuplicateKeyException when another player created today's board first. */
    public void createBoard(LocalDate day, String level, String puzzleJson, Instant now) {
        jdbc.update("INSERT INTO daily_kana_puzzle (challenge_date, board_level, puzzle_json, created_at) VALUES (?, ?, ?, ?)",
                day, level, puzzleJson, Timestamp.from(now));
    }

    private static final String CHALLENGE_SELECT = """
            SELECT c.id, c.user_id, c.solution_json, c.started_at, c.completed_at, c.elapsed_seconds,
                   p.id AS board_id, p.challenge_date AS board_date, p.board_level, p.puzzle_json
            FROM daily_kana_challenge c JOIN daily_kana_puzzle p ON p.id = c.puzzle_id
            """;

    public Optional<Challenge> find(Long userId, long boardId) {
        return jdbc.query(CHALLENGE_SELECT + " WHERE c.user_id = ? AND c.puzzle_id = ?",
                (rs, row) -> challenge(rs), userId, boardId).stream().findFirst();
    }

    public Optional<Challenge> findById(Long userId, long id) {
        return jdbc.query(CHALLENGE_SELECT + " WHERE c.user_id = ? AND c.id = ?",
                (rs, row) -> challenge(rs), userId, id).stream().findFirst();
    }

    /** Active stage wins over a distant final target; no path means mixed N5..N1. */
    public Optional<String> levelFor(Long userId) {
        return jdbc.query("""
                SELECT COALESCE(
                    (SELECT s.level FROM user_learning_stage s WHERE s.learning_path_id = p.id
                        AND s.status = 'ACTIVE' ORDER BY s.stage_index LIMIT 1),
                    NULLIF(p.target_level, ''), NULLIF(p.current_level, '')) AS level
                FROM user_learning_path p WHERE p.user_id = ? AND p.status IN ('ACTIVE', 'COMPLETED')
                ORDER BY CASE WHEN p.status = 'ACTIVE' THEN 0 ELSE 1 END, p.id DESC LIMIT 1
                """, (rs, row) -> rs.getString("level"), userId).stream()
                .filter(java.util.Objects::nonNull).filter(s -> s.matches("N[1-5]")).findFirst();
    }

    public List<Word> words(String level) {
        return jdbc.query("""
                SELECT id, expression, reading, meaning, jlpt_level FROM vocabulary_entry
                WHERE dictionary_status = 'PUBLISHED' AND jlpt_level IN ('N5','N4','N3','N2','N1')
                    AND meaning IS NOT NULL AND TRIM(meaning) <> ''
                """ + (level == null ? "" : " AND jlpt_level = ?") + " ORDER BY id",
                (rs, row) -> new Word(rs.getLong("id"), rs.getString("expression"), rs.getString("reading"),
                        rs.getString("meaning"), rs.getString("jlpt_level")),
                level == null ? new Object[]{} : new Object[]{level});
    }

    /**
     * The board itself lives in daily_kana_puzzle; the legacy NOT NULL columns keep the first word
     * as a readable summary and cells_json stays empty.
     */
    public void create(Long userId, Board board, Word first, String progress, Instant now) {
        jdbc.update("""
                INSERT INTO daily_kana_challenge
                (user_id, challenge_date, puzzle_id, vocabulary_id, jlpt_level, mixed_levels, expression, reading,
                    meaning, cells_json, solution_json, started_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?)
                """, userId, board.date(), board.id(), first.id(), board.level(), "MIX".equals(board.level()),
                first.expression(), first.reading(), first.meaning(), progress, Timestamp.from(now));
    }

    public void saveProgress(long id, String progress) {
        jdbc.update("UPDATE daily_kana_challenge SET solution_json = ? WHERE id = ?", progress, id);
    }

    public void complete(long id, Instant now, int seconds) {
        jdbc.update("""
                UPDATE daily_kana_challenge SET completed_at = ?, elapsed_seconds = ?
                WHERE id = ? AND completed_at IS NULL
                """, Timestamp.from(now), seconds, id);
    }

    private static final String RANK_ORDER = " ORDER BY c.elapsed_seconds, c.completed_at, c.id";

    /** Fastest finishers of one board. Names never fall back to e-mail, which is private. */
    public List<RankRow> topFinishers(long boardId, int limit) {
        return jdbc.query("""
                SELECT c.user_id, COALESCE(NULLIF(TRIM(u.full_name), ''), NULLIF(TRIM(u.username), '')) AS name,
                       u.avatar_url, c.elapsed_seconds, c.completed_at
                FROM daily_kana_challenge c JOIN users u ON u.id = c.user_id
                WHERE c.puzzle_id = ? AND c.completed_at IS NOT NULL
                """ + RANK_ORDER + " LIMIT ?",
                (rs, row) -> new RankRow(rs.getLong("user_id"), rs.getString("name"), rs.getString("avatar_url"),
                        rs.getInt("elapsed_seconds"), rs.getTimestamp("completed_at").toInstant()),
                boardId, limit);
    }

    public int finisherCount(long boardId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM daily_kana_challenge WHERE puzzle_id = ? AND completed_at IS NOT NULL",
                Integer.class, boardId);
        return count == null ? 0 : count;
    }

    /** 1-based position of a finished row, using the same tie-break as {@link #topFinishers}. */
    public int rankOf(long boardId, Challenge mine) {
        Integer ahead = jdbc.queryForObject("""
                SELECT COUNT(*) FROM daily_kana_challenge c
                WHERE c.puzzle_id = ? AND c.completed_at IS NOT NULL AND (c.elapsed_seconds < ?
                    OR (c.elapsed_seconds = ? AND (c.completed_at < ? OR (c.completed_at = ? AND c.id < ?))))
                """, Integer.class, boardId, mine.elapsedSeconds(), mine.elapsedSeconds(),
                Timestamp.from(mine.completedAt()), Timestamp.from(mine.completedAt()), mine.id());
        return (ahead == null ? 0 : ahead) + 1;
    }

    private static Board board(ResultSet rs) throws SQLException {
        return new Board(rs.getLong("id"), rs.getObject("challenge_date", LocalDate.class),
                rs.getString("board_level"), rs.getString("puzzle_json"));
    }

    private static Challenge challenge(ResultSet rs) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new Challenge(rs.getLong("id"), rs.getLong("user_id"),
                new Board(rs.getLong("board_id"), rs.getObject("board_date", LocalDate.class),
                        rs.getString("board_level"), rs.getString("puzzle_json")),
                rs.getString("solution_json"), rs.getTimestamp("started_at").toInstant(),
                completed == null ? null : completed.toInstant(), (Integer) rs.getObject("elapsed_seconds"));
    }
}
