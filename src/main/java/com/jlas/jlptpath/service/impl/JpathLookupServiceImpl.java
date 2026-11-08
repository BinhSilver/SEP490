package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.jlptpath.dto.JpathGrammarLookupView;
import com.jlas.jlptpath.entity.JpathExample;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathLevelInfo;
import com.jlas.jlptpath.service.JpathLookupService;
import com.jlas.jlptpath.util.JpathGrammarMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] {@link JpathLookupService}: scores every visible pattern of the released levels with
 * {@link JpathGrammarMatcher} (pattern and pattern_variants_json); the example is the first visible MAIN one.
 */
@Service
public class JpathLookupServiceImpl implements JpathLookupService {

    static final int MAX_QUERY = 100;
    static final int MAX_RESULTS = 3;
    private static final String LESSON_URL = "/learning/jlpt/grammar/";
    private static final String MAIN = "MAIN";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ORDER = List.of("N5", "N4", "N3", "N2", "N1");

    private final JpathCatalogService catalog;
    private final JpathContentReader content;

    public JpathLookupServiceImpl(JpathCatalogService catalog, JpathContentReader content) {
        this.catalog = catalog;
        this.content = content;
    }

    private record Hit(JpathGrammar grammar, int score) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<JpathGrammarLookupView> grammar(Long userId, String q) {
        if (q == null || q.isBlank() || q.length() > MAX_QUERY || JpathGrammarMatcher.normalize(q).isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (JpathLevelInfo level : catalog.levels()) {
            if (!level.released()) {
                continue;
            }
            for (JpathGrammar g : content.visibleGrammar(level.level())) {
                int score = JpathGrammarMatcher.score(q, g.getPattern(), variants(g.getPatternVariantsJson()));
                if (score > 0) {
                    hits.add(new Hit(g, score));
                }
            }
        }
        return hits.stream()
                .sorted(Comparator.comparingInt(Hit::score).reversed()
                        .thenComparingInt(h -> ORDER.indexOf(h.grammar().getLevel()))
                        .thenComparingInt(h -> h.grammar().getSortOrder()))
                .limit(MAX_RESULTS)
                .map(h -> view(h.grammar()))
                .toList();
    }

    private JpathGrammarLookupView view(JpathGrammar g) {
        JpathExample ex = content.examples("GRAMMAR", g.getCode()).stream()
                .filter(e -> MAIN.equals(e.getRole())).findFirst().orElse(null);
        return new JpathGrammarLookupView(g.getCode(), g.getPattern(), g.getMeaningVi(), g.getFormation(), g.getLevel(),
                ex == null ? null : ex.getJa(), ex == null ? null : ex.getJaFurigana(), ex == null ? null : ex.getVi(),
                LESSON_URL + g.getCode());
    }

    private static List<String> variants(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
