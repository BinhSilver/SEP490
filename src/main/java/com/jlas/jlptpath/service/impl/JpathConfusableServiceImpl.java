package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathConfusablePairView;
import com.jlas.jlptpath.dto.JpathConfusableSide;
import com.jlas.jlptpath.entity.JpathConfusablePair;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.repository.JpathConfusablePairRepository;
import com.jlas.jlptpath.repository.JpathStudyEventRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.service.JpathConfusableService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] {@link JpathConfusableService}: catalogue pairs filtered by the learner's rows and visibility. */
@Service
public class JpathConfusableServiceImpl implements JpathConfusableService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JpathConfusablePairRepository pairs;
    private final JpathUserItemRepository userItems;
    private final JpathStudyEventRepository events;
    private final JpathContentReader content;

    public JpathConfusableServiceImpl(JpathConfusablePairRepository pairs, JpathUserItemRepository userItems,
                                      JpathStudyEventRepository events, JpathContentReader content) {
        this.pairs = pairs;
        this.userItems = userItems;
        this.events = events;
        this.content = content;
    }

    @Override
    @Transactional(readOnly = true)
    public List<JpathConfusablePairView> pairs(Long userId, String tab) {
        JpathItemType type = type(tab);
        Set<String> learned = userItems.findByUserIdAndItemType(userId, type).stream()
                .filter(i -> i.getFirstLearnedAt() != null || i.getState() == JpathItemState.KNOWN)
                .map(JpathUserItem::getItemCode).collect(Collectors.toSet());
        List<JpathConfusablePair> candidates = pairs.findByItemTypeOrderByCodeAAscCodeBAsc(type).stream()
                .filter(p -> learned.contains(p.getCodeA()) && learned.contains(p.getCodeB())).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> codes = candidates.stream().flatMap(p -> Stream.of(p.getCodeA(), p.getCodeB())).distinct()
                .toList();
        Map<String, JpathConfusableSide> sides = sides(type, codes);
        Map<String, Integer> wrong = new HashMap<>();
        for (Object[] row : events.countWrongAnswersByItem(userId, type)) {
            wrong.put((String) row[0], ((Number) row[1]).intValue());
        }
        List<JpathConfusablePairView> out = new ArrayList<>();
        for (JpathConfusablePair p : candidates) {
            JpathConfusableSide a = sides.get(p.getCodeA());
            JpathConfusableSide b = sides.get(p.getCodeB());
            if (a != null && b != null) {
                out.add(new JpathConfusablePairView(type.name(), a, b,
                        wrong.getOrDefault(p.getCodeA(), 0) + wrong.getOrDefault(p.getCodeB(), 0),
                        p.getDiffVi(), parse(p.getExamplesJson()), parse(p.getQuestionJson())));
            }
        }
        out.sort(Comparator.comparingInt(JpathConfusablePairView::wrongCount).reversed()
                .thenComparing(v -> v.a().code()));
        return out;
    }

    /** The visible sides among {@code codes}; a hidden item is missing, so its pairs drop. */
    private Map<String, JpathConfusableSide> sides(JpathItemType type, List<String> codes) {
        Map<String, JpathConfusableSide> out = new HashMap<>();
        if (type == JpathItemType.VOCAB) {
            for (JpathVocabulary v : content.vocabs(codes)) {
                out.put(v.getCode(), new JpathConfusableSide(v.getCode(), v.getExpression(), v.getReading(),
                        v.getMeaningVi()));
            }
        } else if (type == JpathItemType.GRAMMAR) {
            for (var g : content.grammars(codes)) {
                out.put(g.getCode(), new JpathConfusableSide(g.getCode(), g.getPattern(), null, g.getMeaningVi()));
            }
        } else {
            for (JpathKanji k : content.kanjis(codes)) {
                out.put(k.getCode(), new JpathConfusableSide(k.getCode(), k.getCharacter(),
                        String.join("・", readings(k.getLevelReadingsJson())), k.getMeaningsVi()));
            }
        }
        return out;
    }

    private static JpathItemType type(String tab) {
        String t = tab == null ? "" : tab.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "VOCAB" -> JpathItemType.VOCAB;
            case "KANJI" -> JpathItemType.KANJI;
            case "GRAMMAR" -> JpathItemType.GRAMMAR;
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tab không hợp lệ.");
        };
    }

    private static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> readings(String json) {
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
