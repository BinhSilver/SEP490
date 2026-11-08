package com.jlas.community.handler;

import com.jlas.community.enums.CommunityContentType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FLASHCARD_SET: đọc thẳng bảng flashcard_set. Bộ thẻ không có cột cấp độ riêng nên cấp độ lấy theo
 * jlpt_level (N5..N1) xuất hiện nhiều nhất trong các thẻ của bộ; bộ không có thẻ gắn cấp độ thì null.
 */
@Component
@RequiredArgsConstructor
public class FlashcardSetCommunityHandler implements CommunityContentTypeHandler {

    /** Công khai ở nguồn: PUBLIC, không draft, chưa xoá mềm, không bị moderation chặn. */
    private static final String PUBLIC_AT_SOURCE = "s.visibility = 'PUBLIC' AND s.is_draft = 0 "
            + "AND s.deleted_at IS NULL AND s.moderation_status NOT IN ('BLOCKED','UNSAFE','REMOVED')";

    private static final String LEVEL = "(SELECT fc.jlpt_level FROM flashcard_card fc WHERE fc.set_id = s.id "
            + "AND fc.jlpt_level IN ('N5','N4','N3','N2','N1') GROUP BY fc.jlpt_level "
            + "ORDER BY COUNT(*) DESC, fc.jlpt_level DESC LIMIT 1)";

    private static final String LINK = "CONCAT('/flashcard/study.html?setId=', s.id)";

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public String contentType() {
        return CommunityContentType.FLASHCARD_SET;
    }

    @Override
    public Optional<CommunitySource> resolve(Long contentId) {
        if (contentId == null) return Optional.empty();
        String sql = "SELECT s.id, s.title, s.description, s.owner_id, s.thumbnail_url, s.created_at, "
                + LEVEL + " AS jlpt_level, " + LINK + " AS link, "
                + "(CASE WHEN " + PUBLIC_AT_SOURCE + " THEN 1 ELSE 0 END) AS is_public, "
                + "(SELECT COUNT(*) FROM flashcard_card fc2 WHERE fc2.set_id = s.id) AS card_count "
                + "FROM flashcard_set s WHERE s.id = :id AND s.deleted_at IS NULL";
        List<CommunitySource> rows = jdbc.query(sql, new MapSqlParameterSource("id", contentId), (rs, i) -> {
            Timestamp created = rs.getTimestamp("created_at");
            return new CommunitySource(contentType(), rs.getLong("id"), rs.getString("title"),
                    rs.getString("description"), rs.getLong("owner_id"), rs.getString("thumbnail_url"),
                    rs.getString("link"), rs.getString("jlpt_level"),
                    created == null ? null : created.toLocalDateTime(), rs.getInt("is_public") == 1,
                    Map.of("cardCount", rs.getLong("card_count")), null);
        });
        return rows.stream().findFirst();
    }

    @Override
    public String feedSelectSql() {
        return "SELECT '" + CommunityContentType.FLASHCARD_SET + "' AS content_type, "
                + "s.id AS content_id, s.title AS title, s.description AS description, s.owner_id AS owner_id, "
                + "s.thumbnail_url AS thumbnail_url, " + LEVEL + " AS jlpt_level, s.created_at AS created_at, "
                + LINK + " AS link, NULL AS ext_rating_avg, NULL AS ext_rating_count, NULL AS ext_my_rating "
                + "FROM flashcard_set s WHERE " + PUBLIC_AT_SOURCE;
    }
}
