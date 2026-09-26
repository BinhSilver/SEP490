package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.dto.LearningResourceCandidate;
import com.jlas.learning.dto.ReplanPreviewResponse;
import com.jlas.learning.dto.ReplanRequest;
import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.dto.SelectedLearningResource;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.UserKnowledgeWeakness;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LessonProgressRepository;
import com.jlas.learning.repository.UserKnowledgeWeaknessRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.LearningCatalogSource;
import com.jlas.learning.service.LearningScheduleService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0122] Replan lộ trình còn lại: loại bài đã hoàn thành, lên lịch lại phần tương lai, và
 * thêm ôn tập từ Sổ tay điểm yếu (REMEDIATE) hoặc bỏ mục ôn tập đã đạt mastery (ACCELERATE).
 *
 * <p>Tự chứa đầy đủ (không sửa {@link LearningServiceImpl}) để giữ các test hiện có
 * ({@code LearningPathApiTest}/{@code LearningPathRollbackTest}/{@code LearningPathConcurrencyTest})
 * vốn {@code @Import LearningServiceImpl} với bộ bean cố định.</p>
 *
 * <p>Proposal + confirm: {@link #preview} tính toán không ghi gì; {@link #apply} chỉ tạo version
 * mới khi learner đã xác nhận ({@code confirmed == true}) và có thay đổi. Lịch sử version dùng
 * status ACTIVE/SUPERSEDED + {@code version} có sẵn trên {@link UserLearningPath}.</p>
 */
@Service
@RequiredArgsConstructor
public class LearningReplanService {

    public static final String MODE_REMEDIATE = "REMEDIATE";
    public static final String MODE_ACCELERATE = "ACCELERATE";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final String STATUS_MASTERED = "MASTERED";
    private static final BigDecimal MASTERED_THRESHOLD = new BigDecimal("80");

    private final UserLearningPathRepository paths;
    private final UserLearningPathItemRepository items;
    private final LessonProgressRepository progress;
    private final UserKnowledgeWeaknessRepository weaknesses;
    private final LearningCatalogSource catalog;
    private final LearningScheduleService scheduler;
    private final LearningMapper mapper;
    private final EntityManager em;

    /** [JLAS-W0122] Xem trước replan (read-only) — không ghi gì, không đổi version. */
    @Transactional(readOnly = true)
    public ReplanPreviewResponse preview(Long userId, String mode) {
        String normalized = normalizeMode(mode);
        Plan plan = compute(userId, normalized);
        return new ReplanPreviewResponse(normalized, plan.changesApply(),
                plan.addedSummaries().size(), plan.removedSummaries().size(),
                plan.newTotalLessons(), plan.newTotalDays(), plan.newTotalMinutes(),
                plan.addedSummaries(), plan.removedSummaries(), plan.explanation());
    }

    /** [JLAS-W0122] Áp dụng replan sau khi learner xác nhận; tạo version path mới. */
    @Transactional
    public LearningPathResponse apply(Long userId, ReplanRequest request) {
        if (request == null || !request.confirmed()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Learner phải xác nhận replan trước khi áp dụng.");
        }
        String mode = normalizeMode(request.mode());
        Plan plan = compute(userId, mode);
        if (!plan.changesApply()) {
            return loadResponse(userId);
        }
        UserLearningPath next = createNextVersion(plan.activePath());
        persistItems(next, plan.newItems());
        return loadResponse(userId);
    }

    private Plan compute(Long userId, String mode) {
        UserLearningPath active = paths.findByUserIdAndStatus(userId, STATUS_ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy lộ trình đang hoạt động."));
        List<UserLearningPathItem> current = items.findWithLessonByLearningPathId(active.getId());
        Set<Long> completedLessonIds = progress.findCompletedLessonIds(userId);

        // Tách item "giữ nguyên" (warm-up + goal đã hoàn thành) khỏi phần tương lai cần lên lịch lại.
        List<UserLearningPathItem> verbatim = new ArrayList<>();
        List<UserLearningPathItem> future = new ArrayList<>();
        int completionMoved = 0;
        for (var item : current) {
            boolean warmUp = LearningPhase.WARM_UP.name().equals(item.getPhase());
            boolean completed = completedLessonIds.contains(item.getLesson().getId());
            if (warmUp || completed) {
                verbatim.add(item);
                if (!warmUp) completionMoved++;
            } else {
                future.add(item);
            }
        }

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        int masteredRemoved = 0;
        List<SelectedLearningResource> selections =
                future.stream().map(this::toSelection).collect(Collectors.toCollection(ArrayList::new));

        if (MODE_REMEDIATE.equals(mode)) {
            for (var weakness : weaknesses.findByUserIdAndStatusNot(userId, STATUS_MASTERED)) {
                Remediation remediation = resolveWeakness(weakness);
                if (remediation != null) {
                    selections.add(remediation.selection());
                    added.add(remediation.summary());
                }
            }
        } else { // ACCELERATE
            Set<String> mastered = masteredCanonicals(userId);
            List<SelectedLearningResource> kept = new ArrayList<>();
            for (var item : future) {
                if (isReviewItem(item) && mastered.contains(canonicalOf(item))) {
                    removed.add(itemTitle(item));
                    masteredRemoved++;
                } else {
                    kept.add(toSelection(item));
                }
            }
            selections = kept;
        }

        int offsetDays = verbatim.stream()
                .mapToInt(item -> item.getDayNumber() == null ? 0 : item.getDayNumber())
                .max().orElse(0);
        List<ScheduledLearningResource> scheduled = scheduler.schedule(selections, budget(active));

        List<PlanItem> ordered = new ArrayList<>();
        int idx = 1;
        for (var v : verbatim) {
            ordered.add(toPlanItem(v, idx++));
        }
        for (var s : scheduled) {
            ordered.add(new PlanItem(s.phase(), s.resource().lessonId(), s.resource().grammarPointId(),
                    s.resource().vocabularyEntryId(), s.reasonCode(), s.reasonText(),
                    s.matchedTagCodes(), s.estimatedMinutes(), s.dayNumber() + offsetDays, idx++,
                    s.resource().title()));
        }

        boolean changesApply = completionMoved > 0 || !added.isEmpty() || masteredRemoved > 0;
        int newTotalLessons = ordered.size();
        int newTotalDays = ordered.stream().mapToInt(PlanItem::dayNumber).max().orElse(0);
        int newTotalMinutes = ordered.stream().mapToInt(PlanItem::estimatedMinutes).sum();
        return new Plan(active, ordered, changesApply, added, removed,
                newTotalLessons, newTotalDays, newTotalMinutes,
                explanation(mode, added.size(), masteredRemoved, completionMoved));
    }

    private SelectedLearningResource toSelection(UserLearningPathItem item) {
        var gp = item.getGrammarPoint();
        var v = item.getVocabularyEntry();
        Set<String> tags = new LinkedHashSet<>(mapper.readMatchedTags(item.getMatchedTagCodes()));
        var candidate = new LearningResourceCandidate(item.getLesson().getId(), itemTitle(item),
                null, null, tags, null,
                item.getEstimatedMinutes() == null ? 0 : item.getEstimatedMinutes(),
                null, null, gp == null ? null : gp.getId(), v == null ? null : v.getId());
        return new SelectedLearningResource(candidate, LearningPhase.valueOf(item.getPhase()),
                item.getReasonCode(), item.getReason(), tags);
    }

    private PlanItem toPlanItem(UserLearningPathItem item, int orderIndex) {
        return new PlanItem(LearningPhase.valueOf(item.getPhase()), item.getLesson().getId(),
                item.getGrammarPoint() == null ? null : item.getGrammarPoint().getId(),
                item.getVocabularyEntry() == null ? null : item.getVocabularyEntry().getId(),
                item.getReasonCode(), item.getReason(),
                new LinkedHashSet<>(mapper.readMatchedTags(item.getMatchedTagCodes())),
                item.getEstimatedMinutes() == null ? 0 : item.getEstimatedMinutes(),
                item.getDayNumber(), orderIndex, itemTitle(item));
    }

    /** Resolve một điểm yếu đang mở (chưa MASTERED) thành một tài nguyên ôn tập có thể đưa vào lịch. */
    private Remediation resolveWeakness(UserKnowledgeWeakness w) {
        String skill = w.getSkill();
        if (WeaknessEvidence.SKILL_GRAMMAR.equals(skill)) {
            Long grammarPointId = parseGrammarPointId(w.getCanonicalItem());
            if (grammarPointId == null) return null;
            return catalog.findGrammarPoint(grammarPointId)
                    .map(c -> remediation(c, "REMEDIATION_GRAMMAR_POINT", "Ôn ngữ pháp: " + c.title()))
                    .orElse(null);
        }
        if (WeaknessEvidence.SKILL_KANJI.equals(skill) || WeaknessEvidence.SKILL_VOCABULARY.equals(skill)) {
            String lexeme = lexemeOf(w.getCanonicalItem());
            if (lexeme == null) return null;
            boolean kanji = WeaknessEvidence.SKILL_KANJI.equals(skill);
            String reasonCode = kanji ? "REMEDIATION_KANJI_ITEM" : "REMEDIATION_VOCAB_ITEM";
            String label = kanji ? "Ôn Kanji: " : "Ôn từ vựng: ";
            return catalog.findVocabularyItemByExpression(lexeme)
                    .map(c -> remediation(c, reasonCode, label + c.title()))
                    .orElse(null);
        }
        if (WeaknessEvidence.SKILL_READING.equals(skill) || WeaknessEvidence.SKILL_LISTENING.equals(skill)) {
            boolean reading = WeaknessEvidence.SKILL_READING.equals(skill);
            String reasonCode = reading ? "REMEDIATION_READING_ITEM" : "REMEDIATION_LISTENING_ITEM";
            String label = reading ? "Luyện đọc: " : "Luyện nghe: ";
            return catalog.findComprehensionLesson(skill, w.getJlptLevel())
                    .map(c -> remediation(c, reasonCode, label + c.title()))
                    .orElse(null);
        }
        return null;
    }

    private Remediation remediation(LearningResourceCandidate candidate, String reasonCode, String summary) {
        return new Remediation(
                new SelectedLearningResource(candidate, LearningPhase.GOAL_LEARNING, reasonCode, summary,
                        candidate.knowledgeTagCodes()),
                summary);
    }

    private Set<String> masteredCanonicals(Long userId) {
        return weaknesses.findByUserId(userId).stream()
                .filter(this::isMastered)
                .map(UserKnowledgeWeakness::getCanonicalItem)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean isMastered(UserKnowledgeWeakness w) {
        return STATUS_MASTERED.equals(w.getStatus())
                || (w.getMasteryScore() != null && w.getMasteryScore().compareTo(MASTERED_THRESHOLD) >= 0);
    }

    private boolean isReviewItem(UserLearningPathItem item) {
        String reason = item.getReasonCode();
        return reason != null && (reason.startsWith("WEAK_") || reason.startsWith("REMEDIATION_"));
    }

    private String canonicalOf(UserLearningPathItem item) {
        var gp = item.getGrammarPoint();
        if (gp != null) return "GP:" + gp.getId();
        var v = item.getVocabularyEntry();
        if (v != null) {
            boolean kanji = item.getReasonCode() != null && item.getReasonCode().contains("KANJI");
            return (kanji ? "KANJI:" : "VOCAB:") + v.getExpression();
        }
        return null;
    }

    private String itemTitle(UserLearningPathItem item) {
        var gp = item.getGrammarPoint();
        if (gp != null) return gp.getPattern();
        var v = item.getVocabularyEntry();
        if (v != null) return v.getExpression();
        return item.getLesson().getTitle();
    }

    private static Long parseGrammarPointId(String canonical) {
        if (canonical == null || !canonical.startsWith("GP:")) return null;
        try {
            return Long.valueOf(canonical.substring(3));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String lexemeOf(String canonical) {
        if (canonical == null) return null;
        int colon = canonical.indexOf(':');
        if (colon < 0 || colon == canonical.length() - 1) return null;
        return canonical.substring(colon + 1);
    }

    private UserLearningPath createNextVersion(UserLearningPath active) {
        active.setStatus(STATUS_SUPERSEDED);
        paths.save(active);
        int nextVersion = paths.findMaxVersionByUserId(active.getUser().getId()) + 1;
        UserLearningPath next = UserLearningPath.builder()
                .user(active.getUser())
                .title(active.getTitle())
                .goal(active.getGoal())
                .targetLevel(active.getTargetLevel())
                .currentLevel(active.getCurrentLevel())
                .surveyId(active.getSurveyId())
                .assessmentAttemptId(active.getAssessmentAttemptId())
                .assessmentRevision(active.getAssessmentRevision())
                .policyVersion(active.getPolicyVersion())
                .minutesPerDay(active.getMinutesPerDay())
                .coverageGapsJson(active.getCoverageGapsJson())
                .gatePassedAt(active.getGatePassedAt())
                .warmUpFlashcardSetId(active.getWarmUpFlashcardSetId())
                .status(STATUS_ACTIVE)
                .version(nextVersion)
                .build();
        return paths.save(next);
    }

    private void persistItems(UserLearningPath next, List<PlanItem> ordered) {
        var pathRef = em.getReference(UserLearningPath.class, next.getId());
        var entities = ordered.stream().map(p -> UserLearningPathItem.builder()
                .learningPath(pathRef)
                .lesson(em.getReference(Lesson.class, p.lessonId()))
                .grammarPoint(p.grammarPointId() == null ? null : em.getReference(GrammarPoint.class, p.grammarPointId()))
                .vocabularyEntry(p.vocabularyEntryId() == null ? null : em.getReference(VocabularyEntry.class, p.vocabularyEntryId()))
                .orderIndex(p.orderIndex())
                .reason(p.reason())
                .phase(p.phase().name())
                .reasonCode(p.reasonCode())
                .dayNumber(p.dayNumber())
                .estimatedMinutes(p.estimatedMinutes())
                .matchedTagCodes(mapper.writeMatchedTags(p.matchedTags()))
                .build()).toList();
        items.saveAll(entities);
    }

    private LearningPathResponse loadResponse(Long userId) {
        UserLearningPath active = paths.findByUserIdAndStatus(userId, STATUS_ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy lộ trình đang hoạt động."));
        return mapper.toResponse(active, items.findWithLessonByLearningPathId(active.getId()),
                progress.findCompletedLessonIds(userId));
    }

    private static int budget(UserLearningPath active) {
        return active.getMinutesPerDay() == null ? 30 : active.getMinutesPerDay();
    }

    private static String normalizeMode(String mode) {
        if (MODE_REMEDIATE.equalsIgnoreCase(mode)) return MODE_REMEDIATE;
        if (MODE_ACCELERATE.equalsIgnoreCase(mode)) return MODE_ACCELERATE;
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mode replan không hợp lệ (REMEDIATE/ACCELERATE).");
    }

    private static String explanation(String mode, int added, int removed, int completionMoved) {
        if (MODE_ACCELERATE.equals(mode)) {
            return removed == 0 ? "Không có mục ôn tập nào đạt mastery để tăng tốc lộ trình."
                    : "Bỏ " + removed + " mục ôn tập đã đạt mastery, rút ngắn lộ trình còn lại.";
        }
        if (added == 0 && completionMoved == 0) {
            return "Không có điểm yếu nào cần ôn tập thêm.";
        }
        return "Bổ sung " + added + " mục ôn tập từ Sổ tay điểm yếu và lên lịch lại lộ trình.";
    }

    private record Remediation(SelectedLearningResource selection, String summary) { }

    private record PlanItem(LearningPhase phase, Long lessonId, Long grammarPointId, Long vocabularyEntryId,
            String reasonCode, String reason, Set<String> matchedTags, int estimatedMinutes,
            Integer dayNumber, int orderIndex, String title) { }

    private record Plan(UserLearningPath activePath, List<PlanItem> newItems, boolean changesApply,
            List<String> addedSummaries, List<String> removedSummaries,
            int newTotalLessons, int newTotalDays, int newTotalMinutes, String explanation) { }
}
