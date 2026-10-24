package com.jlas.game.repository;

import com.jlas.game.enums.GameType;
import com.jlas.game.util.GameShopCatalog.ShopItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class GameSkinRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<ShopItem> MAPPER = (rs, row) -> new ShopItem(rs.getLong("id"),
            rs.getString("code"), GameType.valueOf(rs.getString("game_type")), rs.getString("name"),
            rs.getString("description"), rs.getInt("price"), rs.getString("image"), rs.getBoolean("starter"),
            rs.getString("render_code"), rs.getBoolean("active"), rs.getBoolean("deleted"),
            rs.getString("pack_path"), rs.getString("weapon_theme"));

    public List<ShopItem> all() { return jdbc.query("SELECT * FROM game_shop_skin ORDER BY game_type, id", MAPPER); }
    public Optional<ShopItem> find(String code) {
        return jdbc.query("SELECT * FROM game_shop_skin WHERE code = ?", MAPPER, code).stream().findFirst();
    }
    public void create(ShopItem item) {
        jdbc.update("INSERT INTO game_shop_skin (code, game_type, name, description, price, image, render_code, starter, active, pack_path, weapon_theme) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?)",
                item.code(), item.gameType().name(), item.name(), item.description(), item.price(), item.image(), item.renderCode(), item.active(),
                item.packPath(), item.weaponTheme());
    }
    public void update(ShopItem item) {
        jdbc.update("UPDATE game_shop_skin SET name=?, description=?, price=?, image=?, render_code=?, active=?, pack_path=?, weapon_theme=? WHERE code=? AND deleted=FALSE",
                item.name(), item.description(), item.price(), item.image(), item.renderCode(), item.active(),
                item.packPath(), item.weaponTheme(), item.code());
    }
    /**
     * Đổi skin mặc định của một game: bỏ cờ của skin cũ, skin mới thành mặc định, miễn phí và đang bán.
     * Người đang mặc skin mặc định cũ được ghi là đã sở hữu nó, để không bị mất món đang dùng.
     */
    public void setStarter(GameType game, String oldCode, String newCode) {
        if (oldCode != null) {
            jdbc.update("INSERT IGNORE INTO user_game_item (user_id, item_code, price_paid) "
                    + "SELECT user_id, item_code, 0 FROM user_game_loadout WHERE game_type = ? AND item_code = ?", game.name(), oldCode);
        }
        jdbc.update("UPDATE game_shop_skin SET starter = FALSE WHERE game_type = ? AND starter = TRUE", game.name());
        jdbc.update("UPDATE game_shop_skin SET starter = TRUE, price = 0, active = TRUE WHERE code = ? AND deleted = FALSE", newCode);
    }

    public void delete(String code) {
        jdbc.update("UPDATE game_shop_skin SET active=FALSE, deleted=TRUE WHERE code=? AND starter=FALSE", code);
    }
}
