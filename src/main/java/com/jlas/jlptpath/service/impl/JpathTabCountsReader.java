package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathTabCount;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathKanaRowRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Progress counts of the learner's tabs at one level, and of the kana tab. */
@Component
public class JpathTabCountsReader {

    /** Kana rows of both scripts (Hiragana 18 + Katakana 18). */
    public static final int KANA_ROWS = 36;

    private final JpathUserItemRepository userItems;
    private final JpathKanaRowRepository kanaRows;
    private final JpathContentReader content;

    public JpathTabCountsReader(JpathUserItemRepository userItems, JpathKanaRowRepository kanaRows,
                                JpathContentReader content) {
        this.userItems = userItems;
        this.kanaRows = kanaRows;
        this.content = content;
    }

    /** VOCAB, KANJI, GRAMMAR of {@code level} in that order; total = learnable visible items. */
    @Transactional(readOnly = true)
    public Map<JpathTab, JpathTabCount> read(Long userId, String level) {
        Map<JpathTab, JpathTabCount> out = new EnumMap<>(JpathTab.class);
        out.put(JpathTab.VOCAB, count(userId, level, JpathItemType.VOCAB));
        out.put(JpathTab.KANJI, count(userId, level, JpathItemType.KANJI));
        out.put(JpathTab.GRAMMAR, count(userId, level, JpathItemType.GRAMMAR));
        return Collections.unmodifiableMap(out);
    }

    /** Kana tab: completed rows of {@link #KANA_ROWS}; kana progress is counted by rows, so known/notebook are 0. */
    @Transactional(readOnly = true)
    public JpathTabCount kana(Long userId) {
        return new JpathTabCount(kanaRows.countCompletedRows(userId), 0, 0, KANA_ROWS);
    }

    private JpathTabCount count(Long userId, String level, JpathItemType type) {
        return new JpathTabCount(
                userItems.countByUserIdAndLevelAndItemTypeAndCountedAtIsNotNull(userId, level, type),
                userItems.countByUserIdAndLevelAndItemTypeAndState(userId, level, type, JpathItemState.KNOWN),
                userItems.countByUserIdAndLevelAndItemTypeAndState(userId, level, type, JpathItemState.NOTEBOOK),
                content.learnableCount(level, type));
    }
}
