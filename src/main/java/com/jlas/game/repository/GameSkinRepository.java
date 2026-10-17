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
            rs.getString("render_code"), rs.getBoolean("active"), rs.getBoolean("deleted"));

    public List<ShopItem> all() { return jdbc.query("SELECT * FROM game_shop_skin ORDER BY game_type, id", MAPPER); }
    public Optional<ShopItem> find(String code) {
        return jdbc.query("SELECT * FROM game_shop_skin WHERE code = ?", MAPPER, code).stream().findFirst();
    }
    public void create(ShopItem item) {
        jdbc.update("INSERT INTO game_shop_skin (code, game_type, name, description, price, image, render_code, starter, active) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?)",
                item.code(), item.gameType().name(), item.name(), item.description(), item.price(), item.image(), item.renderCode(), item.active());
    }
    public void update(ShopItem item) {
        jdbc.update("UPDATE game_shop_skin SET name=?, description=?, price=?, image=?, render_code=?, active=? WHERE code=? AND deleted=FALSE",
                item.name(), item.description(), item.price(), item.image(), item.renderCode(), item.active(), item.code());
    }
    public void delete(String code) {
        jdbc.update("UPDATE game_shop_skin SET active=FALSE, deleted=TRUE WHERE code=? AND starter=FALSE", code);
    }
}
