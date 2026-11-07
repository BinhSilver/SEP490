package com.jlas.dictionary.kanjimap;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KanjiMapProgressService {
    private final JdbcTemplate jdbc;
    private final KanjiMapCatalog catalog;

    @Transactional(readOnly = true)
    public List<String> mastered(Long userId) {
        requireUser(userId);
        return jdbc.queryForList("SELECT kanji_character FROM user_kanji_mastery WHERE user_id = ? ORDER BY kanji_character",
                String.class, userId);
    }

    @Transactional
    public void update(Long userId, String character, boolean mastered) {
        requireUser(userId);
        if (!catalog.contains(character)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Chữ này không có trong lược đồ Kanji.");
        }
        if (mastered) {
            // Idempotent even when two browser tabs update the same character concurrently.
            jdbc.update("INSERT INTO user_kanji_mastery (user_id, kanji_character, mastered_at) VALUES (?, ?, CURRENT_TIMESTAMP) "
                    + "ON DUPLICATE KEY UPDATE mastered_at = mastered_at", userId, character);
        } else {
            jdbc.update("DELETE FROM user_kanji_mastery WHERE user_id = ? AND kanji_character = ?", userId, character);
        }
    }

    private static void requireUser(Long userId) {
        if (userId == null) throw new ApiException(ErrorCode.UNAUTHORIZED, "Bạn cần đăng nhập để lưu tiến độ.");
    }
}
