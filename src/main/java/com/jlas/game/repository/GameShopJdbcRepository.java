package com.jlas.game.repository;

import com.jlas.game.enums.GameType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Món đã mua ({@code user_game_item}) và món đang dùng theo từng game ({@code user_game_loadout}). */
@Repository
@RequiredArgsConstructor
public class GameShopJdbcRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Khoá dòng user tới hết giao dịch để các lượt mua của cùng một người chạy lần lượt (bấm mua hai lần
     * thì lượt sau thấy món đã có thay vì bị báo thiếu CP). Phải là câu lệnh đầu tiên của giao dịch.
     */
    public void lockUser(long userId) {
        jdbc.queryForList("SELECT id FROM users WHERE id = :userId FOR UPDATE",
                new MapSqlParameterSource("userId", userId), Long.class);
    }

    public Set<String> ownedCodes(long userId) {
        return new HashSet<>(jdbc.queryForList("SELECT item_code FROM user_game_item WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId), String.class));
    }

    public boolean owns(long userId, String itemCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_game_item WHERE user_id = :userId AND item_code = :code",
                new MapSqlParameterSource("userId", userId).addValue("code", itemCode), Integer.class);
        return count != null && count > 0;
    }

    /** Bỏ qua nếu đã có (mua trùng do bấm hai lần không tạo thêm dòng). */
    public void addOwned(long userId, String itemCode, int pricePaid) {
        jdbc.update("INSERT IGNORE INTO user_game_item (user_id, item_code, price_paid) VALUES (:userId, :code, :price)",
                new MapSqlParameterSource("userId", userId).addValue("code", itemCode).addValue("price", pricePaid));
    }

    public Optional<String> equipped(long userId, GameType gameType) {
        List<String> codes = jdbc.queryForList(
                "SELECT item_code FROM user_game_loadout WHERE user_id = :userId AND game_type = :gameType",
                new MapSqlParameterSource("userId", userId).addValue("gameType", gameType.name()), String.class);
        return codes.stream().findFirst();
    }

    public void equip(long userId, GameType gameType, String itemCode) {
        jdbc.update("INSERT INTO user_game_loadout (user_id, game_type, item_code) VALUES (:userId, :gameType, :code) "
                        + "ON DUPLICATE KEY UPDATE item_code = VALUES(item_code)",
                new MapSqlParameterSource("userId", userId).addValue("gameType", gameType.name())
                        .addValue("code", itemCode));
    }
}
