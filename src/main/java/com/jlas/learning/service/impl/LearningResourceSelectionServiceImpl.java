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
    private final LearningCatalogSource catalog;
    private final WeakTagPolicy policy;

    @Override
    public LearningSelectionResult select(Long userId, LearningProfileSnapshot profile, LearningCatalogScope scope) {
        validate(userId, profile, scope);
        var goals = candidates(scope.goalTextbookIds(), rank(profile.assessedLevel()), rank(profile.targetLevel()));
        if (goals.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No published lessons match the selected JLPT curriculum and levels.");
        }
        var weaknesses = profile.weaknesses() == null ? List.<WeaknessEvidence>of() : profile.weaknesses();
        var weak = weaknesses.stream().filter(policy::isWeak).sorted(this::compareWeakness).toList();
        var gaps = new ArrayList<CoverageGap>();
        weaknesses.stream().filter(e -> !policy.hasEnoughEvidence(e))
                .sorted(Comparator.comparing(WeaknessEvidence::sortKey))
                .forEach(e -> gaps.add(new CoverageGap(e.sortKey(), "INSUFFICIENT_ASSESSMENT")));
        if (weaknesses.isEmpty()) {
            gaps.add(new CoverageGap("ASSESSMENT", "NO_ASSESSMENT_EVIDENCE"));
        }
        var resources = new ArrayList<SelectedLearningResource>();
        var selectedIds = new HashSet<Long>();
        if (!weak.isEmpty()) {
            var warmUp = candidates(scope.warmUpTextbookIds(), 0, rank(profile.assessedLevel()));
            var uncovered = new LinkedHashSet<String>();
            var comprehensionResolved = new HashSet<String>();
            weak.forEach(e -> {
                if (e.tagCode() != null) uncovered.add(e.tagCode());
            });
            for (var e : weak) {
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
                        selectedIds.add(point.get().lessonId());
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
                        selectedIds.add(item.get().lessonId());
                        addItemWarmUp(item.get(), e.skill(), resources);
                        continue;
                    }
                    gaps.add(new CoverageGap(e.sortKey(), "MISSING_WARM_UP_RESOURCE"));
                    continue;
                }
                // [JLAS-W0108] Lát B — READING/LISTENING: warm-up bằng một bài luyện đọc/nghe cùng
                // cấp sai (ưu tiên content_item qua lesson.skill; fallback question bank xử lý ở
                // gate quiz Lát C). Mỗi skill chỉ tạo một item (5 đọc + 5 nghe là nội dung trong bài).
                if (WeaknessEvidence.SKILL_READING.equals(e.skill())
                        || WeaknessEvidence.SKILL_LISTENING.equals(e.skill())) {
                    if (!comprehensionResolved.add(e.skill())) continue;
                    String level = e.jlptLevel() != null ? e.jlptLevel() : profile.assessedLevel();
                    var lesson = catalog.findComprehensionLesson(e.skill(), level);
                    if (lesson.isPresent()) {
                        selectedIds.add(lesson.get().lessonId());
                        addComprehensionWarmUp(lesson.get(), e.skill(), resources);
                    } else {
                        gaps.add(new CoverageGap(e.sortKey(), "MISSING_WARM_UP_RESOURCE"));
                    }
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
        for (var lesson : goals) {
            if (!selectedIds.add(lesson.lessonId())) continue;
            requireDuration(lesson);
            resources.add(new SelectedLearningResource(lesson, LearningPhase.GOAL_LEARNING, "GOAL_TARGET",
                    "Study the selected JLPT curriculum from " + profile.assessedLevel()
                            + " to " + profile.targetLevel() + ".", lesson.knowledgeTagCodes()));
        }
        return new LearningSelectionResult(resources, gaps, policy.version(), weak);
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
        if (profile == null || !positive(profile.userId()) || !positive(profile.assessmentAttemptId())
                || !positive(profile.surveyId()) || profile.assessmentRevision() == null
                || profile.assessmentRevision().isBlank()) {
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
