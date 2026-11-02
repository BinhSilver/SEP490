package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathLevelProgressView;
import com.jlas.jlptpath.dto.JpathTabCount;
import com.jlas.jlptpath.dto.JpathTabProgressView;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.service.JpathProgressService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Tab and level progress from {@link JpathTabCountsReader}; stage changes of policy #15. */
@Service
public class JpathProgressServiceImpl implements JpathProgressService {

    private static final List<JpathTab> LEVEL_TABS = List.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR);
    private static final int SKILL_STAGE_PERCENT = 80;

    private final JpathTabCountsReader tabCounts;
    private final JpathPathService paths;
    private final JpathUserPathRepository pathRepository;
    private final JpathUserLevelRepository levels;
    private final JpathUserItemRepository userItems;
    private final JpathClock clock;

    public JpathProgressServiceImpl(JpathTabCountsReader tabCounts, JpathPathService paths,
                                    JpathUserPathRepository pathRepository, JpathUserLevelRepository levels,
                                    JpathUserItemRepository userItems, JpathClock clock) {
        this.tabCounts = tabCounts;
        this.paths = paths;
        this.pathRepository = pathRepository;
        this.levels = levels;
        this.userItems = userItems;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public JpathLevelProgressView level(Long userId, String level) {
        Map<JpathTab, JpathTabCount> counts = tabCounts.read(userId, level);
        List<JpathTabProgressView> tabs = new ArrayList<>();
        int known = 0;
        int notebook = 0;
        int total = 0;
        for (JpathTab tab : LEVEL_TABS) {
            JpathTabProgressView view = view(userId, level, tab, counts.get(tab));
            tabs.add(view);
            known += view.known();
            notebook += view.notebook();
            total += view.total();
        }
        Optional<JpathUserPath> path = paths.findActive(userId);
        JpathStage stage = path.flatMap(p -> levels.findById(new JpathUserLevelId(p.getId(), level)))
                .map(JpathUserLevel::getStage).orElse(JpathStage.STUDY);
        if (path.map(JpathUserPath::isKanaTab).orElse(false)) {
            tabs.add(view(userId, level, JpathTab.KANA, tabCounts.kana(userId)));
        }
        return new JpathLevelProgressView(level, levelPercent(counts), stage, List.copyOf(tabs), known, notebook,
                total);
    }

    @Override
    @Transactional(readOnly = true)
    public JpathTabProgressView tab(Long userId, String level, JpathTab tab) {
        if (tab == JpathTab.KANA) {
            return view(userId, level, tab, tabCounts.kana(userId));
        }
        if (!LEVEL_TABS.contains(tab)) {
            throw new IllegalArgumentException("No progress for tab " + tab);
        }
        return view(userId, level, tab, tabCounts.read(userId, level).get(tab));
    }

    @Override
    @Transactional
    public void reevaluateStage(Long userId) {
        // Lock order: path, then level (Task 20); a locking read also sees the latest committed path row.
        Optional<JpathUserPath> found = pathRepository.findActiveForUpdate(userId);
        if (found.isEmpty()) {
            return;
        }
        JpathUserPath path = found.get();
        if (path.isKanaTab() && path.getKanaCompletedAt() == null
                && tabCounts.kana(userId).counted() >= JpathTabCountsReader.KANA_ROWS) {
            path.setKanaCompletedAt(clock.now());
        }
        JpathUserLevel open = levels.findForUpdate(path.getId(), path.getOpenLevel()).orElse(null);
        if (open == null || open.getStage() != JpathStage.STUDY) {
            return; // SKILL_PRACTICE, EXAM and PASSED (also after lowering onto a passed level) stay as they are
        }
        Map<JpathTab, JpathTabCount> counts = tabCounts.read(userId, path.getOpenLevel());
        if (LEVEL_TABS.stream().allMatch(t -> reachesSkillStage(counts.get(t)))) {
            open.setStage(JpathStage.SKILL_PRACTICE);
            open.setSkillPracticeAt(clock.now());
        }
    }

    /** {@code counted * 100 >= 80 * total} with total &gt; 0 (exact, no rounding). */
    static boolean reachesSkillStage(JpathTabCount c) {
        return c.total() > 0 && c.counted() * 100 >= SKILL_STAGE_PERCENT * c.total();
    }

    /** floor(100 x exact mean of the three tab ratios); a tab with total 0 counts 0, a ratio caps at 1. */
    static int levelPercent(Map<JpathTab, JpathTabCount> counts) {
        // Sum of counted/total over a common denominator keeps the mean exact.
        long numerator = 0;
        long denominator = 1;
        for (JpathTab tab : LEVEL_TABS) {
            JpathTabCount c = counts.get(tab);
            boolean empty = c == null || c.total() <= 0;
            long t = empty ? 1 : c.total();
            long n = empty ? 0 : Math.min(c.counted(), c.total());
            numerator = numerator * t + n * denominator;
            denominator *= t;
        }
        return (int) (numerator * 100 / (denominator * LEVEL_TABS.size()));
    }

    private JpathTabProgressView view(Long userId, String level, JpathTab tab, JpathTabCount c) {
        int learning = 0;
        if (tab != JpathTab.KANA) {
            learning = (int) userItems.countByUserIdAndLevelAndItemTypeAndState(userId, level,
                    JpathItemType.valueOf(tab.name()), JpathItemState.LEARNING);
        }
        int notebook = tab == JpathTab.GRAMMAR ? 0 : (int) c.notebook(); // grammar has no notebook
        return new JpathTabProgressView(tab, (int) c.total(), (int) c.counted(), c.percent(), (int) c.known(),
                notebook, learning);
    }
}
