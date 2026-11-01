package com.jlas.common.history;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * "Lịch sử" kiểu YouTube: lần gần nhất người dùng mở một bộ flashcard hoặc một video Shadowing (migration 065).
 * Một dòng mỗi (người dùng, mục); mở lại chỉ đổi thời điểm.
 */
@Repository
public class ViewHistoryRepository {
    private static final Logger log = LoggerFactory.getLogger(ViewHistoryRepository.class);
    /** Giới hạn số mục đọc ra cho một trang lịch sử. */
    static final int LIMIT = 1000;

    public enum Item { FLASHCARD_SET, SHADOWING_VIDEO }

    private final JdbcTemplate jdbc;

    public ViewHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Ghi một lần ghé. Lịch sử là phụ: lỗi DB chỉ ghi log, không làm hỏng việc mở trang. */
    public void record(Long userId, Item item, Long itemId) {
        if (userId == null || itemId == null) return;
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        try {
            if (jdbc.update("UPDATE user_view_history SET viewed_at=? WHERE user_id=? AND item_type=? AND item_id=?",
                    now, userId, item.name(), itemId) == 0)
                jdbc.update("INSERT INTO user_view_history (user_id, item_type, item_id, viewed_at) VALUES (?,?,?,?)",
                        userId, item.name(), itemId, now);
        } catch (DataAccessException e) {
            log.warn("View history not recorded for {} {} ({})", item, itemId, e.getClass().getSimpleName());
        }
    }

    /** Mã mục → lần ghé gần nhất, mới nhất trước. */
    public Map<Long, LocalDateTime> recent(Long userId, Item item) {
        Map<Long, LocalDateTime> seen = new LinkedHashMap<>();
        if (userId == null) return seen;
        jdbc.query("SELECT item_id, viewed_at FROM user_view_history WHERE user_id=? AND item_type=? "
                        + "ORDER BY viewed_at DESC, item_id DESC LIMIT " + LIMIT,
                rs -> { seen.put(rs.getLong("item_id"), rs.getTimestamp("viewed_at").toLocalDateTime()); },
                userId, item.name());
        return seen;
    }

    public void remove(Long userId, Item item, Long itemId) {
        jdbc.update("DELETE FROM user_view_history WHERE user_id=? AND item_type=? AND item_id=?", userId, item.name(), itemId);
    }

    public void clear(Long userId, Item item) {
        jdbc.update("DELETE FROM user_view_history WHERE user_id=? AND item_type=?", userId, item.name());
    }
}
