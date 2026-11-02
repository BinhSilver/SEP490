package com.jlas.jlptpath.service.impl;

import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathEntryPreferenceRequest;
import com.jlas.jlptpath.dto.JpathEntryStatusView;
import com.jlas.jlptpath.entity.JpathEntryPreference;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathKanaChoice;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.repository.JpathEntryPreferenceRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.util.JpathDays;
import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Entry status, entry answers and the learner's active path. */
@Service
public class JpathPathServiceImpl implements JpathPathService {

    private static final List<String> DEFAULT_SKILL_DAYS = List.of("WED", "SAT");
    private static final String KANA_CHOICE_REQUIRED = "Hãy chọn bạn còn nhớ hay muốn ôn bảng chữ cái.";

    private final JpathEntryPreferenceRepository preferences;
    private final JpathUserPathRepository paths;
    private final UserLearningSurveyRepository surveys;
    private final JpathSmartTestReader smartTests;

    public JpathPathServiceImpl(JpathEntryPreferenceRepository preferences, JpathUserPathRepository paths,
            UserLearningSurveyRepository surveys, JpathSmartTestReader smartTests) {
        this.preferences = preferences;
        this.paths = paths;
        this.surveys = surveys;
        this.smartTests = smartTests;
    }

    @Override
    @Transactional(readOnly = true)
    public JpathEntryStatusView entryStatus(Long userId) {
        Optional<UserLearningSurvey> survey = surveys.findById(userId);
        String goal = survey.map(UserLearningSurvey::getGoal)
                .map(g -> g.trim().toUpperCase(Locale.ROOT)).orElse(null);
        Optional<JpathEntryPreference> preference = preferences.findById(userId);
        JpathEntryStatusView.SmartTest smartTest = smartTests.latestAttempt(userId)
                .map(a -> new JpathEntryStatusView.SmartTest(a.getId(), a.getStatus())).orElse(null);
        return new JpathEntryStatusView(
                survey.isPresent(),
                goal,
                preference.map(JpathEntryPreference::isStudiedBefore).orElse(null),
                preference.map(p -> p.getKanaChoice().name()).orElse(null),
                preference.isPresent(),
                preference.map(p -> JpathDays.codes(JpathDays.parse(p.getSkillDays()))).orElse(DEFAULT_SKILL_DAYS),
                preference.map(p -> JpathDays.codes(JpathDays.parse(p.getRestDays()))).orElse(List.of()),
                smartTest,
                pathStatus(userId));
    }

    @Override
    @Transactional
    public JpathEntryStatusView saveEntryPreference(Long userId, JpathEntryPreferenceRequest request) {
        boolean studied = Boolean.TRUE.equals(request.studiedBefore());
        JpathKanaChoice kana = studied ? studiedKanaChoice(request.kanaChoice()) : JpathKanaChoice.NONE;
        EnumSet<DayOfWeek> skillDays = JpathDays.parseCodes(request.skillDays());
        EnumSet<DayOfWeek> restDays = JpathDays.parseCodes(request.restDays());
        JpathDays.validate(skillDays, restDays);

        JpathEntryPreference preference = preferences.findById(userId)
                .orElseGet(() -> JpathEntryPreference.builder().userId(userId).build());
        preference.setStudiedBefore(studied);
        preference.setKanaChoice(kana);
        preference.setSkillDays(JpathDays.format(skillDays));
        preference.setRestDays(JpathDays.format(restDays));
        preferences.saveAndFlush(preference);
        return entryStatus(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JpathUserPath> findActive(Long userId) {
        return paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public JpathUserPath requireActive(Long userId) {
        return findActive(userId).orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    private JpathPathStatus pathStatus(Long userId) {
        if (paths.existsByUserIdAndStatus(userId, JpathPathStatus.ACTIVE)) {
            return JpathPathStatus.ACTIVE;
        }
        return paths.existsByUserIdAndStatus(userId, JpathPathStatus.PAUSED) ? JpathPathStatus.PAUSED : null;
    }

    private static JpathKanaChoice studiedKanaChoice(String value) {
        if ("REMEMBER".equals(value)) {
            return JpathKanaChoice.REMEMBER;
        }
        if ("REVIEW".equals(value)) {
            return JpathKanaChoice.REVIEW;
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, KANA_CHOICE_REQUIRED);
    }
}
