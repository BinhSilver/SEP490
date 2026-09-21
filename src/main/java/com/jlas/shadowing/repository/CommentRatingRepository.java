package com.jlas.shadowing.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CommentRatingRepository {
    private final JdbcTemplate jdbc;

    public CommentRatingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(Long commentId, int rating) {
        jdbc.update("INSERT INTO content_comment_rating (comment_id, rating) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE rating = VALUES(rating)", commentId, rating);
    }

    public Optional<Integer> find(Long commentId) {
        return jdbc.query("SELECT rating FROM content_comment_rating WHERE comment_id = ?",
                rs -> rs.next() ? Optional.of(rs.getInt("rating")) : Optional.empty(), commentId);
    }

    public void delete(Long commentId) {
        jdbc.update("DELETE FROM content_comment_rating WHERE comment_id = ?", commentId);
    }
}
