package com.jlas.learning.service.impl;

import com.jlas.learning.dto.*;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.service.LearningCatalogSource;
import com.jlas.learning.service.LearningResourceSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LearningResourceSelectionServiceImpl implements LearningResourceSelectionService {
    private static final List<String> LEVELS = List.of("BELOW_N5", "N5", "N4", "N3", "N2", "N1");
    /** [JLAS-W0108] Cấp kết luận cho người chưa từng học (bắt đầu từ bảng chữ cái). */
    private static final String BELOW_N5 = "BELOW_N5";
    /** [JLAS-W0108] Reason code cho warm-up nền tảng bảng chữ cái (from-zero). */
    private static final String REASON_KANA_FOUNDATION = "KANA_FOUNDATION";
    private final LearningCatalogSource catalog;
    private final WeakTagPolicy policy;
    /** [JLAS-W0108] P3 — D1 (gợi ý xác minh nền tảng) gác sau feature policy CHƯA kích hoạt đến khi chốt.
     *  Bật cờ này mới sinh gap {@code FOUNDATION_VERIFICATION_SUGGESTED}; KHÔNG tự giao 70–100% sách. */
    @Value("${jlas.learning.selection.full-review-enabled:false}")
    private boolean fullReviewEnabled;

    @Override
    public LearningSelectionResult select(Long userId, LearningProfileSnapshot profile, LearningCatalogScope scope) {
        validate(userId, profile, scope);
        // Overall Smart Test band determines whether the survey level is relearned, mistakes are
        // warmed up, or only the target is studied. Legacy profiles retain their former range.
        int goalMin = rank(LearningStartPolicy.firstGoalLevel(profile));
        var goals = candidates(scope.goalTextbookIds(), goalMin, rank(profile.targetLevel()));
        if (goals.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No published lessons match the selected JLPT curriculum and levels.");
        }
        var weaknesses = profile.weaknesses() == null ? List.<WeaknessEvidence>of() : profile.weaknesses();
        var skillScores = profile.skillScores() == null ? List.<SkillScore>of() : profile.skillScores();
        var resources = new ArrayList<SelectedLearningResource>();
        var selectedIds = new HashSet<Long>();
        var gaps = new ArrayList<CoverageGap>();

        // [JLAS-W0108] From-zero: warm-up = bảng chữ cái (không có điểm yếu/điểm skill để ôn),
        // rồi vào thẳng GOAL N5..target.
        if (BELOW_N5.equals(profile.assessedLevel())) {
            addKanaFoundationWarmUps(scope.warmUpTextbookIds(), selectedIds, resources);
            addGoals(goals, selectedIds, resources, profile);
            return new LearningSelectionResult(resources, gaps, policy.version(), List.of());
        }

        if (!LearningStartPolicy.needsMistakeWarmUp(profile)) {
            addGoals(goals, selectedIds, resources, profile);
            return new LearningSelectionResult(resources, gaps, policy.version(), List.of());
        }

        // [JLAS-W0108] P3 — KHÔNG còn bỏ warm-up toàn cục theo Σcorrect/Σassessed > 60%, và KHÔNG tự giao
        // "ôn toàn bộ" theo một skill thấp (D1). Duyệt từng skill: từ/Kanji sai luôn giữ dù skill cao;
        // đọc/nghe theo bảng % đúng (§6.1); Grammar được review theo từng câu sai, còn nhãn yếu
        // vẫn tuân thủ WeakTagPolicy.
        suggestFoundationVerification(skillScores, gaps);

        var weak = weaknesses.stream()
                .filter(policy::isWeak)
                .sorted(this::compareWeakness)
                .toList();
        // REVIEW selection is broader than weakness classification: one wrong grammar answer
        // deserves focused practice, but must not be recorded as a concluded weak point unless
        // the configured WeakTagPolicy threshold is met.
        var reviewable = weaknesses.stream()
                .filter(evidence -> policy.isWeak(evidence) || hasWrongGrammarAnswer(evidence))
                .sorted(this::compareWeakness)
                .toList();
        weaknesses.stream().filter(e -> !policy.hasEnoughEvidence(e))
                .sorted(Comparator.comparing(WeaknessEvidence::sortKey))
                .forEach(e -> gaps.add(new CoverageGap(e.sortKey(), "INSUFFICIENT_ASSESSMENT")));
        if (weaknesses.isEmpty()) {
            gaps.add(new CoverageGap("ASSESSMENT", "NO_ASSESSMENT_EVIDENCE"));
        }

        // (1) [JLAS-W0108] P3 — Đọc/nghe warm-up theo bảng % đúng (p-table), riêng từng skill, đủ mẫu.
        addComprehensionWarmUps(skillScores, profile, selectedIds, resources, gaps);

        // (2) [JLAS-W0108] Ôn đúng item/điểm sai: grammar sai được review dù chưa đủ ngưỡng yếu;
        // từ/Kanji giữ theo item; phân loại weakEvidence vẫn theo policy.
        if (!reviewable.isEmpty()) {
            var warmUp = candidates(scope.warmUpTextbookIds(), 0, rank(profile.assessedLevel()));
            var uncovered = new LinkedHashSet<String>();
            reviewable.forEach(e -> {
                if (e.tagCode() != null) uncovered.add(e.tagCode());
            });
            for (var e : reviewable) {
                // [JLAS-W0108] Đọc/nghe đã xử lý riêng ở p-table (theo điểm skill), không qua nhánh
                // tag/grammar/vocab bên dưới.
                if (WeaknessEvidence.SKILL_READING.equals(e.skill())
                        || WeaknessEvidence.SKILL_LISTENING.equals(e.skill())) {
                    continue;
                }
                // [JLAS-W0108] Lát 2.5b — item sai ở mức CAO HƠN trình độ kết luận (estimated) là
                // "chưa từng học" -> KHÔNG warm-up (phủ bởi GOAL_LEARNING), và không báo gap
                // (không phải thiếu học liệu). Warm-up chỉ nhận item <= trình độ kết luận.
                if (e.jlptLevel() != null && rank(e.jlptLevel()) > rank(profile.assessedLevel())) {
                    continue;
                }
                // [JLAS-W0108] Lát 2.5 — GRAMMAR: review đúng grammar point đã sai (không phải cả bài).
                // Nếu point chưa có trong catalog thì fallback xuống khớp theo tag lesson bên dưới.
                if (WeaknessEvidence.SKILL_GRAMMAR.equals(e.skill()) && e.grammarPointId() != null) {
                    var point = catalog.findGrammarPoint(e.grammarPointId());
                    if (point.isPresent()) {
                        // [JLAS-W0108] Warm-up ôn đúng mẫu ngữ pháp đã sai; KHÔNG đánh dấu cả bài gốc là
                        // "đã chọn" để bài đó vẫn xuất hiện trọn vẹn trong giai đoạn GOAL (full giáo trình).
                        addGrammarWarmUp(point.get(), resources);
                        continue;
                    }
                }
                // [JLAS-W0108] Lát 2.5e — KANJI/VOCAB: warm-up theo đúng từ đã sai (không phải cả bài).
                // Nếu từ chưa có bài dạy trong catalog (từ bổ sung DICTIONARY) thì báo gap rõ ràng.
                if (WeaknessEvidence.SKILL_KANJI.equals(e.skill())
                        || WeaknessEvidence.SKILL_VOCABULARY.equals(e.skill())) {
                    String lexeme = e.vocabularyEntryId() == null ? lexemeOf(e) : null;
                    if (e.vocabularyEntryId() == null && lexeme == null) {
                        gaps.add(new CoverageGap(e.sortKey(), "CONTENT_MAPPING_PENDING"));
                        continue;
                    }
                    var item = e.vocabularyEntryId() != null
                            ? catalog.findVocabularyItem(e.vocabularyEntryId())
                            : catalog.findVocabularyItemByExpression(lexeme);
                    if (item.isPresent()) {
                        // [JLAS-W0108] Warm-up ôn đúng từ đã sai; bài dạy từ đó vẫn vào trọn vẹn GOAL.
                        addItemWarmUp(item.get(), e.skill(), resources);
                        continue;
                    }
                    gaps.add(new CoverageGap(e.sortKey(), "MISSING_WARM_UP_RESOURCE"));
                    continue;
                }
                // [JLAS-W0108] Kanji/Vocab thiếu anchor (vocabularyEntryId null) cần content-side mapping
                // (content_item -> topic/outcome) — đầu việc data riêng, chưa nạp -> báo gap rõ ràng.
                if (e.tagCode() == null) {
                    gaps.add(new CoverageGap(e.sortKey(), "CONTENT_MAPPING_PENDING"));
                    continue;
                }
                if (!uncovered.contains(e.tagCode())) continue;
                var match = warmUp.stream().filter(lesson -> lesson.knowledgeTagCodes().contains(e.tagCode()))
                        .findFirst();
                if (match.isEmpty()) {
                    gaps.add(new CoverageGap(e.tagCode(), "MISSING_WARM_UP_RESOURCE"));
                    continue;
                }
                var lesson = match.get();
                var matchedTags = new TreeSet<>(lesson.knowledgeTagCodes());
                matchedTags.retainAll(uncovered);
                uncovered.removeAll(matchedTags);
                // A whole-lesson fallback would duplicate a goal lesson. Keep the full target
                // curriculum in GOAL; only a resolved grammar point receives focused warm-up.
                if (goals.stream().anyMatch(goal -> goal.lessonId().equals(lesson.lessonId()))) continue;
                requireDuration(lesson);
                selectedIds.add(lesson.lessonId());
                resources.add(new SelectedLearningResource(lesson, LearningPhase.WARM_UP, "WEAK_TAG",
                        "Review weak knowledge tags: " + String.join(", ", matchedTags) + ".", matchedTags));
            }
        }
        addGoals(goals, selectedIds, resources, profile);
        return new LearningSelectionResult(resources, gaps, policy.version(), weak);
    }

    private boolean hasWrongGrammarAnswer(WeaknessEvidence evidence) {
        return WeaknessEvidence.SKILL_GRAMMAR.equals(evidence.skill()) && evidence.wrongCount() > 0;
    }

    private String lexemeOf(WeaknessEvidence evidence) {
        String canonical = evidence.canonicalId();
        String prefix = evidence.skill() + ":";
        if (canonical != null && canonical.startsWith(prefix)) {
            String lexeme = canonical.substring(prefix.length()).strip();
            return lexeme.isEmpty() || lexeme.startsWith("UNMAPPED_QUESTION:") ? null : lexeme;
        }
        // Older imported profiles used the shorter VOCAB prefix; keep their remediation resolvable.
        if (WeaknessEvidence.SKILL_VOCABULARY.equals(evidence.skill())
                && canonical != null && canonical.startsWith("VOCAB:")) {
            String lexeme = canonical.substring("VOCAB:".length()).strip();
            return lexeme.isEmpty() || lexeme.startsWith("UNMAPPED_QUESTION:") ? null : lexeme;
        }
        return null;
    }

    /**
     * [JLAS-W0108] Giai đoạn GOAL — lấy trọn vẹn từng bài trong dải đã chọn theo ability band.
     * Whole-lesson warm-ups for lessons outside the target are
     * deduped; a target lesson stays in GOAL rather than being repeated in both phases.
     */
    private void addGoals(List<LearningResourceCandidate> goals, HashSet<Long> selectedIds,
            List<SelectedLearningResource> resources, LearningProfileSnapshot profile) {
        for (var lesson : goals) {
            if (!selectedIds.add(lesson.lessonId())) continue;
            requireDuration(lesson);
            resources.add(new SelectedLearningResource(lesson, LearningPhase.GOAL_LEARNING, "GOAL_TARGET",
                    "Study the selected JLPT curriculum from " + profile.assessedLevel()
                            + " to " + profile.targetLevel() + ".", lesson.knowledgeTagCodes()));
        }
    }

    /**
     * [JLAS-W0108] P3 — D1: tổng trung bình < 40% VÀ ≥ 3/5 skill < 40% + đủ mẫu → chỉ GỢI Ý kiểm tra
     * xác minh nền tảng (gap), KHÔNG tự giao ôn toàn bộ 70–100% sách. Gác sau
     * {@code jlas.learning.selection.full-review-enabled} (mặc định false) đến khi D1 được chốt.
     */
    private void suggestFoundationVerification(List<SkillScore> scores, List<CoverageGap> gaps) {
        if (!fullReviewEnabled) return;
        int assessed = scores.stream().mapToInt(SkillScore::assessedCount).sum();
        if (assessed <= 0) return;
        double overall = (double) scores.stream().mapToInt(SkillScore::correctCount).sum() / assessed;
        long veryWeakSkills = scores.stream()
                .filter(score -> score.assessedCount() > 0 && score.correctRatio() < 0.40)
                .count();
        if (overall < 0.40 && veryWeakSkills >= 3) {
            gaps.add(new CoverageGap("ASSESSMENT", "FOUNDATION_VERIFICATION_SUGGESTED"));
        }
    }

    private void addGrammarWarmUp(LearningResourceCandidate point, List<SelectedLearningResource> resources) {
        requireDuration(point);
        resources.add(new SelectedLearningResource(point, LearningPhase.WARM_UP, "WEAK_GRAMMAR_POINT",
                "Ôn lại mẫu ngữ pháp đã sai trong Smart Test.", point.knowledgeTagCodes()));
    }

    private void addItemWarmUp(LearningResourceCandidate item, String skill, List<SelectedLearningResource> resources) {
        requireDuration(item);
        String reasonCode = WeaknessEvidence.SKILL_KANJI.equals(skill) ? "WEAK_KANJI_ITEM" : "WEAK_VOCAB_ITEM";
        resources.add(new SelectedLearningResource(item, LearningPhase.WARM_UP, reasonCode,
                "Ôn lại từ đã sai trong Smart Test.", item.knowledgeTagCodes()));
    }

    private void addComprehensionWarmUp(LearningResourceCandidate lesson, String skill,
            List<SelectedLearningResource> resources) {
        requireDuration(lesson);
        String reasonCode = WeaknessEvidence.SKILL_READING.equals(skill) ? "WEAK_READING_ITEM" : "WEAK_LISTENING_ITEM";
        String label = WeaknessEvidence.SKILL_READING.equals(skill) ? "đọc hiểu" : "nghe hiểu";
        resources.add(new SelectedLearningResource(lesson, LearningPhase.WARM_UP, reasonCode,
                "Luyện " + label + " cùng cấp độ còn yếu trong Smart Test.", lesson.knowledgeTagCodes()));
    }

    /**
     * [JLAS-W0108] P3 — Đọc/nghe warm-up theo bảng % đúng (§6.1), riêng từng skill. Số bài =
     * {@link WeakTagPolicy#comprehensionLessonCount}; không có mẫu → INSUFFICIENT_EVIDENCE (không phải 0%).
     * Chỉ dùng bài ĐÚNG cấp (không fallback sai cấp); thiếu đủ số bài → gap MISSING_WARM_UP_RESOURCE.
     */
    private void addComprehensionWarmUps(List<SkillScore> skillScores, LearningProfileSnapshot profile,
            HashSet<Long> selectedIds, List<SelectedLearningResource> resources, List<CoverageGap> gaps) {
        for (String skill : List.of(WeaknessEvidence.SKILL_READING, WeaknessEvidence.SKILL_LISTENING)) {
            var score = skillScores.stream().filter(s -> skill.equals(s.skill())).findFirst();
            if (score.isEmpty()) continue;
            int count = WeakTagPolicy.comprehensionLessonCount(score.get());
            if (count < 0) {
                gaps.add(new CoverageGap("SKILL:" + skill, "INSUFFICIENT_EVIDENCE"));
                continue;
            }
            if (count == 0) continue;
            var lessons = catalog.findComprehensionLessons(skill, profile.assessedLevel());
            int added = 0;
            for (var lesson : lessons) {
                if (added >= count) break;
                if (!selectedIds.add(lesson.lessonId())) continue;
                requireDuration(lesson);
                addComprehensionWarmUp(lesson, skill, resources);
                added++;
            }
            if (added < count) {
                gaps.add(new CoverageGap("SKILL:" + skill, "MISSING_WARM_UP_RESOURCE"));
            }
        }
    }

    /**
     * [JLAS-W0108] From-zero — chọn TOÀN BỘ bài nền tảng bảng chữ cái (Hiragana/Katakana) trong sách
     * foundation (jlpt_level NULL) làm warm-up. Kana lesson có level null nên không lọt qua
     * {@link #candidates} (rank -1); lấy thẳng từ catalog theo thứ tự ổn định.
     */
    private void addKanaFoundationWarmUps(List<Long> foundationBookIds, HashSet<Long> selectedIds,
            List<SelectedLearningResource> resources) {
        if (foundationBookIds == null || foundationBookIds.isEmpty()) return;
        var lessons = catalog.findPublishedLessons(foundationBookIds).stream()
                .filter(lesson -> foundationBookIds.contains(lesson.textbookId()))
                .sorted(Comparator.comparingInt((LearningResourceCandidate lesson) -> foundationBookIds.indexOf(lesson.textbookId()))
                        .thenComparing(LearningResourceCandidate::unitOrder)
                        .thenComparing(LearningResourceCandidate::unitId)
                        .thenComparing(LearningResourceCandidate::lessonOrder)
                        .thenComparing(LearningResourceCandidate::lessonId))
                .toList();
        for (var lesson : lessons) {
            requireDuration(lesson);
            selectedIds.add(lesson.lessonId());
            resources.add(new SelectedLearningResource(lesson, LearningPhase.WARM_UP, REASON_KANA_FOUNDATION,
                    "Học bảng chữ cái (Hiragana & Katakana) làm nền tảng trước khi vào bài học.",
                    lesson.knowledgeTagCodes()));
        }
    }

    private List<LearningResourceCandidate> candidates(List<Long> books, int minimumLevel, int maximumLevel) {
        if (books.isEmpty()) return List.of();
        return catalog.findPublishedLessons(books).stream()
                .filter(lesson -> books.contains(lesson.textbookId()))
                .filter(lesson -> rank(lesson.level()) >= minimumLevel && rank(lesson.level()) <= maximumLevel)
                .sorted(Comparator.comparingInt((LearningResourceCandidate lesson) -> rank(lesson.level()))
                        .thenComparingInt(lesson -> books.indexOf(lesson.textbookId()))
                        .thenComparing(LearningResourceCandidate::unitOrder)
                        .thenComparing(LearningResourceCandidate::unitId)
                        .thenComparing(LearningResourceCandidate::lessonOrder)
                        .thenComparing(LearningResourceCandidate::lessonId))
                .toList();
    }

    private int compareWeakness(WeaknessEvidence first, WeaknessEvidence second) {
        // Cross-multiplication avoids rounding different error ratios into a tie.
        int ratio = Long.compare((long) second.wrongCount() * first.assessedCount(),
                (long) first.wrongCount() * second.assessedCount());
        if (ratio != 0) return ratio;
        int wrong = Integer.compare(second.wrongCount(), first.wrongCount());
        return wrong != 0 ? wrong : first.sortKey().compareTo(second.sortKey());
    }

    private void validate(Long userId, LearningProfileSnapshot profile, LearningCatalogScope scope) {
        if (!positive(userId)) throw new ApiException(ErrorCode.UNAUTHORIZED);
        // [JLAS-W0108] From-zero (BELOW_N5) không có attempt: chỉ survey + revision là đủ.
        if (profile == null || !positive(profile.userId()) || !positive(profile.surveyId())
                || profile.assessmentRevision() == null || profile.assessmentRevision().isBlank()
                || (!BELOW_N5.equals(profile.assessedLevel()) && !positive(profile.assessmentAttemptId()))) {
            invalid("A persisted survey and versioned assessment profile are required.");
        }
        if (!userId.equals(profile.userId())) throw new ApiException(ErrorCode.FORBIDDEN);
        if (!profile.surveyCompleted() || !profile.assessmentFinalized()) {
            throw new ApiException(ErrorCode.CONFLICT, "Complete the survey and finalize Smart Test results first.");
        }
        if (profile.goalCode() == null || profile.goalCode().isBlank()) invalid("A learning goal is required.");
        if (!"JLPT".equals(profile.goalCode())) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "This learning goal does not have a selection policy yet.");
        }
        if (rank(profile.assessedLevel()) < 0 || rank(profile.targetLevel()) < 1
                || rank(profile.targetLevel()) < rank(profile.assessedLevel())
                || profile.minutesPerDay() == null || profile.minutesPerDay() < 5 || profile.minutesPerDay() > 180) {
            invalid("Valid assessed/target levels and 5 to 180 daily study minutes are required.");
        }
        if (profile.weaknesses() != null) {
            Set<String> keys = new HashSet<>();
            for (var e : profile.weaknesses()) {
                if (e == null || e.skill() == null || e.skill().isBlank()
                        || e.canonicalId() == null || e.canonicalId().isBlank()
                        || !keys.add(e.skill() + "\u0000" + e.canonicalId())
                        || e.assessedCount() < 0 || e.wrongCount() < 0 || e.wrongCount() > e.assessedCount()) {
                    invalid("Weakness evidence must have unique stable skill/canonical keys and valid assessed/wrong counts.");
                }
            }
        }
        if (scope == null || !validBooks(scope.warmUpTextbookIds()) || !validBooks(scope.goalTextbookIds())
                || scope.goalTextbookIds().isEmpty()) {
            invalid("An explicit curriculum scope with unique positive textbook IDs is required.");
        }
    }

    private boolean validBooks(List<Long> books) {
        return books != null && books.stream().allMatch(this::positive) && new HashSet<>(books).size() == books.size();
    }

    private boolean positive(Long id) {
        return id != null && id > 0;
    }

    private int rank(String level) {
        return level == null ? -1 : LEVELS.indexOf(level);
    }

    private void requireDuration(LearningResourceCandidate lesson) {
        if (lesson.estimatedMinutes() == null || lesson.estimatedMinutes() <= 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Bài học chưa có thời lượng phù hợp. Vui lòng thử lại sau.");
        }
    }

    private void invalid(String message) {
        throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
