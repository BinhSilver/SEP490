package com.jlas.game.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Truy vấn đọc cho bộ chọn flashcard của Game Arena. Dùng JDBC để lấy tên chủ bộ, số thẻ và độ phủ
 * 4 trường trong một lượt, tránh N+1 khi hiển thị danh sách.
 */
@Repository
@RequiredArgsConstructor
public class GameDeckJdbcRepository {

    private static final String SET_COLUMNS = """
            SELECT s.id, s.title, s.description, s.thumbnail_url, s.visibility, s.is_draft, s.owner_id,
                   s.updated_at,
                   COALESCE(NULLIF(TRIM(u.full_name), ''), NULLIF(TRIM(u.username), ''),
                            SUBSTRING_INDEX(u.email, '@', 1)) AS owner_name
            """;

    /** Người chơi được dùng bộ của mình (kể cả riêng tư) và bộ công khai đã xuất bản. */
    private static final String ACCESSIBLE = """
            s.deleted_at IS NULL
            AND (s.owner_id = :userId OR (s.visibility = 'PUBLIC' AND s.is_draft = 0))
            """;

    /**
     * Lọc "chơi được" (khi {@code :minPair} khác null): bộ đã xuất bản và có ít nhất một cặp hỏi – đáp mà số thẻ
     * có đủ cả hai trường đạt {@code :minPair}. Điều kiện từng trường giống {@link #coverage}; khi chọn bộ,
     * server vẫn kiểm lại chính xác (bỏ trùng) như cũ.
     */
    private static final String PLAYABLE = """
            AND (:minPair IS NULL OR (s.is_draft = 0 AND s.id IN (
              SELECT c.set_id FROM (
                SELECT set_id,
                  TRIM(COALESCE(front, '')) <> '' AS k,
                  (TRIM(COALESCE(reading, '')) <> '' OR COALESCE(back, '') LIKE '% — %') AS h,
                  (TRIM(COALESCE(meaning, '')) <> '' OR TRIM(COALESCE(back, '')) <> '') AS m,
                  TRIM(COALESCE(han_viet, '')) <> '' AS v
                FROM flashcard_card
              ) c GROUP BY c.set_id
              HAVING GREATEST(SUM(c.k AND c.h), SUM(c.k AND c.m), SUM(c.k AND c.v),
                              SUM(c.h AND c.m), SUM(c.h AND c.v), SUM(c.m AND c.v)) >= :minPair)))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public record SetRow(long id, String title, String description, String thumbnail, String visibility,
                         boolean draft, long ownerId, String ownerName, LocalDateTime updatedAt,
                         LocalDateTime lastUsedAt) {
    }

    public record CoverageRow(long setId, int total, int kanji, int hiragana, int meaning, int hanViet) {
    }

    public List<SetRow> mine(long userId, String like, Integer minPair, int limit, int offset) {
        String sql = SET_COLUMNS + ", NULL AS last_used FROM flashcard_set s JOIN users u ON u.id = s.owner_id "
                + "WHERE s.deleted_at IS NULL AND s.owner_id = :userId "
                + "AND (:like IS NULL OR LOWER(s.title) LIKE :like) " + PLAYABLE
                + "ORDER BY s.updated_at DESC, s.id DESC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params(userId, like, minPair, limit, offset), SET_ROW);
    }

    /** Bộ của mình lên trước, sau đó bộ công khai mới cập nhật. */
    public List<SetRow> all(long userId, String like, Integer minPair, int limit, int offset) {
        String sql = SET_COLUMNS + ", NULL AS last_used FROM flashcard_set s JOIN users u ON u.id = s.owner_id "
                + "WHERE " + ACCESSIBLE + " AND (:like IS NULL OR LOWER(s.title) LIKE :like) " + PLAYABLE
                + "ORDER BY (s.owner_id = :userId) DESC, s.updated_at DESC, s.id DESC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params(userId, like, minPair, limit, offset), SET_ROW);
    }

    /**
     * Bộ đã mở/học/chơi gần đây: lần mở bộ (flashcard_user_set_state), phiên học/quiz
     * (flashcard_study_session) và trận game (game_match).
     */
    public List<SetRow> recent(long userId, String like, Integer minPair, int limit, int offset) {
        String sql = SET_COLUMNS + ", r.last_used FROM ("
                + "  SELECT set_id, MAX(ts) AS last_used FROM ("
                + "    SELECT set_id, last_accessed_at AS ts FROM flashcard_user_set_state"
                + "      WHERE user_id = :userId AND last_accessed_at IS NOT NULL"
                + "    UNION ALL"
                + "    SELECT set_id, started_at AS ts FROM flashcard_study_session WHERE user_id = :userId"
                + "    UNION ALL"
                + "    SELECT gm.flashcard_set_id AS set_id, gm.started_at AS ts FROM game_match gm"
                + "      JOIN game_match_player gp ON gp.match_id = gm.match_id WHERE gp.user_id = :userId"
                + "  ) used GROUP BY set_id"
                + ") r JOIN flashcard_set s ON s.id = r.set_id JOIN users u ON u.id = s.owner_id "
                + "WHERE " + ACCESSIBLE + " AND (:like IS NULL OR LOWER(s.title) LIKE :like) " + PLAYABLE
                + "ORDER BY r.last_used DESC, s.id DESC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params(userId, like, minPair, limit, offset), SET_ROW);
    }

    /** Số thẻ và số thẻ có từng trường (Kanji / cách đọc / nghĩa / Hán Việt) của mỗi bộ. */
    public Map<Long, CoverageRow> coverage(Collection<Long> setIds) {
        Map<Long, CoverageRow> result = new HashMap<>();
        if (setIds.isEmpty()) {
            return result;
        }
        String sql = """
                SELECT set_id, COUNT(*) AS total,
                  SUM(CASE WHEN TRIM(COALESCE(front, '')) <> '' THEN 1 ELSE 0 END) AS kanji,
                  SUM(CASE WHEN TRIM(COALESCE(reading, '')) <> '' OR COALESCE(back, '') LIKE '% — %'
                           THEN 1 ELSE 0 END) AS hiragana,
                  SUM(CASE WHEN TRIM(COALESCE(meaning, '')) <> '' OR TRIM(COALESCE(back, '')) <> ''
                           THEN 1 ELSE 0 END) AS meaning,
                  SUM(CASE WHEN TRIM(COALESCE(han_viet, '')) <> '' THEN 1 ELSE 0 END) AS han_viet
                FROM flashcard_card WHERE set_id IN (:ids) GROUP BY set_id
                """;
        jdbc.query(sql, new MapSqlParameterSource("ids", setIds), rs -> {
            long setId = rs.getLong("set_id");
            result.put(setId, new CoverageRow(setId, rs.getInt("total"), rs.getInt("kanji"),
                    rs.getInt("hiragana"), rs.getInt("meaning"), rs.getInt("han_viet")));
        });
        return result;
    }

    private static MapSqlParameterSource params(long userId, String like, Integer minPair, int limit, int offset) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("like", like)
                .addValue("minPair", minPair)
                .addValue("limit", limit)
                .addValue("offset", offset);
    }

    private static final RowMapper<SetRow> SET_ROW = (rs, rowNum) -> new SetRow(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("thumbnail_url"),
            rs.getString("visibility"),
            rs.getBoolean("is_draft"),
            rs.getLong("owner_id"),
            rs.getString("owner_name"),
            toLocal(rs.getTimestamp("updated_at")),
            toLocal(rs.getTimestamp("last_used")));

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
