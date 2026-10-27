package com.jlas.game.repository;

import com.jlas.game.util.AvatarFrameCatalog.Frame;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Danh mục khung avatar do admin quản lý ({@code avatar_frame}); người mua ở {@code user_avatar_frame}. */
@Repository
@RequiredArgsConstructor
public class AvatarFrameCatalogRepository {
    private final JdbcTemplate jdbc;

    private static final RowMapper<Frame> MAPPER = (rs, row) -> new Frame(rs.getLong("id"), rs.getString("code"),
            rs.getString("name"), rs.getString("description"), rs.getInt("price"), rs.getString("image"),
            rs.getString("theme"), rs.getString("name_style"), rs.getString("name_style_label"),
            rs.getString("gems_json"), rs.getInt("sort_order"), rs.getBoolean("active"), rs.getBoolean("deleted"));

    /** Mọi khung kể cả đã ẩn / đã xoá, theo thứ tự hiển thị. */
    public List<Frame> all() {
        return jdbc.query("SELECT * FROM avatar_frame ORDER BY sort_order, id", MAPPER);
    }

    public Optional<Frame> find(String code) {
        return jdbc.query("SELECT * FROM avatar_frame WHERE code = ?", MAPPER, code).stream().findFirst();
    }

    public void create(Frame frame) {
        jdbc.update("INSERT INTO avatar_frame (code, name, description, price, image, theme, name_style, name_style_label, "
                        + "gems_json, sort_order, active, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)",
                frame.code(), frame.name(), frame.description(), frame.price(), frame.image(), frame.theme(),
                frame.nameStyle(), frame.nameStyleLabel(), frame.gemsJson(), frame.sortOrder(), frame.active());
    }

    public void update(Frame frame) {
        jdbc.update("UPDATE avatar_frame SET name = ?, description = ?, price = ?, image = ?, theme = ?, name_style = ?, "
                        + "name_style_label = ?, gems_json = ?, sort_order = ?, active = ? WHERE code = ? AND deleted = FALSE",
                frame.name(), frame.description(), frame.price(), frame.image(), frame.theme(), frame.nameStyle(),
                frame.nameStyleLabel(), frame.gemsJson(), frame.sortOrder(), frame.active(), frame.code());
    }

    /** Xoá mềm: giữ dòng để mã khung không bị dùng lại cho món khác. */
    public void delete(String code) {
        jdbc.update("UPDATE avatar_frame SET active = FALSE, deleted = TRUE WHERE code = ?", code);
    }

    public int owners(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_avatar_frame WHERE frame_code = ?", Integer.class, code);
        return count == null ? 0 : count;
    }

    /** Số người đã mua theo mã khung (chỉ khung có người mua). */
    public Map<String, Integer> ownerCounts() {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("SELECT frame_code, COUNT(*) AS owners FROM user_avatar_frame GROUP BY frame_code",
                rs -> { counts.put(rs.getString("frame_code"), rs.getInt("owners")); });
        return counts;
    }
}
