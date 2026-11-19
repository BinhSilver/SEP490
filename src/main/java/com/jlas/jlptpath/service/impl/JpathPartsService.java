package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathItemRef;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Owner 05/11: a tab is shown as study parts, not as the whole list. A part holds as many items as the
 * tab's slider (one day's new items): the parts already learned (grouped by the day they were first learned), today's
 * part (learned today + still planned), then the coming parts of the queue cut by the slider.
 */
@Service
public class JpathPartsService {

    /** One item of a part. */
    public record Item(String code, String label, String reading, String meaning) {
    }

    /** One part: DONE (a past day), TODAY, NEXT (coming). {@code day} is the study day of a DONE / TODAY part. */
    public record Part(int index, String status, LocalDate day, List<Item> items) {
    }

    /** The parts of one tab of the open level. */
    public record Parts(String tab, String level, int perPart, int total, int learned, List<Part> parts) {
    }

    private static final int MAX_QUEUE = 5000;
    private static final List<String> LEVEL_ORDER = List.of("N5", "N4", "N3", "N2", "N1");

    /** Owner 06/11 warm-up: offered in the first days of a newly opened level above N5, on the level below. */
    public record Warmup(boolean offer, String level, String lowerLevel, LocalDate openedOn) {
    }

    static final int WARMUP_DAYS = 3;

    /** Owner 06/11: the Smart Test result the path was made from, for the "Đầu vào" block of the Stats page. */
    public record EntryResult(boolean fromSmartTest, String abilityBand, String estimatedLevel, Integer vocabScore,
                              Integer kanjiScore, Integer grammarScore, boolean partial, LocalDate pathCreatedOn,
                              String openLevel) {
    }

    private org.springframework.beans.factory.ObjectProvider<JpathSmartTestReader> smartTests;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSmartTests(org.springframework.beans.factory.ObjectProvider<JpathSmartTestReader> smartTests) {
        this.smartTests = smartTests;
    }

    @Transactional(readOnly = true)
    public EntryResult entryResult(Long userId) {
        JpathUserPath path = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE).orElse(null);
        if (path == null) return new EntryResult(false, null, null, null, null, null, false, null, null);
        LocalDate created = path.getCreatedAt() == null ? null : path.getCreatedAt().toLocalDate();
        JpathSmartTestReader reader = smartTests == null ? null : smartTests.getIfAvailable();
        if (path.getAssessmentAttemptId() == null || reader == null) {
            return new EntryResult(false, null, null, null, null, null, false, created, path.getOpenLevel());
        }
        try {
            com.jlas.jlptpath.dto.JpathSmartTestSnapshot s = reader.read(userId, path.getAssessmentAttemptId());
            return new EntryResult(true, s.abilityBand(), s.estimatedLevel(), s.vocabScore(), s.kanjiScore(),
                    s.grammarScore(), s.partial(), created, path.getOpenLevel());
        } catch (RuntimeException e) {
            return new EntryResult(false, null, null, null, null, null, false, created, path.getOpenLevel());
        }
    }

    @Transactional(readOnly = true)
    public Warmup warmup(Long userId) {
        JpathUserPath path = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE).orElse(null);
        if (path == null) return new Warmup(false, null, null, null);
        String level = path.getOpenLevel();
        int at = LEVEL_ORDER.indexOf(level);
        JpathUserLevel row = levels.findById(new JpathUserLevelId(path.getId(), level)).orElse(null);
        if (at <= 0 || row == null || row.getOpenedAt() == null) return new Warmup(false, level, null, null);
        LocalDate opened = row.getOpenedAt().toLocalDate();
        boolean recent = !clock.today(userId).isAfter(opened.plusDays(WARMUP_DAYS - 1));
        return new Warmup(recent, level, LEVEL_ORDER.get(at - 1), opened);
    }

    /**
     * Owner 06/11 "Tôi biết tuần này", opened to every learner on any week by owner 11/11 (F64). {@code codes} = the next week of the tab (the
     * coming {@link #WEEK_PARTS} parts); ≥ 90% on the check counts the week as known (topic-check rules).
     */
    public record WeekSkip(boolean offer, String tab, String level, int items, List<String> codes) {
    }

    /** Parts of one map week (jpath-parts.js WEEK_SPAN). */
    static final int WEEK_PARTS = 7;

    @Transactional(readOnly = true)
    public WeekSkip weekSkip(Long userId, JpathTab tab) {
        return weekSkip(userId, tab, null);
    }

    /** {@code kanaRows}: KANA only — how many rows to cover; 0 or less = the whole table left (owner 11/11, F65). */
    @Transactional(readOnly = true)
    public WeekSkip weekSkip(Long userId, JpathTab tab, Integer kanaRows) {
        String tabName = tab == null ? null : tab.name();
        JpathUserPath path = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE).orElse(null);
        if (tab == JpathTab.KANA) {
            return kanaSkip(userId, path, kanaRows == null ? KANA_SKIP_ROWS : (kanaRows <= 0 ? Integer.MAX_VALUE : kanaRows));
        }
        // owner 11/11 (F64): every learner may prove a coming week known, whatever the Smart Test band
        if (path == null) {
            return new WeekSkip(false, tabName, null, 0, List.of());
        }
        Parts p = parts(userId, tab);
        int firstNext = -1;
        for (int i = 0; i < p.parts().size(); i++) {
            if ("NEXT".equals(p.parts().get(i).status())) {
                firstNext = i;
                break;
            }
        }
        if (firstNext < 0) {
            return new WeekSkip(false, tabName, p.level(), 0, List.of());
        }
        // the rest of the map week the first coming part sits in (a whole week when the week has not started)
        int weekEnd = (firstNext / WEEK_PARTS + 1) * WEEK_PARTS;
        List<String> codes = new ArrayList<>();
        for (int i = firstNext; i < Math.min(p.parts().size(), weekEnd); i++) {
            p.parts().get(i).items().forEach(it -> codes.add(it.code()));
        }
        return new WeekSkip(!codes.isEmpty(), tabName, p.level(), codes.size(), codes);
    }

    /** Kana rows one quick kana check covers (AI 1 #11, owner 10/11). */
    static final int KANA_SKIP_ROWS = 5;

    /**
     * AI 1 #11 (owner 10/11 "thêm kiểm tra nhanh"): a learner who chose to REVIEW kana may test out of the next
     * {@link #KANA_SKIP_ROWS} rows not passed yet; ≥ 90% marks them known like the week check.
     */
    private WeekSkip kanaSkip(Long userId, JpathUserPath path, int maxRows) {
        String level = path == null ? null : path.getOpenLevel();
        // owner 11/11 (F64): any learner on a path may test out of kana, not only those who chose REVIEW
        if (path == null || lists == null) {
            return new WeekSkip(false, JpathTab.KANA.name(), level, 0, List.of());
        }
        List<String> codes = new ArrayList<>();
        int taken = 0;
        for (com.jlas.jlptpath.dto.JpathKanaRowView row : lists.kana(userId, null)) {
            if (row.passed()) continue;
            row.kanas().forEach(k -> codes.add(k.code()));
            if (++taken >= maxRows) break;
        }
        return new WeekSkip(!codes.isEmpty(), JpathTab.KANA.name(), level, codes.size(), codes);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.jlptpath.repository.JpathEntryPreferenceRepository preferences;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.jlas.jlptpath.service.JpathListService lists;

    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathUserItemRepository userItems;
    private final JpathContentReader content;
    private final JpathDailyPlanService dailyPlan;
    private final JpathClock clock;

    public JpathPartsService(JpathUserPathRepository paths, JpathUserLevelRepository levels,
                             JpathUserItemRepository userItems, JpathContentReader content,
                             JpathDailyPlanService dailyPlan, JpathClock clock) {
        this.paths = paths;
        this.levels = levels;
        this.userItems = userItems;
        this.content = content;
        this.dailyPlan = dailyPlan;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Parts parts(Long userId, JpathTab tab) {
        if (tab != JpathTab.VOCAB && tab != JpathTab.KANJI && tab != JpathTab.GRAMMAR) {
            return new Parts(tab == null ? null : tab.name(), null, 0, 0, 0, List.of());
        }
        JpathUserPath path = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE).orElse(null);
        if (path == null) {
            return new Parts(tab.name(), null, 0, 0, 0, List.of());
        }
        String level = path.getOpenLevel();
        JpathUserLevel row = levels.findById(new JpathUserLevelId(path.getId(), level)).orElse(null);
        int per = Math.max(1, row == null ? 10 : switch (tab) {
            case VOCAB -> row.getSliderVocab();
            case KANJI -> row.getSliderKanji();
            default -> row.getSliderGrammar();
        });
        boolean priority = row != null && row.isPriorityMode();
        JpathItemType type = JpathItemType.valueOf(tab.name());
        LocalDate today = clock.today(userId);

        // [JLAS-W0108] F154g: learned/known items of this level (including KNOWN items without firstLearnedAt)
        Map<LocalDate, List<String>> byDay = new LinkedHashMap<>();
        List<String> undatedKnown = new ArrayList<>();
        List<JpathUserItem> learned = new ArrayList<>(userItems.findByUserIdAndItemType(userId, type));
        learned.removeIf(u -> (u.getLevel() != null && !level.equals(u.getLevel()))
                || (learnedAt(u) == null && u.getState() != JpathItemState.KNOWN));
        learned.sort(Comparator.comparing(JpathPartsService::learnedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        Set<String> placed = new LinkedHashSet<>();
        for (JpathUserItem u : learned) {
            placed.add(u.getItemCode());
            LocalDateTime at = learnedAt(u);
            if (at == null) {
                undatedKnown.add(u.getItemCode());
            } else {
                byDay.computeIfAbsent(at.toLocalDate(), d -> new ArrayList<>()).add(u.getItemCode());
            }
        }
        List<String> todayCodes = new ArrayList<>(byDay.getOrDefault(today, List.of()));
        byDay.remove(today);
        boolean todayLeft = false;
        for (JpathItemRef ref : dailyPlan.plannedNewItems(userId, tab)) {
            if (placed.add(ref.code())) {
                todayCodes.add(ref.code());
                todayLeft = true;
            }
        }
        List<String> ordered = content.learnableOrderedCodes(level, type, priority);
        List<String> queue = ordered.isEmpty()
                ? content.nextNewCodes(userId, level, type, MAX_QUEUE, priority, c -> !placed.contains(c))
                : ordered.stream().filter(c -> !placed.contains(c)).limit(MAX_QUEUE).toList();

        List<String> allCodes = new ArrayList<>(undatedKnown);
        allCodes.addAll(union(byDay.values(), todayCodes, queue));
        Map<String, Item> labels = labels(type, allCodes);
        List<Part> parts = new ArrayList<>();
        int index = 1;
        if (!undatedKnown.isEmpty()) {
            parts.add(new Part(index++, "DONE", null, items(undatedKnown, labels)));
        }
        for (Map.Entry<LocalDate, List<String>> e : byDay.entrySet()) {
            parts.add(new Part(index++, "DONE", e.getKey(), items(e.getValue(), labels)));
        }
        if (!todayCodes.isEmpty()) {
            parts.add(new Part(index++, todayLeft ? "TODAY" : "TODAY_DONE", today, items(todayCodes, labels)));
        }
        for (int i = 0; i < queue.size(); i += per) {
            parts.add(new Part(index++, "NEXT", null, items(queue.subList(i, Math.min(queue.size(), i + per)), labels)));
        }
        int learnedCount = learned.size();
        return new Parts(tab.name(), level, per, learnedCount + (todayCodes.size() - countToday(learned, today))
                + queue.size(), learnedCount, parts);
    }

    private static LocalDateTime learnedAt(JpathUserItem u) {
        if (u.getFirstLearnedAt() != null) {
            return u.getFirstLearnedAt();
        }
        if (u.getCountedAt() != null) {
            return u.getCountedAt();
        }
        return u.getState() == JpathItemState.KNOWN ? u.getKnownAt() : null;
    }

    private static int countToday(List<JpathUserItem> learned, LocalDate today) {
        return (int) learned.stream().filter(u -> {
            LocalDateTime at = learnedAt(u);
            return at != null && at.toLocalDate().equals(today);
        }).count();
    }

    private static List<String> union(java.util.Collection<List<String>> a, List<String> b, List<String> c) {
        List<String> all = new ArrayList<>();
        a.forEach(all::addAll);
        all.addAll(b);
        all.addAll(c);
        return all;
    }

    private static List<Item> items(List<String> codes, Map<String, Item> labels) {
        List<Item> out = new ArrayList<>(codes.size());
        for (String code : codes) {
            out.add(labels.getOrDefault(code, new Item(code, code, null, null)));
        }
        return out;
    }

    private Map<String, Item> labels(JpathItemType type, List<String> codes) {
        Map<String, Item> out = new HashMap<>();
        if (codes.isEmpty()) return out;
        switch (type) {
            case VOCAB -> {
                for (JpathVocabulary v : content.vocabs(codes)) {
                    out.put(v.getCode(), new Item(v.getCode(), v.getExpression(), v.getReading(), v.getMeaningVi()));
                }
            }
            case KANJI -> {
                for (JpathKanji k : content.kanjis(codes)) {
                    out.put(k.getCode(), new Item(k.getCode(), k.getCharacter(),
                            k.getHanViet() == null ? null : k.getHanViet().toUpperCase(java.util.Locale.ROOT), k.getMeaningsVi()));
                }
            }
            case GRAMMAR -> {
                for (JpathGrammar g : content.grammars(codes)) {
                    out.put(g.getCode(), new Item(g.getCode(), g.getPattern(), null, g.getMeaningVi()));
                }
            }
            default -> { }
        }
        return out;
    }
}
