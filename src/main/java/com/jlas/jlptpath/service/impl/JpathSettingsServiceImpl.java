package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.config.JpathProperties;
import com.jlas.jlptpath.dto.JpathDisplaySettings;
import com.jlas.jlptpath.dto.JpathPathView;
import com.jlas.jlptpath.dto.JpathSettingsUpdateRequest;
import com.jlas.jlptpath.dto.JpathSettingsView;
import com.jlas.jlptpath.dto.JpathSliderAdjustmentView;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.entity.JpathSliderAdjustment;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathUserSettings;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathSliderAdjustmentRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.repository.JpathUserSettingsRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathSettingsService;
import com.jlas.jlptpath.util.JpathDays;
import com.jlas.jlptpath.util.JpathPacingEngine;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Settings of the JLPT path. Writes are done by {@link JpathSettingsWriter}, lower level and restart by
 * {@link JpathPathChanger}; the pace, slider panel and schedule rules come from {@link JpathPathViewAssembler} and
 * {@link JpathPathRules}.
 */
@Service
public class JpathSettingsServiceImpl implements JpathSettingsService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository userLevels;
    private final JpathSliderAdjustmentRepository adjustments;
    private final JpathUserSettingsRepository settings;
    private final JpathCatalogService catalog;
    private final JpathPathViewAssembler views;
    private final JpathSettingsWriter writer;
    private final JpathProperties props;
    private final JpathPathChanger changer;

    public JpathSettingsServiceImpl(JpathUserPathRepository paths, JpathUserLevelRepository userLevels,
            JpathSliderAdjustmentRepository adjustments, JpathUserSettingsRepository settings,
            JpathCatalogService catalog, JpathPathViewAssembler views, JpathSettingsWriter writer,
            JpathProperties props, JpathPathChanger changer) {
        this.paths = paths;
        this.userLevels = userLevels;
        this.adjustments = adjustments;
        this.settings = settings;
        this.catalog = catalog;
        this.views = views;
        this.writer = writer;
        this.props = props;
        this.changer = changer;
    }

    @Override
    @Transactional(readOnly = true)
    public JpathSettingsView get(Long userId) {
        JpathUserSettings row = settings.findById(userId).orElse(null);
        Optional<JpathUserPath> path = findActive(userId);
        if (path.isEmpty()) {
            return view(null, null, null, null, row);
        }
        JpathUserLevel level = openLevel(path.get());
        return view(path.get(), level, views.pacing(path.get(), level), null, row);
    }

    @Override
    @Transactional
    public JpathSettingsView update(Long userId, JpathSettingsUpdateRequest request) {
        JpathSettingsWriter.Result result = writer.update(userId, request);
        JpathUserSettings row = result.settings() != null ? result.settings()
                : settings.findById(userId).orElse(null);
        if (result.path() != null) {
            return view(result.path(), result.level(), result.pacing(), result.appliesFromTomorrow(), row);
        }
        // Display only: the path was not written, a plain read is enough.
        Optional<JpathUserPath> path = findActive(userId);
        if (path.isEmpty()) {
            return view(null, null, null, null, row);
        }
        JpathUserLevel level = openLevel(path.get());
        return view(path.get(), level, views.pacing(path.get(), level), null, row);
    }

    @Override
    @Transactional(readOnly = true)
    public JpathDisplaySettings display(Long userId) {
        String openLevel = findActive(userId).map(JpathUserPath::getOpenLevel).orElse(null);
        return JpathDisplaySettings.from(settings.findById(userId).orElse(null), openLevel);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JpathSliderAdjustmentView> sliderHistory(Long userId) {
        return findActive(userId)
                .map(path -> adjustments.findByPathIdOrderByIdDesc(path.getId()).stream()
                        .map(JpathSettingsServiceImpl::historyRow).toList())
                .orElse(List.of());
    }

    @Override
    @Transactional
    public JpathPathView lowerLevel(Long userId, String level) {
        JpathPathUpdater.Change c = changer.lowerLevel(userId, level);
        return views.view(c.path(), c.level(), c.pacing(), c.appliesFromTomorrow());
    }

    @Override
    @Transactional
    public void restart(Long userId, String confirmText) {
        changer.restart(userId, confirmText);
    }

    private JpathSettingsView view(JpathUserPath path, JpathUserLevel level, JpathPathViewAssembler.Pacing p,
            Set<JpathTab> appliesFromTomorrow, JpathUserSettings row) {
        boolean web = row == null || row.isReminderWeb();
        boolean email = row != null && row.isReminderEmail();
        LocalTime time = row == null ? props.reminder().defaultLocalTime() : row.getReminderTime();
        boolean emailAvailable = props.reminder().emailEnabled();
        if (path == null) {
            return new JpathSettingsView(null, List.of(), null, null, 0, JpathPacingEngine.MINUTE_OPTIONS, List.of(),
                    List.of(), null, true, null, null, null, JpathDisplaySettings.from(row, null), web, email,
                    emailAvailable, time.format(HH_MM), null, null, List.of());
        }
        String open = path.getOpenLevel();
        DayOfWeek stored = JpathPathViewAssembler.storedThirdDay(path);
        boolean thirdAuto = stored == null;
        DayOfWeek third = thirdAuto ? JpathCalendarService.autoThirdDay(p.skillDays(), p.restDays()) : stored;
        return new JpathSettingsView(path.getTargetLevel(), JpathPathRules.targetOptions(catalog, open),
                JpathPaceService.usableExamDate(path.getExamDate(), p.today()), p.pace(), path.getDailyMinutes(),
                JpathPacingEngine.MINUTE_OPTIONS, JpathDays.codes(p.skillDays()), JpathDays.codes(p.restDays()),
                third == null ? null : JpathDays.format(List.of(third)), thirdAuto, level.getStage(), p.mode(),
                JpathSettingsWriter.suggestion(path), JpathDisplaySettings.from(row, open), web, email,
                emailAvailable, time.format(HH_MM), views.sliderView(path, level, p, appliesFromTomorrow), open,
                JpathPathRules.lowerLevels(catalog, open));
    }

    private Optional<JpathUserPath> findActive(Long userId) {
        return paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE);
    }

    private JpathUserLevel openLevel(JpathUserPath path) {
        return userLevels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel()))
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    private static JpathSliderAdjustmentView historyRow(JpathSliderAdjustment a) {
        JpathSliderValues before = a.getVocabBefore() == null ? null
                : new JpathSliderValues(a.getVocabBefore(), a.getKanjiBefore(), a.getGrammarBefore());
        return new JpathSliderAdjustmentView(a.getLevel(), a.getKind(), a.isCounted(), before,
                new JpathSliderValues(a.getVocabAfter(), a.getKanjiAfter(), a.getGrammarAfter()), a.getDailyMinutes(),
                a.getCreatedAt());
    }
}
