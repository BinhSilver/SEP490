package com.jlas.game.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Khung đã mua ({@code user_avatar_frame}) và khung + màu tên đang dùng ({@code user_profile_style}). */
@Repository
@RequiredArgsConstructor
public class AvatarFrameJdbcRepository {

    /** Khung và màu tên đang dùng; null = không dùng khung / màu mặc định. */
    public record Style(String frameCode, String nameStyle) {
        public static final Style NONE = new Style(null, null);
    }

    private final NamedParameterJdbcTemplate jdbc;

    public Set<String> ownedCodes(long userId) {
        return new HashSet<>(jdbc.queryForList("SELECT frame_code FROM user_avatar_frame WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId), String.class));
    }

    public boolean owns(long userId, String frameCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_avatar_frame WHERE user_id = :userId AND frame_code = :code",
                new MapSqlParameterSource("userId", userId).addValue("code", frameCode), Integer.class);
        return count != null && count > 0;
    }

    /** Bỏ qua nếu đã có (bấm mua hai lần không tạo thêm dòng). */
    public void addOwned(long userId, String frameCode, int pricePaid) {
        jdbc.update("INSERT IGNORE INTO user_avatar_frame (user_id, frame_code, price_paid) VALUES (:userId, :code, :price)",
                new MapSqlParameterSource("userId", userId).addValue("code", frameCode).addValue("price", pricePaid));
    }

    public Style style(long userId) {
        List<Style> rows = jdbc.query("SELECT frame_code, name_style FROM user_profile_style WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId),
                (rs, i) -> new Style(rs.getString("frame_code"), rs.getString("name_style")));
        return rows.stream().findFirst().orElse(Style.NONE);
    }

    /** Khung + màu đã lưu của nhiều người (bảng xếp hạng, phòng game, bình luận); người chưa chọn không có trong map. */
    public Map<Long, Style> styles(Collection<Long> userIds) {
        Map<Long, Style> result = new HashMap<>();
        if (userIds.isEmpty()) return result;
        jdbc.query("SELECT user_id, frame_code, name_style FROM user_profile_style WHERE user_id IN (:ids)",
                new MapSqlParameterSource("ids", userIds),
                rs -> { result.put(rs.getLong("user_id"), new Style(rs.getString("frame_code"), rs.getString("name_style"))); });
        return result;
    }

    /** Khung đã mua của nhiều người. */
    public Map<Long, Set<String>> ownedCodes(Collection<Long> userIds) {
        Map<Long, Set<String>> result = new HashMap<>();
        if (userIds.isEmpty()) return result;
        jdbc.query("SELECT user_id, frame_code FROM user_avatar_frame WHERE user_id IN (:ids)",
                new MapSqlParameterSource("ids", userIds),
                rs -> { result.computeIfAbsent(rs.getLong("user_id"), id -> new HashSet<>()).add(rs.getString("frame_code")); });
        return result;
    }

    public void saveStyle(long userId, String frameCode, String nameStyle) {
        jdbc.update("INSERT INTO user_profile_style (user_id, frame_code, name_style) VALUES (:userId, :frame, :name) "
                        + "ON DUPLICATE KEY UPDATE frame_code = VALUES(frame_code), name_style = VALUES(name_style)",
                new MapSqlParameterSource("userId", userId).addValue("frame", frameCode).addValue("name", nameStyle));
    }
}
