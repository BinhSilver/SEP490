package com.jlas.kaiwa.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Đọc nội dung được phép index từ MySQL và dựng thành {@link RagChunk}.
 *
 * <p>Chỉ lấy bài PUBLISHED của giáo trình PUBLISHED. Mặc định bỏ mọi thứ có nguồn {@code VNJPCLUB}
 * (chưa có giấy phép phân phối lại) và nội dung {@code AI_GENERATED} chưa duyệt, xét ở cả cấp giáo trình,
 * unit, bài, mẫu ngữ pháp và mục nội dung.
 *
 * <p>Loại đoạn:
 * <ul>
 *   <li>{@code GRAMMAR}: một mẫu ngữ pháp + tối đa {@value #MAX_EXAMPLES} câu ví dụ của nó (kèm audio).</li>
 *   <li>{@code DIALOGUE}, {@code READING}: các mục cùng một phần của bài, gom tối đa {@value #LINES_PER_CHUNK} dòng.</li>
 * </ul>
 */
@Repository
public class RagContentSource {

    static final int MAX_EXAMPLES = 6;
    static final int LINES_PER_CHUNK = 10;

    private static final String LESSON_COLUMNS = """
            l.id AS lesson_id, l.title AS lesson_title, u.title AS unit_title,
            t.id AS textbook_id, t.title AS textbook_title, t.jlpt_level AS jlpt_level""";

    private final JdbcTemplate jdbc;
    private final RagProperties properties;

    public RagContentSource(JdbcTemplate jdbc, RagProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** Mọi đoạn được phép index; {@code lessonId} khác null thì chỉ bài đó. */
    public List<RagChunk> load(Long lessonId) {
        Map<Long, Audio> audio = loadAudio();
        List<RagChunk> chunks = new ArrayList<>(grammarChunks(lessonId, audio));
        chunks.addAll(sectionChunks(lessonId, audio));
        return chunks;
    }

    /** Điều kiện cho bí danh t/u/l và các thực thể con đã cho (vd. {@code GRAMMAR_POINT} → {@code gp.id}). */
    String eligibility(Long lessonId, Map<String, String> children) {
        Map<String, String> entities = new LinkedHashMap<>();
        entities.put("TEXTBOOK", "t.id");
        entities.put("UNIT", "u.id");
        entities.put("LESSON", "l.id");
        entities.putAll(children);
        StringBuilder sql = new StringBuilder("t.status = 'PUBLISHED' AND l.status = 'PUBLISHED'");
        // Một NOT EXISTS cho mỗi loại thực thể để MySQL dùng được chỉ mục (entity_type, entity_id).
        entities.forEach((type, column) -> {
            String owned = "SELECT 1 FROM content_source cs WHERE cs.entity_type = '" + type + "' AND cs.entity_id = " + column;
            if (!properties.includeVnjpclub()) sql.append(" AND NOT EXISTS (").append(owned).append(" AND cs.source_code = 'VNJPCLUB')");
            if (!properties.includeUnreviewed()) sql.append(" AND NOT EXISTS (").append(owned)
                    .append(" AND cs.source_code = 'AI_GENERATED' AND cs.review_status <> 'DA_DUYET')");
        });
        if (lessonId != null) sql.append(" AND l.id = ").append(lessonId.longValue());
        return sql.toString();
    }

    private List<RagChunk> grammarChunks(Long lessonId, Map<Long, Audio> audio) {
        String where = eligibility(lessonId, Map.of("GRAMMAR_POINT", "gp.id"));
        List<Map<String, Object>> points = jdbc.queryForList("SELECT gp.id, gp.pattern, gp.meaning, gp.example, gp.note, "
                + LESSON_COLUMNS + " FROM grammar_point gp JOIN lesson l ON l.id = gp.lesson_id"
                + " JOIN unit u ON u.id = l.unit_id JOIN textbook t ON t.id = u.textbook_id"
                + " WHERE " + where + " ORDER BY gp.id");
        Map<Long, List<Map<String, Object>>> examples = new HashMap<>();
        jdbc.queryForList("SELECT ci.id, ci.grammar_point_id, ci.content, ci.translated_text FROM content_item ci"
                + " JOIN grammar_point gp ON gp.id = ci.grammar_point_id JOIN lesson l ON l.id = gp.lesson_id"
                + " JOIN unit u ON u.id = l.unit_id JOIN textbook t ON t.id = u.textbook_id"
                + " WHERE ci.content IS NOT NULL AND " + eligibility(lessonId, Map.of("GRAMMAR_POINT", "gp.id", "CONTENT_ITEM", "ci.id"))
                + " ORDER BY ci.grammar_point_id, ci.order_index, ci.id")
                .forEach(row -> examples.computeIfAbsent(number(row, "grammar_point_id"), k -> new ArrayList<>()).add(row));

        List<RagChunk> chunks = new ArrayList<>();
        for (Map<String, Object> gp : points) {
            long id = number(gp, "id");
            StringBuilder text = new StringBuilder("Mẫu ngữ pháp: ").append(str(gp, "pattern"))
                    .append("\nÝ nghĩa: ").append(str(gp, "meaning"));
            if (!str(gp, "note").isBlank()) text.append("\nLưu ý: ").append(str(gp, "note"));
            if (!str(gp, "example").isBlank()) text.append("\nVí dụ: ").append(str(gp, "example"));
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> example : examples.getOrDefault(id, List.of()).stream().limit(MAX_EXAMPLES).toList()) {
                text.append("\n- ").append(str(example, "content"));
                if (!str(example, "translated_text").isBlank()) text.append(" — ").append(str(example, "translated_text"));
                lines.add(line(example, null, audio));
            }
            Map<String, Object> citation = citation(gp, "GRAMMAR");
            citation.put("pattern", str(gp, "pattern"));
            citation.put("lines", lines);
            chunks.add(new RagChunk("grammar_point:" + id, "GRAMMAR", id, number(gp, "lesson_id"), number(gp, "textbook_id"),
                    nullable(gp, "jlpt_level"), str(gp, "pattern"), text.toString(), citation));
        }
        return chunks;
    }

    private List<RagChunk> sectionChunks(Long lessonId, Map<Long, Audio> audio) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT ci.id, ci.content_type, ci.title, ci.content, ci.translated_text,"
                + " s.id AS section_id, s.title AS section_title, dr.name_ja AS speaker_ja, dr.name_vi AS speaker_vi, "
                + LESSON_COLUMNS + " FROM content_item ci JOIN lesson_section s ON s.id = ci.section_id"
                + " JOIN lesson l ON l.id = s.lesson_id JOIN unit u ON u.id = l.unit_id JOIN textbook t ON t.id = u.textbook_id"
                + " LEFT JOIN dialogue_line_detail d ON d.content_item_id = ci.id LEFT JOIN dialogue_role dr ON dr.id = d.dialogue_role_id"
                + " WHERE ci.grammar_point_id IS NULL AND ci.content IS NOT NULL AND ci.content_type IN ('LISTENING', 'READING', 'GRAMMAR')"
                + " AND " + eligibility(lessonId, Map.of("CONTENT_ITEM", "ci.id"))
                + " ORDER BY s.id, ci.content_type, ci.order_index, ci.id");
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String type = "LISTENING".equals(str(row, "content_type")) ? "DIALOGUE" : str(row, "content_type");
            groups.computeIfAbsent(number(row, "section_id") + ":" + type, k -> new ArrayList<>()).add(row);
        }
        List<RagChunk> chunks = new ArrayList<>();
        groups.forEach((group, items) -> {
            String type = group.substring(group.indexOf(':') + 1);
            for (int part = 0; part * LINES_PER_CHUNK < items.size(); part++) {
                List<Map<String, Object>> window = items.subList(part * LINES_PER_CHUNK, Math.min(items.size(), (part + 1) * LINES_PER_CHUNK));
                Map<String, Object> first = window.getFirst();
                StringBuilder text = new StringBuilder(str(first, "lesson_title")).append(" — ").append(str(first, "section_title"));
                List<Map<String, Object>> lines = new ArrayList<>();
                for (Map<String, Object> item : window) {
                    String speaker = !str(item, "speaker_ja").isBlank() ? str(item, "speaker_ja")
                            : "DIALOGUE".equals(type) ? str(item, "title") : "";
                    text.append("\n").append(speaker.isBlank() ? "" : speaker + ": ").append(str(item, "content"));
                    if (!str(item, "translated_text").isBlank()) text.append(" — ").append(str(item, "translated_text"));
                    lines.add(line(item, speaker.isBlank() ? null : speaker, audio));
                }
                long sectionId = number(first, "section_id");
                Map<String, Object> citation = citation(first, type);
                citation.put("section", str(first, "section_title"));
                citation.put("lines", lines);
                chunks.add(new RagChunk("section:" + sectionId + ":" + type + ":" + part, type, sectionId,
                        number(first, "lesson_id"), number(first, "textbook_id"), nullable(first, "jlpt_level"),
                        str(first, "section_title"), text.toString(), citation));
            }
        });
        return chunks;
    }

    private record Audio(String url, Integer startMs, Integer endMs) { }

    /** Audio đầu tiên (theo id) của mỗi mục nội dung. */
    private Map<Long, Audio> loadAudio() {
        Map<Long, Audio> audio = new HashMap<>();
        jdbc.query("SELECT owner_id, audio_url, start_ms, end_ms FROM content_audio WHERE owner_type = 'CONTENT_ITEM' ORDER BY id",
                rs -> {
                    audio.putIfAbsent(rs.getLong("owner_id"), new Audio(rs.getString("audio_url"),
                            (Integer) rs.getObject("start_ms", Integer.class), (Integer) rs.getObject("end_ms", Integer.class)));
                });
        return audio;
    }

    private static Map<String, Object> line(Map<String, Object> item, String speaker, Map<Long, Audio> audio) {
        Map<String, Object> line = new LinkedHashMap<>();
        long id = number(item, "id");
        line.put("contentItemId", id);
        if (speaker != null) line.put("speaker", speaker);
        line.put("ja", str(item, "content"));
        if (!str(item, "translated_text").isBlank()) line.put("vi", str(item, "translated_text"));
        Audio a = audio.get(id);
        if (a != null) {
            line.put("audioUrl", a.url());
            if (a.startMs() != null) line.put("startMs", a.startMs());
            if (a.endMs() != null) line.put("endMs", a.endMs());
        }
        return line;
    }

    private static Map<String, Object> citation(Map<String, Object> row, String type) {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("type", type);
        citation.put("textbook", str(row, "textbook_title"));
        citation.put("unit", str(row, "unit_title"));
        citation.put("lesson", str(row, "lesson_title"));
        citation.put("lessonId", number(row, "lesson_id"));
        return citation;
    }

    private static String str(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? "" : value.toString().strip();
    }

    private static String nullable(Map<String, Object> row, String column) {
        String value = str(row, column);
        return value.isBlank() ? null : value;
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }
}
