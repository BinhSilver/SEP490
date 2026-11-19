package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathTabCount;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathKanaRowRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import java.time.LocalDateTime;
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
        // Bug 113: one grouped query per level instead of three counts per tab
        Map<JpathItemType, long[]> rows = new EnumMap<>(JpathItemType.class);
        for (Object[] row : userItems.tabCountsByType(userId, level)) {
            if (row[0] instanceof JpathItemType type) {
                rows.put(type, new long[] {num(row[1]), num(row[2]), num(row[3])});
            }
        }
        Map<JpathTab, JpathTabCount> out = new EnumMap<>(JpathTab.class);
        for (JpathTab tab : new JpathTab[] {JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR}) {
            JpathItemType type = JpathItemType.valueOf(tab.name());
            long[] r = rows.getOrDefault(type, new long[3]);
            out.put(tab, new JpathTabCount(r[0], r[1], r[2], content.learnableCount(level, type)));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Like {@link #read} but counting only items counted before {@code before} (learner local time): the level %
     * at the start of a day.
     */
    @Transactional(readOnly = true)
    public Map<JpathTab, JpathTabCount> readCountedBefore(Long userId, String level, LocalDateTime before) {
        Map<JpathTab, JpathTabCount> out = new EnumMap<>(JpathTab.class);
        for (JpathTab tab : new JpathTab[] {JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR}) {
            JpathItemType type = JpathItemType.valueOf(tab.name());
            out.put(tab, new JpathTabCount(
                    userItems.countByUserIdAndLevelAndItemTypeAndCountedAtBefore(userId, level, type, before),
                    0, 0, content.learnableCount(level, type)));
        }
        return Collections.unmodifiableMap(out);
    }

    /** Kana tab: completed rows of {@link #KANA_ROWS}; kana progress is counted by rows, so known/notebook are 0. */
    @Transactional(readOnly = true)
    public JpathTabCount kana(Long userId) {
        return new JpathTabCount(kanaRows.countCompletedRows(userId), 0, 0, KANA_ROWS);
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
