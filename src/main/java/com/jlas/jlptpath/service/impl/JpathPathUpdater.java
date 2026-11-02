package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSliderUpdateRequest;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathSliderKind;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathSliderAdjustmentRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathTodayTargetsPort;
import com.jlas.jlptpath.util.JpathPacingEngine;
import com.jlas.jlptpath.util.JpathSliderBounds;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.jlas.jlptpath.service.impl.JpathPathRules.MAX_ADJUSTMENTS;

/**
 * [JLAS-W0108] Changes to the active path after it was created: sliders (policy #29), Rebalance, the study mode and the
 * priority mode (#30). Each change locks the open level row first ({@code PESSIMISTIC_WRITE}), so two tabs changing
 * the same path run one after the other. Runs inside the caller's transaction.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpathPathUpdater {

    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository userLevels;
    private final JpathSliderAdjustmentRepository adjustments;
    private final JpathPathViewAssembler views;
    private final ObjectProvider<JpathTodayTargetsPort> todayTargets;

    public JpathPathUpdater(JpathUserPathRepository paths, JpathUserLevelRepository userLevels,
            JpathSliderAdjustmentRepository adjustments, JpathPathViewAssembler views,
            ObjectProvider<JpathTodayTargetsPort> todayTargets) {
        this.paths = paths;
        this.userLevels = userLevels;
        this.adjustments = adjustments;
        this.views = views;
        this.todayTargets = todayTargets;
    }

    /**
     * Result of a change: the path, its locked open level row, the pacing after the change and the tabs the change
     * reaches only from tomorrow ({@code appliesFromTomorrow} null = nothing was changed).
     */
    public record Change(JpathUserPath path, JpathUserLevel level, JpathPathViewAssembler.Pacing pacing,
            Set<JpathTab> appliesFromTomorrow) {
    }

    /**
     * Policy #29. Values outside the open level's limits → JPATH_SLIDER_OUT_OF_RANGE; the same values → nothing is
     * written. The first drag after creating the path is free (FIRST_DRAG, not counted); later changes are UPDATE and
     * use one of the 2 adjustments of the level (none left → JPATH_ADJUSTMENTS_EXHAUSTED before anything is written).
     */
    public Change updateSliders(Long userId, JpathSliderUpdateRequest request) {
        if (request == null || request.vocab() == null || request.kanji() == null || request.grammar() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu số mục mỗi ngày.");
        }
        JpathUserPath path = requireActive(userId);
        JpathSliderValues wanted = new JpathSliderValues(request.vocab(), request.kanji(), request.grammar());
        JpathSliderBounds.requireInRange(path.getOpenLevel(), wanted);
        JpathUserLevel level = lockOpenLevel(path);
        JpathSliderValues current = sliders(level);
        if (current.equals(wanted)) {
            return new Change(path, level, views.pacing(path, level), null);
        }
        JpathSliderKind kind;
        boolean counted;
        if (!level.isFirstDragUsed()) {
            kind = JpathSliderKind.FIRST_DRAG;
            counted = false;
            level.setFirstDragUsed(true);
        } else {
            if (level.getAdjustmentsUsed() >= MAX_ADJUSTMENTS) {
                throw new ApiException(ErrorCode.JPATH_ADJUSTMENTS_EXHAUSTED);
            }
            kind = JpathSliderKind.UPDATE;
            counted = true;
            level.setAdjustmentsUsed(level.getAdjustmentsUsed() + 1);
        }
        return applySliders(path, level, kind, counted, current, wanted);
    }

    /** Rebalance: back to the recommendation for the daily minutes; never counted, allowed with no adjustment left. */
    public Change rebalance(Long userId) {
        JpathUserPath path = requireActive(userId);
        JpathUserLevel level = lockOpenLevel(path);
        JpathSliderValues current = sliders(level);
        JpathSliderValues recommended = JpathPacingEngine.recommend(path.getOpenLevel(), path.getDailyMinutes());
        if (current.equals(recommended)) {
            return new Change(path, level, views.pacing(path, level), null);
        }
        return applySliders(path, level, JpathSliderKind.REBALANCE, false, current, recommended);
    }

    /** Switches between Gộp and Tách; the sliders and adjustments stay as they are. */
    public Change changeMode(Long userId, JpathStudyMode mode) {
        if (mode == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Hãy chọn cách học Gộp hoặc Tách.");
        }
        JpathUserPath path = requireActive(userId);
        JpathUserLevel level = lockOpenLevel(path);
        if (mode == path.getStudyMode()) {
            return new Change(path, level, views.pacing(path, level), null);
        }
        path.setStudyMode(mode);
        JpathPathViewAssembler.Pacing pacing = refreshPriority(path, level);
        paths.flush();
        return new Change(path, level, pacing, refreshToday(userId));
    }

    /** Recomputes the pace of the active path and stores its priority mode (#30); no active path → nothing. */
    public void refreshPacing(Long userId) {
        paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE).ifPresent(path -> {
            refreshPriority(path, lockOpenLevel(path));
            userLevels.flush();
        });
    }

    private Change applySliders(JpathUserPath path, JpathUserLevel level, JpathSliderKind kind, boolean counted,
            JpathSliderValues before, JpathSliderValues after) {
        level.setSliderVocab(after.vocab());
        level.setSliderKanji(after.kanji());
        level.setSliderGrammar(after.grammar());
        adjustments.save(JpathPathRules.adjustment(path.getId(), path.getOpenLevel(), kind, counted, before, after,
                path.getDailyMinutes()));
        JpathPathViewAssembler.Pacing pacing = refreshPriority(path, level);
        // Today's targets are refreshed from the stored sliders.
        adjustments.flush();
        return new Change(path, level, pacing, refreshToday(path.getUserId()));
    }

    /**
     * Pace with the learner's own sliders; priority mode on when items of the open level will not fit before an exam
     * less than 60 days away (#30) — the same rule as the PRIORITY_MODE notice of the path view.
     */
    private JpathPathViewAssembler.Pacing refreshPriority(JpathUserPath path, JpathUserLevel level) {
        JpathPathViewAssembler.Pacing pacing = views.pacing(path, level);
        level.setPriorityMode(pacing.pace().skippedLowPriority() > 0);
        return pacing;
    }

    /** Tabs that keep today's targets; empty without the port (nothing refreshes today's plan then). */
    private Set<JpathTab> refreshToday(Long userId) {
        JpathTodayTargetsPort port = todayTargets.getIfAvailable();
        if (port == null) {
            return EnumSet.noneOf(JpathTab.class);
        }
        Set<JpathTab> kept = port.refreshTodayTargets(userId);
        return kept == null ? EnumSet.noneOf(JpathTab.class) : kept;
    }

    private JpathUserPath requireActive(Long userId) {
        return paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    private JpathUserLevel lockOpenLevel(JpathUserPath path) {
        return userLevels.findForUpdate(path.getId(), path.getOpenLevel())
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    private static JpathSliderValues sliders(JpathUserLevel level) {
        return new JpathSliderValues(level.getSliderVocab(), level.getSliderKanji(), level.getSliderGrammar());
    }
}
