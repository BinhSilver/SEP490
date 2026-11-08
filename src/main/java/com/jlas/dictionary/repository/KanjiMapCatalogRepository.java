package com.jlas.dictionary.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reconstructs the existing HTTP graph contract from normalized source tables, never a classpath file. */
@Repository
public class KanjiMapCatalogRepository {
    private record Dataset(int version, String sources, int nodes) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public KanjiMapCatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public ObjectNode loadGraph() {
        var datasets = jdbc.query("SELECT graph_version,sources_json,node_count FROM kanji_map_dataset WHERE id=1",
                (rs, row) -> new Dataset(rs.getInt(1), rs.getString(2), rs.getInt(3)));
        if (datasets.isEmpty()) throw missingSeed();
        var metadata = datasets.getFirst();
        var graph = mapper.createObjectNode();
        graph.put("version", metadata.version());
        try { graph.set("sources", mapper.readTree(metadata.sources())); }
        catch (java.io.IOException e) { throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không đọc được nguồn dữ liệu lược đồ Kanji trong DB."); }
        var nodes = new LinkedHashMap<String, ObjectNode>();
        jdbc.query("SELECT * FROM kanji_map_node WHERE is_active=TRUE ORDER BY source_order", (RowCallbackHandler) rs -> {
            var n = mapper.createObjectNode();
            n.put("character", rs.getString("kanji_character"));
            n.put("isKanji", rs.getBoolean("is_kanji")); n.put("isRadical", rs.getBoolean("is_radical"));
            integer(n, "radicalNumber", rs, "radical_number"); integer(n, "strokeCount", rs, "stroke_count");
            n.put("level", rs.getString("jlpt_level")); n.put("hanViet", rs.getString("han_viet"));
            n.put("meaningVi", rs.getString("meaning_vi")); n.put("meaningViSource", rs.getString("meaning_vi_source"));
            n.put("meaningViSourceUrl", rs.getString("meaning_vi_source_url")); n.put("memoryHint", rs.getString("memory_hint"));
            n.put("parent", rs.getString("parent_character"));
            if (rs.getString("parent_relation") != null) n.put("parentRelation", rs.getString("parent_relation"));
            for (var field : List.of("on", "kun", "meaningsEn", "strokes", "components")) n.putArray(field);
            if (rs.getString("radical_name_vi") != null) {
                n.put("radicalNameVi", rs.getString("radical_name_vi")); n.putArray("radicalAliases");
                n.put("radicalBase", rs.getString("radical_base")); n.put("isRadicalVariant", rs.getBoolean("is_radical_variant"));
            }
            nodes.put(n.path("character").asText(), n);
        });
        if (nodes.isEmpty() || nodes.size() != metadata.nodes()) throw missingSeed();
        jdbc.query("""
                SELECT * FROM kanji_map_reading
                WHERE kanji_character IN (SELECT kanji_character FROM kanji_map_node WHERE is_active=TRUE)
                ORDER BY kanji_character,reading_type,reading_order
                """, (RowCallbackHandler) rs -> nodes.get(rs.getString("kanji_character"))
                .withArray("ON".equals(rs.getString("reading_type")) ? "on" : "kun").add(rs.getString("reading_text")));
        values(nodes, "kanji_map_gloss", "gloss_order", "meaning_en", "meaningsEn");
        values(nodes, "kanji_map_radical_alias", "alias_order", "alias_text", "radicalAliases");
        values(nodes, "kanji_map_stroke", "stroke_order", "path_data", "strokes");
        jdbc.query("""
                SELECT * FROM kanji_map_component
                WHERE kanji_character IN (SELECT kanji_character FROM kanji_map_node WHERE is_active=TRUE)
                ORDER BY kanji_character,component_order
                """, (RowCallbackHandler) rs -> {
            var c = nodes.get(rs.getString("kanji_character")).withArray("components").addObject();
            c.put("character", rs.getString("component_character")); c.put("original", rs.getString("original_character"));
            c.put("position", rs.getString("position_code")); c.put("partial", rs.getBoolean("is_partial"));
        });
        var counts = graph.putObject("counts");
        var levels = counts.putObject("levels");
        for (var level : List.of("N1", "N2", "N3", "N4", "N5")) levels.put(level, 0);
        int kanji = 0, meanings = 0, unclassified = 0;
        for (var n : nodes.values()) if (n.path("isKanji").asBoolean()) {
            kanji++;
            if (!n.path("meaningVi").asText("").isBlank()) meanings++;
            var level = n.path("level").asText("");
            if (levels.has(level)) levels.put(level, levels.path(level).asInt() + 1); else unclassified++;
        }
        counts.put("nodes", nodes.size()); counts.put("kanji", kanji); counts.put("components", nodes.size() - kanji);
        counts.put("meaningsVi", meanings); counts.put("unclassified", unclassified);
        var array = graph.putArray("nodes"); nodes.values().forEach(array::add);
        return graph;
    }

    private void integer(ObjectNode node, String field, ResultSet rs, String column) throws SQLException {
        var value = (Number) rs.getObject(column);
        if (value == null) node.putNull(field); else node.put(field, value.intValue());
    }

    private void values(LinkedHashMap<String, ObjectNode> nodes, String table, String order, String column, String field) {
        // Identifiers are private constants supplied above, never request input.
        jdbc.query("SELECT kanji_character," + column + " FROM " + table +
                " WHERE kanji_character IN (SELECT kanji_character FROM kanji_map_node WHERE is_active=TRUE) " +
                "ORDER BY kanji_character," + order, (RowCallbackHandler) rs ->
                nodes.get(rs.getString("kanji_character")).withArray(field).add(rs.getString(column)));
    }

    private ApiException missingSeed() {
        return new ApiException(ErrorCode.INTERNAL_ERROR,
                "Kho lược đồ Kanji chưa được nạp đầy đủ. Hãy chạy sql/seeds/kanji_map_update.sql.");
    }
}
