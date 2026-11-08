package com.jlas.dictionary.kanjimap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Editorial text overlays the immutable source graph; no structural edges or strokes are accepted. */
@Service
public class KanjiMapContentService {
    public record Edit(long version, String hanViet, String meaningVi, String level,
                       List<String> on, List<String> kun, String memoryHint,
                       String radicalNameVi, List<String> radicalAliases) {}
    public record Entry(ObjectNode node, ObjectNode source, long version, Instant updatedAt, Long updatedBy) {}
    private record Stored(String character, String content, long version, Instant updatedAt, Long updatedBy) {}
    private static final List<String> FIELDS = List.of("hanViet", "meaningVi", "level", "on", "kun",
            "memoryHint", "radicalNameVi", "radicalAliases");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode source;
    private final Map<String, ObjectNode> originals = new LinkedHashMap<>();

    public KanjiMapContentService(JdbcTemplate jdbc, KanjiMapCatalog catalog) {
        this.jdbc = jdbc;
        source = catalog.graph();
        source.path("nodes").forEach(n -> originals.put(n.path("character").asText(), (ObjectNode) n));
    }

    @Transactional(readOnly = true)
    public ObjectNode graph(boolean admin) {
        var edits = new HashMap<String, Stored>();
        jdbc.query("SELECT * FROM kanji_map_content", (rs, i) -> new Stored(rs.getString("kanji_character"),
                rs.getString("content_json"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("updated_by"))).forEach(row -> edits.put(row.character(), row));
        var graph = source.deepCopy();
        var levels = mapper.createObjectNode();
        for (var level : List.of("N1", "N2", "N3", "N4", "N5")) levels.put(level, 0);
        int unclassified = 0;
        for (var raw : graph.path("nodes")) {
            var node = (ObjectNode) raw;
            var saved = edits.get(node.path("character").asText());
            apply(node, saved);
            if (admin) {
                node.put("contentVersion", saved == null ? 0 : saved.version());
                if (saved != null) node.put("contentUpdatedAt", saved.updatedAt().toString());
            }
            if (node.path("isKanji").asBoolean()) {
                String level = node.path("level").asText("");
                if (levels.has(level)) levels.put(level, levels.path(level).asInt() + 1);
                else unclassified++;
            }
        }
        ((ObjectNode) graph.path("counts")).set("levels", levels);
        ((ObjectNode) graph.path("counts")).put("unclassified", unclassified);
        if (admin) graph.put("editedCount", edits.size());
        return graph;
    }

    @Transactional(readOnly = true)
    public Entry entry(String character) {
        var original = original(character);
        var saved = stored(character);
        var node = original.deepCopy();
        apply(node, saved);
        return new Entry(node, original.deepCopy(), saved == null ? 0 : saved.version(),
                saved == null ? null : saved.updatedAt(), saved == null ? null : saved.updatedBy());
    }

    @Transactional
    public Entry save(String character, Edit edit, Long actor) {
        if (actor == null) throw new ApiException(ErrorCode.UNAUTHORIZED);
        var original = original(character);
        var content = validate(original, edit);
        String json;
        try { json = mapper.writeValueAsString(content); }
        catch (Exception e) { throw new ApiException(ErrorCode.INTERNAL_ERROR); }
        var now = Timestamp.from(Instant.now());
        if (edit.version() == 0) {
            try {
                jdbc.update("INSERT INTO kanji_map_content (kanji_character,content_json,version,updated_at,updated_by) VALUES (?,?,1,?,?)",
                        character, json, now, actor);
            } catch (DuplicateKeyException e) { throw conflict(); }
        } else if (jdbc.update("UPDATE kanji_map_content SET content_json=?,version=version+1,updated_at=?,updated_by=? WHERE kanji_character=? AND version=?",
                json, now, actor, character, edit.version()) != 1) throw conflict();
        return entry(character);
    }

    @Transactional
    public Entry reset(String character, long version, Long actor) {
        var n = original(character);
        return save(character, new Edit(version, n.path("hanViet").asText(""), n.path("meaningVi").asText(""),
                n.path("level").asText(""), strings(n, "on"), strings(n, "kun"), n.path("memoryHint").asText(""),
                n.path("radicalNameVi").asText(""), strings(n, "radicalAliases")), actor);
    }

    private ObjectNode validate(ObjectNode original, Edit edit) {
        if (edit == null || edit.version() < 0) throw invalid("Phiên bản nội dung không hợp lệ.");
        var node = mapper.createObjectNode();
        node.put("hanViet", text(edit.hanViet(), 180, false, "Hán Việt"));
        node.put("meaningVi", text(edit.meaningVi(), 1500, original.path("isKanji").asBoolean(), "Nghĩa Việt"));
        var level = text(edit.level(), 20, false, "Cấp độ");
        if (!level.isEmpty() && (!Set.of("N1", "N2", "N3", "N4", "N5").contains(level) || !original.path("isKanji").asBoolean()))
            throw invalid("Cấp độ chỉ nhận N5–N1 cho chữ Kanji, hoặc để chưa xếp cấp.");
        if (level.isEmpty()) node.putNull("level"); else node.put("level", level);
        node.set("on", list(edit.on(), 40, 80, "Âm On"));
        node.set("kun", list(edit.kun(), 40, 80, "Âm Kun"));
        node.put("memoryHint", text(edit.memoryHint(), 2000, false, "Gợi ý ghi nhớ"));
        var radical = text(edit.radicalNameVi(), 100, false, "Tên bộ thủ");
        var aliases = list(edit.radicalAliases(), 20, 100, "Tên gọi khác");
        boolean isRadical = original.path("isRadical").asBoolean() || original.path("isRadicalVariant").asBoolean();
        if (!isRadical && (!radical.isEmpty() || !aliases.isEmpty())) throw invalid("Chỉ bộ thủ và dạng biến thể mới có tên bộ thủ.");
        if (isRadical && radical.isEmpty()) throw invalid("Vui lòng nhập tên bộ thủ.");
        node.put("radicalNameVi", radical); node.set("radicalAliases", aliases);
        return node;
    }

    private String text(String value, int max, boolean required, String label) {
        var result = value == null ? "" : value.strip();
        if (result.length() > max || required && result.isEmpty() || result.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
            throw invalid(label + " không hợp lệ hoặc vượt quá " + max + " ký tự.");
        return result;
    }
    private com.fasterxml.jackson.databind.node.ArrayNode list(List<String> values, int max, int length, String label) {
        if (values == null || values.size() > max) throw invalid(label + " cần là danh sách tối đa " + max + " mục.");
        var result = mapper.createArrayNode(); var seen = new HashSet<String>();
        for (var value : values) { var normalized = text(value, length, true, label); if (seen.add(normalized)) result.add(normalized); }
        return result;
    }
    private List<String> strings(ObjectNode node, String field) {
        var result = new ArrayList<String>(); node.path(field).forEach(n -> result.add(n.asText())); return result;
    }
    private ObjectNode original(String character) {
        var node = originals.get(character);
        if (node == null) throw new ApiException(ErrorCode.NOT_FOUND, "Chữ không có trong lược đồ nguồn.");
        return node;
    }
    private Stored stored(String character) {
        var rows = jdbc.query("SELECT * FROM kanji_map_content WHERE kanji_character=?", (rs, i) ->
                new Stored(character, rs.getString("content_json"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(), rs.getLong("updated_by")), character);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private void apply(ObjectNode node, Stored stored) {
        if (stored == null) return;
        try {
            var edited = mapper.readTree(stored.content());
            for (var field : FIELDS) if (edited.has(field)) node.set(field, edited.get(field));
            node.put("meaningViSource", "jlas-content-creator");
        } catch (java.io.IOException e) { throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không đọc được nội dung Kanji đã lưu."); }
    }
    private ApiException invalid(String message) { return new ApiException(ErrorCode.VALIDATION_FAILED, message); }
    private ApiException conflict() { return new ApiException(ErrorCode.CONFLICT, "Nội dung đã được người khác cập nhật. Hãy tải lại trước khi lưu."); }
}
