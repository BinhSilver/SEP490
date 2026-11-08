package com.jlas.dictionary.kanjimap;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read the current editorial text and immutable geometry without copying them into notebooks. */
@Service
@RequiredArgsConstructor
public class KanjiNotebookStudyContent {
    private final KanjiMapContentService content;
    private final JdbcTemplate jdbc;

    public record Details(String hanViet, String meaning, List<String> on, List<String> kun,
                          String memoryHint, List<String> strokes, List<String> components,
                          List<String> usedIn) {}

    public Details details(String character) {
        var node = content.entry(character).node();
        var components = new ArrayList<String>();
        node.path("components").forEach(part -> {
            String ch = part.path("character").asText();
            if (!ch.isBlank() && !components.contains(ch)) components.add(ch);
        });
        var usedIn = jdbc.query("SELECT DISTINCT c.kanji_character FROM kanji_map_component c JOIN kanji_map_node n ON n.kanji_character=c.kanji_character WHERE c.component_character=? AND n.is_kanji=TRUE ORDER BY c.kanji_character LIMIT 12",
                (rs, i) -> rs.getString(1), character);
        return new Details(node.path("hanViet").asText(""), node.path("meaningVi").asText(""),
                strings(node, "on"), strings(node, "kun"), node.path("memoryHint").asText(""),
                strings(node, "strokes"), List.copyOf(components), usedIn);
    }

    private List<String> strings(JsonNode node, String field) {
        var result = new ArrayList<String>();
        node.path(field).forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }
}
