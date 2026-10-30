package com.jlas.shadowing.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replies and reactions of comments (migration 056). JDBC like {@link CommentRatingRepository}: the
 * columns are added at startup, so they stay out of the JPA entity that schema validation checks.
 */
@Repository
public class CommentThreadRepository {
    /** Reactions a learner can leave on a comment, in the order the picker shows them. */
    public static final List<String> REACTIONS = List.of("LIKE", "LOVE", "HAHA", "WOW", "SAD", "CLAP");

    private final NamedParameterJdbcTemplate jdbc;

    public CommentThreadRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** comment id → the top-level comment it replies to, for the comments that are replies. */
    public Map<Long, Long> parents(Collection<Long> commentIds) {
        Map<Long, Long> parents = new HashMap<>();
        if (commentIds.isEmpty()) return parents;
        jdbc.query("SELECT id, parent_id FROM content_comment WHERE id IN (:ids) AND parent_id IS NOT NULL",
                new MapSqlParameterSource("ids", commentIds),
                rs -> { parents.put(rs.getLong("id"), rs.getLong("parent_id")); });
        return parents;
    }

    public void setParent(Long commentId, Long parentId) {
        jdbc.update("UPDATE content_comment SET parent_id = :parent WHERE id = :id",
                new MapSqlParameterSource("parent", parentId).addValue("id", commentId));
    }

    /** comment id → reaction → how many people left it, in {@link #REACTIONS} order. */
    public Map<Long, Map<String, Long>> counts(Collection<Long> commentIds) {
        Map<Long, Map<String, Long>> counts = new HashMap<>();
        if (commentIds.isEmpty()) return counts;
        jdbc.query("SELECT comment_id, reaction, COUNT(*) AS total FROM content_comment_reaction "
                        + "WHERE comment_id IN (:ids) GROUP BY comment_id, reaction",
                new MapSqlParameterSource("ids", commentIds),
                rs -> { counts.computeIfAbsent(rs.getLong("comment_id"), id -> new HashMap<>())
                        .put(rs.getString("reaction"), rs.getLong("total")); });
        Map<Long, Map<String, Long>> ordered = new HashMap<>();
        counts.forEach((id, byReaction) -> {
            Map<String, Long> sorted = new LinkedHashMap<>();
            REACTIONS.forEach(r -> { if (byReaction.containsKey(r)) sorted.put(r, byReaction.get(r)); });
            ordered.put(id, sorted);
        });
        return ordered;
    }

    /** comment id → the reaction this user left on it. */
    public Map<Long, String> mine(Collection<Long> commentIds, Long userId) {
        Map<Long, String> mine = new HashMap<>();
        if (commentIds.isEmpty() || userId == null) return mine;
        jdbc.query("SELECT comment_id, reaction FROM content_comment_reaction WHERE comment_id IN (:ids) AND user_id = :user",
                new MapSqlParameterSource("ids", commentIds).addValue("user", userId),
                rs -> { mine.put(rs.getLong("comment_id"), rs.getString("reaction")); });
        return mine;
    }

    /** Picking the reaction already left removes it; another one replaces it. Returns the reaction now left, or null. */
    public String toggle(Long commentId, Long userId, String reaction) {
        String current = mine(List.of(commentId), userId).get(commentId);
        MapSqlParameterSource params = new MapSqlParameterSource("comment", commentId).addValue("user", userId)
                .addValue("reaction", reaction);
        if (reaction.equals(current)) {
            jdbc.update("DELETE FROM content_comment_reaction WHERE comment_id = :comment AND user_id = :user", params);
            return null;
        }
        jdbc.update("INSERT INTO content_comment_reaction (comment_id, user_id, reaction) VALUES (:comment, :user, :reaction) "
                + "ON DUPLICATE KEY UPDATE reaction = VALUES(reaction), created_at = CURRENT_TIMESTAMP(6)", params);
        return reaction;
    }
}
