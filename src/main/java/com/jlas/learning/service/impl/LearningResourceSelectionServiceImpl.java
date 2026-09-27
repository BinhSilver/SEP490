package com.jlas.learning.service.impl;

import com.jlas.learning.dto.*;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.service.LearningCatalogSource;
import com.jlas.learning.service.LearningResourceSelectionService;
import lombok.RequiredArgsConstructor;
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
    /** [JLAS-W0108] Tổng % đúng > ngưỡng này → pass cấp hiện tại (cấp tự chọn trong survey), bỏ warm-up. */
    private static final double PASS_CORRECT_RATIO = 0.60;
    /** [JLAS-W0108] Kỹ năng đúng < ngưỡng này → "rất yếu", ôn TOÀN BỘ kỹ năng đó ở cấp hiện tại. */
    private static final double FULL_REVIEW_CORRECT_RATIO = 0.40;
    /** [JLAS-W0108] Chỉ kỹ năng có bài học chuyên biệt mới hỗ trợ "ôn toàn bộ" (lesson-based).
     *  Đọc/nghe KHÔNG có bài chuyên biệt ở N5/N4 (bài tổng hợp GRAMMAR chứa sẵn phần đọc + nghe)
     *  nên được xử lý riêng bằng comprehension lesson ở {@link #addComprehensionWarmUps}. */
    private static final List<String> FULL_REVIEWABLE_SKILLS = List.of(WeaknessEvidence.SKILL_GRAMMAR);
    private final LearningCatalogSource catalog;
    private final WeakTagPolicy policy;

    @Override
    public LearningSelectionResult select(Long userId, LearningProfileSnapshot profile, LearningCatalogScope scope) {
        validate(userId, profile, scope);
        // [JLAS-W0108] Giai đoạn GOAL chỉ lấy bài Ở TRÊN trình độ đã kết luận (loại trừ assessedLevel):
        // người học đã đạt cấp hiện tại nên GOAL phải là nội dung MỚI tới target, không lặp lại cấp cũ.
        // VD N5→N4 lấy đúng 25 bài N4 (không trộn 25 bài N5 đã biết); N5→N3 lấy N4+N3; N4→N4 giữ N4.
        int goalMin = Math.min(rank(profile.assessedLevel()) + 1, rank(profile.targetLevel()));
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

        if (passesCurrentLevel(skillScores)) {
            // [JLAS-W0108] Tổng % đúng > 60% → pass cấp hiện tại (cấp tự chọn trong survey): bỏ warm-up, vào thẳng GOAL.
            addGoals(goals, selectedIds, resources, profile);
            return new LearningSelectionResult(resources, gaps, policy.version(), List.of());
        }

        Set<String> fullReviewSkills = fullReviewSkills(skillScores);
        Set<String> passedSkills = passedSkills(skillScores);
        var weak = weaknesses.stream()
                .filter(policy::isWeak)
                .filter(e -> !passedSkills.contains(e.skill()))
                .sorted(this::compareWeakness)
                .toList();
        weaknesses.stream().filter(e -> !policy.hasEnoughEvidence(e))
                .sorted(Comparator.comparing(WeaknessEvidence::sortKey))
                .forEach(e -> gaps.add(new CoverageGap(e.sortKey(), "INSUFFICIENT_ASSESSMENT")));
        if (weaknesses.isEmpty()) {
            gaps.add(new CoverageGap("ASSESSMENT", "NO_ASSESSMENT_EVIDENCE"));
        }

        // (1) [JLAS-W0108] Kỹ năng rất yếu (<40% đúng) → ôn TOÀN BỘ bài của skill đó ở cấp hiện tại.
        for (String skill : fullReviewSkills) {
            for (var lesson : candidates(scope.warmUpTextbookIds(), 0, rank(profile.assessedLevel()))) {
                if (!skill.equals(lesson.skill())) continue;
                requireDuration(lesson);
                selectedIds.add(lesson.lessonId());
                resources.add(new SelectedLearningResource(lesson, LearningPhase.WARM_UP, "FULL_SKILL_REVIEW",
                        "Ôn lại toàn bộ kỹ năng " + skillLabel(skill) + " cấp " + profile.assessedLevel()
                                + " trước khi học cấp mục tiêu.", lesson.knowledgeTagCodes()));
            }
        }

        // (1b) [JLAS-W0108] Đọc/nghe warm-up theo ĐIỂM SKILL (không phụ thuộc weakness evidence):
        // skill có đánh giá nhưng chưa đạt -> ôn 1 bài comprehension cùng cấp đánh giá.
        addComprehensionWarmUps(skillScores, profile, selectedIds, resources, gaps);

        // (2) [JLAS-W0108] Kỹ năng 40–60% đúng → ôn đúng item/điểm sai (skill đã full-review hoặc đã pass thì bỏ).
        if (!weak.isEmpty()) {
            var warmUp = candidates(scope.warmUpTextbookIds(), 0, rank(profile.assessedLevel()));
            var uncovered = new LinkedHashSet<String>();
            weak.forEach(e -> {
                if (e.tagCode() != null) uncovered.add(e.tagCode());
            });
            for (var e : weak) {
                if (fullReviewSkills.contains(e.skill()) || passedSkills.contains(e.skill())) continue;
                // [JLAS-W0108] Đọc/nghe đã xử lý riêng ở addComprehensionWarmUps (theo điểm skill),
                // không qua nhánh tag/grammar/vocab bên dưới.
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
                // [JLAS-W0108] Lát 2.5 — GRAMMAR: warm-up theo đúng grammar point đã sai (không phải cả bài).
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
                if ((WeaknessEvidence.SKILL_KANJI.equals(e.skill())
                        || WeaknessEvidence.SKILL_VOCABULARY.equals(e.skill()))
                        && e.vocabularyEntryId() != null) {
                    var item = catalog.findVocabularyItem(e.vocabularyEntryId());
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
                requireDuration(lesson);
                selectedIds.add(lesson.lessonId());
                resources.add(new SelectedLearningResource(lesson, LearningPhase.WARM_UP, "WEAK_TAG",
                        "Review weak knowledge tags: " + String.join(", ", matchedTags) + ".", matchedTags));
            }
        }
        addGoals(goals, selectedIds, resources, profile);
        return new LearningSelectionResult(resources, gaps, policy.version(), weak);
    }

    /**
     * [JLAS-W0108] Giai đoạn GOAL — lấy trọn vẹn từng bài trong giáo trình đã công bố ở dải
     * [assessedLevel, targetLevel]. Bài đã dùng làm warm-up dạng "cả bài" không lặp lại ở GOAL.
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

    /** [JLAS-W0108] Tổng % đúng > {@value #PASS_CORRECT_RATIO} → pass cấp hiện tại (bỏ warm-up). */
    private static boolean passesCurrentLevel(List<SkillScore> scores) {
        int assessed = scores.stream().mapToInt(SkillScore::assessedCount).sum();
        int correct = scores.stream().mapToInt(SkillScore::correctCount).sum();
        return assessed > 0 && (double) correct / assessed > PASS_CORRECT_RATIO;
    }

    /** [JLAS-W0108] Kỹ năng rất yếu (&lt; {@value #FULL_REVIEW_CORRECT_RATIO} đúng) hỗ trợ ôn toàn bộ. */
    private static Set<String> fullReviewSkills(List<SkillScore> scores) {
        Set<String> result = new HashSet<>();
        for (var score : scores) {
            if (FULL_REVIEWABLE_SKILLS.contains(score.skill()) && score.assessedCount() > 0
                    && score.correctRatio() < FULL_REVIEW_CORRECT_RATIO) {
                result.add(score.skill());
            }
        }
        return result;
    }

    /** [JLAS-W0108] Kỹ năng đạt (&gt; {@value #PASS_CORRECT_RATIO} đúng) → bỏ ôn, không ghi nhận yếu. */
    private static Set<String> passedSkills(List<SkillScore> scores) {
        Set<String> result = new HashSet<>();
        for (var score : scores) {
            if (score.assessedCount() > 0 && score.correctRatio() > PASS_CORRECT_RATIO) {
                result.add(score.skill());
            }
        }
        return result;
    }

    private static String skillLabel(String skill) {
        return switch (skill) {
            case WeaknessEvidence.SKILL_GRAMMAR -> "ngữ pháp";
            case WeaknessEvidence.SKILL_READING -> "đọc hiểu";
            case WeaknessEvidence.SKILL_LISTENING -> "nghe hiểu";
            default -> skill;
        };
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
     * [JLAS-W0108] Đọc/nghe warm-up theo ĐIỂM SKILL (không phụ thuộc weakness evidence): skill có
     * đánh giá nhưng chưa đạt (&le; {@value #PASS_CORRECT_RATIO} đúng) → ôn 1 bài comprehension
     * cùng cấp đánh giá. Ở N5/N4 bài tổng hợp GRAMMAR chứa sẵn phần đọc + nghe.
     */
    private void addComprehensionWarmUps(List<SkillScore> skillScores, LearningProfileSnapshot profile,
            HashSet<Long> selectedIds, List<SelectedLearningResource> resources, List<CoverageGap> gaps) {
        for (String skill : List.of(WeaknessEvidence.SKILL_READING, WeaknessEvidence.SKILL_LISTENING)) {
            var score = skillScores.stream().filter(s -> skill.equals(s.skill())).findFirst();
            if (score.isEmpty() || score.get().assessedCount() <= 0) continue;
            if (score.get().correctRatio() > PASS_CORRECT_RATIO) continue;
            var lesson = catalog.findComprehensionLesson(skill, profile.assessedLevel());
            if (lesson.isPresent()) {
                selectedIds.add(lesson.get().lessonId());
                addComprehensionWarmUp(lesson.get(), skill, resources);
            } else {
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
            throw new ApiException(ErrorCode.CONFLICT, "Selected lessons must have a positive estimated duration.");
        }
    }

    private void invalid(String message) {
        throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
