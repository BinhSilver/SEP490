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
        var weakTags = profile.tagResults().stream().filter(policy::isWeak)
                .sorted(this::compareWeakness).toList();
        var gaps = new ArrayList<CoverageGap>();
        profile.tagResults().stream().filter(tag -> !policy.hasEnoughEvidence(tag))
                .sorted(Comparator.comparing(TagAssessmentResult::tagCode))
                .forEach(tag -> gaps.add(new CoverageGap(tag.tagCode(), "INSUFFICIENT_ASSESSMENT")));
        var resources = new ArrayList<SelectedLearningResource>();
        var selectedIds = new HashSet<Long>();
        if (!weakTags.isEmpty()) {
            var warmUp = candidates(scope.warmUpTextbookIds(), 0, rank(profile.assessedLevel()));
            var uncovered = new LinkedHashSet<String>();
            weakTags.forEach(tag -> uncovered.add(tag.tagCode()));
            for (var tag : weakTags) {
                if (!uncovered.contains(tag.tagCode())) continue;
                var match = warmUp.stream().filter(lesson -> lesson.knowledgeTagCodes().contains(tag.tagCode()))
                        .findFirst();
                if (match.isEmpty()) {
                    gaps.add(new CoverageGap(tag.tagCode(), "MISSING_WARM_UP_RESOURCE"));
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
        return new LearningSelectionResult(resources, gaps, policy.version());
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

    private int compareWeakness(TagAssessmentResult first, TagAssessmentResult second) {
        // Cross-multiplication avoids rounding different error ratios into a tie.
        int ratio = Long.compare((long) second.wrongCount() * first.assessedCount(),
                (long) first.wrongCount() * second.assessedCount());
        if (ratio != 0) return ratio;
        int wrong = Integer.compare(second.wrongCount(), first.wrongCount());
        return wrong != 0 ? wrong : first.tagCode().compareTo(second.tagCode());
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
        if (profile.tagResults() == null || profile.tagResults().isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "Finalized results must include tag assessment evidence.");
        }
        Set<String> tags = new HashSet<>();
        for (var tag : profile.tagResults()) {
            if (tag == null || tag.tagCode() == null || tag.tagCode().isBlank()
                    || !tag.tagCode().equals(tag.tagCode().trim()) || !tags.add(tag.tagCode())
                    || tag.assessedCount() < 0 || tag.wrongCount() < 0 || tag.wrongCount() > tag.assessedCount()) {
                invalid("Tag evidence must have unique stable codes and valid assessed/wrong counts.");
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
