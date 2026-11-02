package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathSliderKind;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathSliderAdjustmentRepository;
import com.jlas.jlptpath.repository.JpathStudyEventRepository;
import com.jlas.jlptpath.repository.JpathStudySessionRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathSettingsService;
import com.jlas.jlptpath.service.JpathTodayTargetsPort;
import com.jlas.jlptpath.util.JpathPacingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.jlas.jlptpath.service.impl.JpathPathRules.LEVELS;

/**
 * [JLAS-W0108] Changes of the whole path: study a lower level, restart from the beginning, pause for a goal change and
 * resume. Runs inside the caller's transaction and follows the lock order of {@link JpathPathUpdater}: the path row is
 * locked first (and is the first load of the path in the transaction), then a level row, then anything is written.
 * Status changes go through {@link JpathUserPath#setStatus} so {@code active_flag} follows the status
 * (chk_jpath_path_active_flag) and are flushed at once, before any new ACTIVE path can be inserted.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpathPathChanger {

    private static final Logger log = LoggerFactory.getLogger(JpathPathChanger.class);
    static final String LOWER_ONLY = "Chỉ chuyển được xuống cấp thấp hơn.";

    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository userLevels;
    private final JpathSliderAdjustmentRepository adjustments;
    private final JpathUserItemRepository userItems;
    private final JpathDailyPlanRepository plans;
    private final JpathStudySessionRepository sessions;
    private final JpathCheckSetRepository checks;
    private final JpathStudyEventRepository events;
    private final JpathCatalogService catalog;
    private final JpathPathViewAssembler views;
    private final JpathClock clock;
    private final ObjectProvider<JpathTodayTargetsPort> todayTargets;

    public JpathPathChanger(JpathUserPathRepository paths, JpathUserLevelRepository userLevels,
            JpathSliderAdjustmentRepository adjustments, JpathUserItemRepository userItems,
            JpathDailyPlanRepository plans, JpathStudySessionRepository sessions, JpathCheckSetRepository checks,
            JpathStudyEventRepository events, JpathCatalogService catalog, JpathPathViewAssembler views,
            JpathClock clock, ObjectProvider<JpathTodayTargetsPort> todayTargets) {
        this.paths = paths;
        this.userLevels = userLevels;
        this.adjustments = adjustments;
        this.userItems = userItems;
        this.plans = plans;
        this.sessions = sessions;
        this.checks = checks;
        this.events = events;
        this.catalog = catalog;
        this.views = views;
        this.clock = clock;
        this.todayTargets = todayTargets;
    }

    /**
     * Opens a released level below the open level. The level row is created when the path has none (recommended
     * sliders for the daily minutes, logged as LEVEL_START, not counted); an existing row is kept as it was, and so are
     * the rows of the levels above (progress to come back to). Today's targets are refreshed through the port.
     */
    public JpathPathUpdater.Change lowerLevel(Long userId, String level) {
        String wanted = JpathPathRules.releasedLevel(catalog, level);
        JpathUserPath path = lockActive(userId);
        if (LEVELS.indexOf(wanted) >= LEVELS.indexOf(path.getOpenLevel())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, LOWER_ONLY);
        }
        JpathUserLevel row = userLevels.findForUpdate(path.getId(), wanted).orElse(null);
        if (row == null) {
            JpathSliderValues recommended = JpathPacingEngine.recommend(wanted, path.getDailyMinutes());
            row = userLevels.save(JpathUserLevel.builder()
                    .pathId(path.getId())
                    .level(wanted)
                    .stage(JpathStage.STUDY)
                    .sliderVocab(recommended.vocab())
                    .sliderKanji(recommended.kanji())
                    .sliderGrammar(recommended.grammar())
                    .adjustmentsUsed(0)
                    .firstDragUsed(false)
                    .openedAt(clock.now())
                    .build());
            adjustments.save(JpathPathRules.adjustment(path.getId(), wanted, JpathSliderKind.LEVEL_START, false, null,
                    recommended, path.getDailyMinutes()));
        }
        path.setOpenLevel(wanted);
        // Priority mode (#30) of the level now open, as JpathPathUpdater keeps it.
        JpathPathViewAssembler.Pacing pacing = views.pacing(path, row);
        row.setPriorityMode(pacing.pace().skippedLowPriority() > 0);
        // The port reads the stored open level and sliders.
        paths.flush();
        return new JpathPathUpdater.Change(path, row, pacing, JpathPathRules.refreshToday(todayTargets, userId));
    }

    /**
     * Restart from the beginning: deletes the learner's own item rows, daily plans (their tabs cascade), study
     * sessions, check sets and study events — only {@code jpath_*} rows of this user — and archives the ACTIVE or
     * PAUSED path. Level rows, the slider log, settings, content reports, badges, reminders, credit points and the
     * team notebook are kept. A wrong confirmation text → JPATH_RESTART_CONFIRM_MISMATCH before anything is read.
     */
    public void restart(Long userId, String confirmText) {
        if (!JpathSettingsService.restartTextMatches(confirmText)) {
            throw new ApiException(ErrorCode.JPATH_RESTART_CONFIRM_MISMATCH);
        }
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .or(() -> paths.findForUpdate(userId, JpathPathStatus.PAUSED))
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        int deleted = checks.deleteByUserId(userId) + events.deleteByUserId(userId)
                + sessions.deleteByUserId(userId) + plans.deleteByUserId(userId) + userItems.deleteByUserId(userId);
        path.setStatus(JpathPathStatus.ARCHIVED);
        path.setArchivedAt(clock.now());
        paths.flush();
        log.info("JLPT path {} of user {} restarted: archived, {} progress rows deleted", path.getId(), userId,
                deleted);
    }

    /** Pauses the ACTIVE path while the learner changes the survey goal; progress stays. */
    public void pauseForGoalChange(Long userId) {
        JpathUserPath path = lockActive(userId);
        path.setStatus(JpathPathStatus.PAUSED);
        paths.flush();
    }

    /**
     * Makes the PAUSED path ACTIVE again (none → JPATH_NO_ACTIVE_PATH; an ACTIVE path inserted meanwhile →
     * JPATH_ACTIVE_PATH_EXISTS) and refreshes the priority mode of its open level, since the exam came closer.
     */
    public JpathPathUpdater.Change resumePaused(Long userId) {
        JpathUserPath path = paths.findForUpdate(userId, JpathPathStatus.PAUSED)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        JpathUserLevel level = userLevels.findForUpdate(path.getId(), path.getOpenLevel()).orElseThrow(() -> {
            log.error("Paused JLPT path {} of user {} has no row for its open level {}", path.getId(), userId,
                    path.getOpenLevel());
            return new ApiException(ErrorCode.INTERNAL_ERROR);
        });
        path.setStatus(JpathPathStatus.ACTIVE);
        try {
            paths.flush();
        } catch (DataIntegrityViolationException e) {
            if (JpathPathCreator.isActivePathKey(e)) {
                throw new ApiException(ErrorCode.JPATH_ACTIVE_PATH_EXISTS);
            }
            throw e;
        }
        JpathPathViewAssembler.Pacing pacing = views.pacing(path, level);
        level.setPriorityMode(pacing.pace().skippedLowPriority() > 0);
        userLevels.flush();
        return new JpathPathUpdater.Change(path, level, pacing, null);
    }

    private JpathUserPath lockActive(Long userId) {
        return paths.findActiveForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }
}
