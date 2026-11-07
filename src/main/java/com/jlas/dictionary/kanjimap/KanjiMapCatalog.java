package com.jlas.dictionary.kanjimap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.dictionary.repository.KanjiMapCatalogRepository;
import java.util.Set;
import java.util.HashSet;
import org.springframework.stereotype.Component;

/** Immutable source snapshot loaded from the SQL catalog, independent of dictionary seed coverage. */
@Component
public class KanjiMapCatalog {
    private final Set<String> characters;
    private final ObjectNode graph;

    public KanjiMapCatalog(KanjiMapCatalogRepository repository) {
        var result = new HashSet<String>();
        graph = repository.loadGraph();
        for (var node : graph.path("nodes")) {
            if (node.path("isKanji").asBoolean()) result.add(node.path("character").asText());
        }
        characters = Set.copyOf(result);
    }

    public boolean contains(String character) { return characters.contains(character); }
    public ObjectNode graph() { return graph.deepCopy(); }
}
