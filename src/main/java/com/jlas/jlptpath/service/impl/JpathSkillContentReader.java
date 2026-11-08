package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.entity.JpathSkillLesson;
import com.jlas.jlptpath.entity.JpathSkillQuestion;
import com.jlas.jlptpath.entity.JpathSkillSet;
import com.jlas.jlptpath.entity.JpathSkillType;
import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.repository.JpathSkillLessonRepository;
import com.jlas.jlptpath.repository.JpathSkillQuestionRepository;
import com.jlas.jlptpath.repository.JpathSkillSetRepository;
import com.jlas.jlptpath.repository.JpathSkillTypeRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] The one reader of skill content (Đọc · Nghe) for learner-facing code. Visibility rule, as
 * {@link JpathContentReader}: status in {@code catalog.visibleStatuses()}, not report-hidden, level released — and a
 * listening set needs its audio, a set of a type that needs a picture needs its text description, and a set needs at
 * least one visible question. A type is available when its lesson is visible and it has a visible set. Anything else
 * reads as absent; an unreleased level is empty. Types come in exam order: reading before listening, then sort order.
 */
@Component
@Transactional(readOnly = true)
public class JpathSkillContentReader {

    /** Exam order: reading (with the language-knowledge section) before listening, then the type's sort order. */
    public static final Comparator<JpathSkillType> EXAM_ORDER = Comparator
            .comparing((JpathSkillType t) -> t.getSkill().ordinal())
            .thenComparing(JpathSkillType::getSortOrder)
            .thenComparing(JpathSkillType::getCode);

    /** A visible set and the number of its visible questions (always ≥ 1). */
    public record VisibleSet(JpathSkillSet set, int questions) {
    }

    private final JpathCatalogService catalog;
    private final JpathSkillTypeRepository types;
    private final JpathSkillLessonRepository lessons;
    private final JpathSkillSetRepository sets;
    private final JpathSkillQuestionRepository questions;

    public JpathSkillContentReader(JpathCatalogService catalog, JpathSkillTypeRepository types,
            JpathSkillLessonRepository lessons, JpathSkillSetRepository sets, JpathSkillQuestionRepository questions) {
        this.catalog = catalog;
        this.types = types;
        this.lessons = lessons;
        this.sets = sets;
        this.questions = questions;
    }

    /** Question types of {@code level} in exam order; empty when the level is not released. */
    public List<JpathSkillType> types(String level) {
        if (level == null || !catalog.isReleased(level)) {
            return List.of();
        }
        return types.findByLevel(level.trim().toUpperCase(Locale.ROOT)).stream().sorted(EXAM_ORDER).toList();
    }

    /** Types of {@code level} for one skill, in exam order. */
    public List<JpathSkillType> types(String level, JpathSkill skill) {
        return types(level).stream().filter(t -> t.getSkill() == skill).toList();
    }

    /** The type with this code when its level is released. */
    public Optional<JpathSkillType> type(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return types.findById(code.trim()).filter(t -> catalog.isReleased(t.getLevel()));
    }

    /** The visible lesson of a type (its level released). */
    public Optional<JpathSkillLesson> lesson(String typeCode) {
        return type(typeCode).flatMap(t -> lessons.findByTypeCodeInAndStatusInAndReportHiddenFalse(
                List.of(t.getCode()), statuses()).stream().findFirst());
    }

    /** Codes of the types among {@code typeList} whose lesson is visible. */
    public Set<String> typesWithLesson(Collection<JpathSkillType> typeList) {
        List<String> codes = released(typeList).stream().map(JpathSkillType::getCode).toList();
        if (codes.isEmpty()) {
            return Set.of();
        }
        return lessons.findByTypeCodeInAndStatusInAndReportHiddenFalse(codes, statuses()).stream()
                .map(JpathSkillLesson::getTypeCode).collect(Collectors.toSet());
    }

    /** Visible sets of one type by sort order. */
    public List<VisibleSet> visibleSets(JpathSkillType type) {
        return visibleSets(List.of(type)).getOrDefault(type.getCode(), List.of());
    }

    /** Visible sets by type code (types without a visible set are absent), each list by sort order. */
    public Map<String, List<VisibleSet>> visibleSets(Collection<JpathSkillType> typeList) {
        List<JpathSkillType> open = released(typeList);
        if (open.isEmpty()) {
            return Map.of();
        }
        Map<String, JpathSkillType> byCode = open.stream()
                .collect(Collectors.toMap(JpathSkillType::getCode, Function.identity(), (a, b) -> a));
        List<JpathSkillSet> candidates = sets.findByTypeCodeInAndStatusInAndReportHiddenFalseOrderBySortOrderAscCodeAsc(
                byCode.keySet(), statuses()).stream().filter(s -> shown(s, byCode.get(s.getTypeCode()))).toList();
        Map<String, Integer> questionCounts = questionCounts(candidates.stream().map(JpathSkillSet::getCode).toList());
        Map<String, List<VisibleSet>> out = new LinkedHashMap<>();
        for (JpathSkillSet s : candidates) {
            int n = questionCounts.getOrDefault(s.getCode(), 0);
            if (n > 0) {
                out.computeIfAbsent(s.getTypeCode(), k -> new java.util.ArrayList<>()).add(new VisibleSet(s, n));
            }
        }
        return out;
    }

    /** Visible questions of these sets, by set code then sort order (sets themselves are not re-checked). */
    public List<JpathSkillQuestion> visibleQuestions(Collection<String> setCodes) {
        if (setCodes == null || setCodes.isEmpty()) {
            return List.of();
        }
        return questions.findBySetCodeInAndStatusInAndReportHiddenFalseOrderBySetCodeAscSortOrderAsc(setCodes,
                statuses());
    }

    /** A type is available when its lesson is visible and it has at least one visible set. */
    public static boolean available(JpathSkillType type, Set<String> withLesson,
            Map<String, List<VisibleSet>> setsByType) {
        return withLesson.contains(type.getCode()) && !setsByType.getOrDefault(type.getCode(), List.of()).isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private Map<String, Integer> questionCounts(List<String> setCodes) {
        Map<String, Integer> counts = new HashMap<>();
        for (JpathSkillQuestion q : visibleQuestions(setCodes)) {
            counts.merge(q.getSetCode(), 1, Integer::sum);
        }
        return counts;
    }

    private List<JpathSkillType> released(Collection<JpathSkillType> typeList) {
        if (typeList == null) {
            return List.of();
        }
        return typeList.stream().filter(t -> t != null && catalog.isReleased(t.getLevel())).toList();
    }

    /** Listening needs audio; a type that needs a picture needs the text description of the scene. */
    private static boolean shown(JpathSkillSet s, JpathSkillType type) {
        if (type == null) {
            return false;
        }
        if (s.getSkill() == JpathSkill.LISTENING && !hasText(s.getAudioPath())) {
            return false;
        }
        return !type.isNeedsImage() || hasText(s.getImageDescriptionVi());
    }

    private Set<JpathContentStatus> statuses() {
        return catalog.visibleStatuses();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
