package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.config.JpathProperties;
import com.jlas.jlptpath.entity.JpathLevel;
import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.repository.JpathGrammarRepository;
import com.jlas.jlptpath.repository.JpathKanjiRepository;
import com.jlas.jlptpath.repository.JpathLevelRepository;
import com.jlas.jlptpath.repository.JpathVocabularyRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathLevelInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Level catalogue read from jpath_level, with the learner-visible content counts. */
@Service
@Transactional(readOnly = true)
public class JpathCatalogServiceImpl implements JpathCatalogService {

    /** N5 first; index + 1 is the sort order. */
    private static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");

    private final JpathLevelRepository levelRepository;
    private final JpathVocabularyRepository vocabularyRepository;
    private final JpathKanjiRepository kanjiRepository;
    private final JpathGrammarRepository grammarRepository;
    private final Set<String> forceReleased;
    private final Set<JpathContentStatus> visibleStatuses;

    public JpathCatalogServiceImpl(JpathLevelRepository levelRepository,
                                   JpathVocabularyRepository vocabularyRepository,
                                   JpathKanjiRepository kanjiRepository,
                                   JpathGrammarRepository grammarRepository,
                                   JpathProperties properties) {
        this.levelRepository = levelRepository;
        this.vocabularyRepository = vocabularyRepository;
        this.kanjiRepository = kanjiRepository;
        this.grammarRepository = grammarRepository;
        this.forceReleased = Set.copyOf(properties.forceReleasedLevels());
        EnumSet<JpathContentStatus> statuses = EnumSet.of(JpathContentStatus.REVIEWED, JpathContentStatus.AI_CHECKED);
        if (properties.previewDraft()) {
            statuses.add(JpathContentStatus.DRAFT);
        }
        this.visibleStatuses = Collections.unmodifiableSet(statuses);
    }

    @Override
    public List<JpathLevelInfo> levels() {
        Map<String, JpathLevel> rows = rowsByLevel();
        List<JpathLevelInfo> infos = new ArrayList<>(LEVELS.size());
        for (String level : LEVELS) {
            infos.add(toInfo(level, rows.get(level)));
        }
        return List.copyOf(infos);
    }

    @Override
    public boolean isReleased(String level) {
        String normalized = normalize(level);
        return normalized != null && released(levelRepository.findById(normalized).orElse(null));
    }

    @Override
    public String highestReleasedLevel() {
        List<String> released = releasedLevels();
        return released.isEmpty() ? null : released.get(released.size() - 1);
    }

    @Override
    public String capToReleased(String level) {
        int ceiling = sortOrder(require(level));
        String best = null;
        for (String released : releasedLevels()) {
            if (sortOrder(released) <= ceiling) {
                best = released;
            }
        }
        if (best == null) {
            throw new ApiException(ErrorCode.JPATH_LEVEL_NOT_RELEASED);
        }
        return best;
    }

    @Override
    public Set<JpathContentStatus> visibleStatuses() {
        return visibleStatuses;
    }

    @Override
    public JpathLevelInfo info(String level) {
        String normalized = require(level);
        return toInfo(normalized, levelRepository.findById(normalized).orElse(null));
    }

    private Map<String, JpathLevel> rowsByLevel() {
        return levelRepository.findAll().stream().collect(Collectors.toMap(JpathLevel::getLevel, Function.identity()));
    }

    /** Released levels in N5→N1 order, without counting content. */
    private List<String> releasedLevels() {
        Map<String, JpathLevel> rows = rowsByLevel();
        return LEVELS.stream().filter(level -> released(rows.get(level))).toList();
    }

    /** A level without a catalogue row has no content, so it stays coming soon even when forced. */
    private boolean released(JpathLevel row) {
        return row != null && (row.isReleased() || forceReleased.contains(row.getLevel()));
    }

    private JpathLevelInfo toInfo(String level, JpathLevel row) {
        int sortOrder = sortOrder(level);
        if (row == null) {
            return new JpathLevelInfo(level, sortOrder, false, true, 0, 0, 0);
        }
        if (!released(row)) {
            return new JpathLevelInfo(level, sortOrder, false, true,
                    orZero(row.getPlannedVocab()), orZero(row.getPlannedKanji()), orZero(row.getPlannedGrammar()));
        }
        return new JpathLevelInfo(level, sortOrder, true, false,
                (int) vocabularyRepository.countByLevelAndStatusInAndReportHiddenFalse(level, visibleStatuses),
                (int) kanjiRepository.countByLevelAndStatusInAndReportHiddenFalse(level, visibleStatuses),
                (int) grammarRepository.countByLevelAndStatusInAndReportHiddenFalse(level, visibleStatuses));
    }

    private static int sortOrder(String level) {
        return LEVELS.indexOf(level) + 1;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** Trimmed, upper-cased N1..N5, or null. */
    private static String normalize(String level) {
        if (level == null) {
            return null;
        }
        String normalized = level.trim().toUpperCase(Locale.ROOT);
        return LEVELS.contains(normalized) ? normalized : null;
    }

    private static String require(String level) {
        String normalized = normalize(level);
        if (normalized == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Cấp JLPT chỉ nhận N5, N4, N3, N2 hoặc N1.");
        }
        return normalized;
    }
}
