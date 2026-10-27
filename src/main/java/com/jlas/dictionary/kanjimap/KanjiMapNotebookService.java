package com.jlas.dictionary.kanjimap;

import com.fasterxml.jackson.databind.JsonNode;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KanjiMapNotebookService {
    private final JdbcTemplate jdbc;
    private final KanjiMapCatalog catalog;
    private final KanjiMapContentService content;

    public record Entry(String character, String hanViet, String meaningVi, List<String> on,
                        List<String> kun, String level, LocalDateTime savedAt) {}
    private record Saved(String character, LocalDateTime at) {}

    @Transactional(readOnly = true)
    public List<Entry> entries(Long userId, Long notebookId) {
        requireNotebook(userId, notebookId);
        var saved = jdbc.query("SELECT kanji_character,saved_at FROM user_notebook_kanji WHERE notebook_id=? ORDER BY saved_at DESC,kanji_character",
                (rs, i) -> new Saved(rs.getString(1), rs.getTimestamp(2).toLocalDateTime()), notebookId);
        if (saved.isEmpty()) return List.of();
        var nodes = new HashMap<String, JsonNode>();
        content.graph(false).path("nodes").forEach(n -> nodes.put(n.path("character").asText(), n));
        return saved.stream().map(row -> {
            var n = nodes.get(row.character());
            return n == null ? new Entry(row.character(), "", "", List.of(), List.of(), null, row.at())
                    : new Entry(row.character(), n.path("hanViet").asText(""), n.path("meaningVi").asText(""),
                        readings(n, "on"), readings(n, "kun"), n.path("level").asText(null), row.at());
        }).toList();
    }

    @Transactional
    public void save(Long userId, Long notebookId, List<String> characters) {
        requireNotebook(userId, notebookId);
        if (characters == null || characters.isEmpty() || characters.size() > 100)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn từ 1 đến 100 chữ Kanji.");
        var unique = new LinkedHashSet<>(characters);
        for (var ch : unique) if (ch == null || !catalog.contains(ch))
            throw new ApiException(ErrorCode.NOT_FOUND, "Chữ này không có trong lược đồ Kanji.");
        for (var ch : unique) jdbc.update("INSERT INTO user_notebook_kanji(notebook_id,kanji_character) VALUES (?,?) ON DUPLICATE KEY UPDATE saved_at=saved_at", notebookId, ch);
    }

    @Transactional
    public void remove(Long userId, Long notebookId, String character) {
        requireNotebook(userId, notebookId);
        jdbc.update("DELETE FROM user_notebook_kanji WHERE notebook_id=? AND kanji_character=?", notebookId, character);
    }

    private void requireNotebook(Long userId, Long notebookId) {
        if (userId == null) throw new ApiException(ErrorCode.UNAUTHORIZED, "Bạn cần đăng nhập để lưu vào sổ tay.");
        if (notebookId == null || jdbc.queryForObject("SELECT COUNT(*) FROM user_notebook WHERE id=? AND user_id=?", Integer.class, notebookId, userId) == 0)
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy sổ tay.");
    }
    private List<String> readings(JsonNode node, String field) {
        var values = new ArrayList<String>(); node.path(field).forEach(n -> values.add(n.asText())); return values;
    }
}
