package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathDaySummaryView;
import com.jlas.jlptpath.dto.JpathTabCount;
import com.jlas.jlptpath.dto.JpathWrongItemView;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathStudyEvent;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.repository.JpathStudyEventRepository;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.repository.UserStreaksRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Builds the day summary of a plan from today's study events (new learned, reviewed, accuracy, wrong
 * items), the CP the hook paid ({@code cp_awarded}), the mission streak (read only) and the level % before the day.
 */
@Component
public class JpathDaySummaryReader {

    private static final String CHECK_ANSWER = "CHECK_ANSWER";

    private final JpathStudyEventRepository events;
    private final JpathTabCountsReader tabCounts;
    private final JpathContentReader content;
    private final UserStreaksRepository streaks;

    public JpathDaySummaryReader(JpathStudyEventRepository events, JpathTabCountsReader tabCounts,
            JpathContentReader content, UserStreaksRepository streaks) {
        this.events = events;
        this.tabCounts = tabCounts;
        this.content = content;
        this.streaks = streaks;
    }

    @Transactional(readOnly = true)
    public JpathDaySummaryView read(Long userId, JpathDailyPlan plan) {
        LocalDate day = plan.getStudyDay();
        int newLearned = events.newLearnedByDay(userId, day, day).getOrDefault(day, 0);
        int reviewed = events.reviewedByDay(userId, day, day).getOrDefault(day, 0);
        long right = events.countByUserIdAndStudyDayAndActionAndCorrect(userId, day, CHECK_ANSWER, true);
        long wrong = events.countByUserIdAndStudyDayAndActionAndCorrect(userId, day, CHECK_ANSWER, false);
        Integer accuracy = right + wrong == 0 ? null : (int) Math.round(right * 100.0 / (right + wrong));
        int streak = streaks.findByUserId(userId).map(UserStreaks::getCurrentStreak).orElse(0);
        // counted_at is learner local time, like the study day.
        int before = JpathTabCount.levelPercent(
                tabCounts.readCountedBefore(userId, plan.getLevel(), day.atStartOfDay()));
        int after = JpathTabCount.levelPercent(tabCounts.read(userId, plan.getLevel()));
        return new JpathDaySummaryView(day, plan.getDayKind(), plan.getAllDoneAt() != null, plan.getLevel(),
                newLearned, reviewed, accuracy, plan.getCpAwarded(), streak, before, after, wrongItems(userId, day));
    }

    /** Each wrong item once, in the order it first went wrong. */
    private List<JpathWrongItemView> wrongItems(Long userId, LocalDate day) {
        Set<String> seen = new LinkedHashSet<>();
        List<JpathWrongItemView> out = new ArrayList<>();
        for (JpathStudyEvent e : events.findWrongOn(userId, day)) {
            if (seen.add(e.getItemType() + ":" + e.getItemCode())) {
                out.add(label(e.getItemType(), e.getItemCode()));
            }
        }
        return out;
    }

    private JpathWrongItemView label(JpathItemType type, String code) {
        JpathWrongItemView fallback = new JpathWrongItemView(type, code, code, null, null);
        return switch (type) {
            case VOCAB -> content.vocab(code)
                    .map(v -> new JpathWrongItemView(type, code, v.getExpression(), v.getReading(), v.getMeaningVi()))
                    .orElse(fallback);
            case KANJI -> content.kanji(code)
                    .map(k -> new JpathWrongItemView(type, code, k.getCharacter(), k.getHanViet(), k.getMeaningsVi()))
                    .orElse(fallback);
            case GRAMMAR -> content.grammar(code)
                    .map(g -> new JpathWrongItemView(type, code, g.getPattern(), null, g.getMeaningVi()))
                    .orElse(fallback);
            case KANA -> content.kanaItem(code)
                    .map(k -> new JpathWrongItemView(type, code, k.getKana(), k.getRomaji(), null))
                    .orElse(fallback);
        };
    }
}
