package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.TextbookRepository;
import com.jlas.learning.config.InteractiveLearningV2Properties;
import com.jlas.learning.dto.CoverageGap;
import com.jlas.learning.dto.InteractiveCompositionResult;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.FoundationWarmUpComposer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** [JLAS-W0108] Composes the Hiragana/Katakana foundation warm-up for "from-zero" learners: one WARM_UP
 * package per kana chart lesson (10 lessons in the seeded "Bảng chữ cái tiếng Nhật" textbook — 5 Hiragana
 * + 5 Katakana), each with a single FOUNDATION activity whose members are the lesson's kana-character
 * content_item rows. Deliberately independent of {@link com.jlas.learning.service.InteractivePackageComposer}
 * (whole-lesson GOAL_LEARNING packages, always 4 parts) and {@link com.jlas.learning.service.WarmUpBComposer}
 * (Smart Test weakness-driven): a from-zero learner has no Smart Test evidence yet, so neither composer would
 * ever produce anything for them without this one — they would otherwise jump straight into N5 vocabulary
 * never having been taught the alphabet. */
@Service
@RequiredArgsConstructor
public class FoundationWarmUpComposerImpl implements FoundationWarmUpComposer {
    private final InteractiveLearningV2Properties properties;
    private final TextbookRepository textbooks;
    private final LessonRepository lessons;
    private final ContentItemRepository contentItems;
    private final LearningPathPackageRepository packages;
    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final UserLearningStageRepository stages;
    private final LearningWorkloadPolicy workloadPolicy = new LearningWorkloadPolicy();
    private final LearningDailyWorkloadPacker workloadPacker = new LearningDailyWorkloadPacker();

    @Override
    @Transactional
    public InteractiveCompositionResult composeFoundation(UserLearningPath path, LearningProfileSnapshot profile) {
        if (!properties.isEnabled()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Learning Path V2 chưa được bật.");
        }
        if (path.getExperienceVersion() != LearningExperienceVersion.INTERACTIVE_V2) {
            throw new ApiException(ErrorCode.CONFLICT, "Foundation composer chỉ nhận path INTERACTIVE_V2.");
        }
        if (!"BELOW_N5".equals(profile.assessedLevel())) {
            return new InteractiveCompositionResult(true, 0, List.of());
        }
        int dailyBudget = path.getMinutesPerDay() != null ? path.getMinutesPerDay()
                : profile.minutesPerDay() == null ? 0 : profile.minutesPerDay();
        if (dailyBudget <= 0) {
            return new InteractiveCompositionResult(false, 0,
                    List.of(new CoverageGap("PATH:" + path.getId(), "MISSING_DAILY_WORKLOAD_BUDGET")));
        }
        if (packages.existsByLearningPathIdAndPhase(path.getId(), "WARM_UP")) {
            // Already composed (by this composer or a prior call) — never duplicate.
            long existing = packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                    .filter(p -> "WARM_UP".equals(p.getPhase())
                            && p.getSourceLesson() != null && isFoundationLesson(p.getSourceLesson()))
                    .count();
            if (existing > 0) return new InteractiveCompositionResult(true, (int) existing, List.of());
        }

        List<Textbook> kanaBooks = textbooks.findKanaFoundationByStatus("PUBLISHED");
        if (kanaBooks.isEmpty()) {
            return new InteractiveCompositionResult(true, 0,
                    List.of(new CoverageGap("FOUNDATION", "MISSING_KANA_FOUNDATION_CONTENT")));
        }

        List<Lesson> kanaLessons = new ArrayList<>();
        for (Textbook book : kanaBooks) kanaLessons.addAll(lessons.findByTextbookIdAndStatus(book.getId(), "PUBLISHED"));
        if (kanaLessons.isEmpty()) {
            return new InteractiveCompositionResult(true, 0,
                    List.of(new CoverageGap("FOUNDATION", "MISSING_KANA_FOUNDATION_CONTENT")));
        }

        int dayNumber = items.findMaxDayNumberByLearningPathId(path.getId());
        int packageOrder = packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .mapToInt(LearningPathPackage::getOrderIndex).max().orElse(0);
        int itemOrder = items.findMaxOrderIndexByLearningPathId(path.getId());
        boolean isFirstPackageOverall = !packages.existsByLearningPathId(path.getId());
        Long stageId = stages.findByLearningPathIdOrderByStageIndexAsc(path.getId()).stream()
                .filter(stage -> "FOUNDATION".equals(stage.getLevel()))
                .findFirst().map(com.jlas.learning.entity.UserLearningStage::getId)
                .orElseThrow(() -> new ApiException(ErrorCode.BLOCKED_CONTENT,
                        "MISSING_FOUNDATION: Lộ trình chưa có stage FOUNDATION."));

        List<CoverageGap> gaps = new ArrayList<>();
        int packagesCreated = 0;
        for (Lesson lesson : kanaLessons) {
            List<ContentItem> characters = contentItems.findByLessonIdAndContentType(lesson.getId(), "READING");
            if (characters.isEmpty()) {
                gaps.add(new CoverageGap("LESSON:" + lesson.getId(), "MISSING_KANA_CHARACTERS"));
                continue;
            }
            String revision = sha256(lesson.getUpdatedAt() + "|" + characters.stream()
                    .map(c -> String.valueOf(c.getId())).reduce("", (a, b) -> a + "," + b));
            List<Long> characterIds = characters.stream().map(ContentItem::getId).distinct().toList();
            List<LearningWorkloadPolicy.ActivityPlan> activityPlans = workloadPolicy
                    .partition(LearningActivityType.FOUNDATION, characterIds, dailyBudget).stream()
                    .map(group -> new LearningWorkloadPolicy.ActivityPlan(LearningActivityType.FOUNDATION,
                            LearningMemberType.FOUNDATION_CHARACTER, group,
                            workloadPolicy.estimateMinutes(LearningActivityType.FOUNDATION, group.size())))
                    .toList();
            List<LearningDailyWorkloadPacker.PackageSegment> segments = workloadPacker.pack(activityPlans, dailyBudget);
            int segmentIndex = 0;
            for (var segment : segments) {
                dayNumber++;
                segmentIndex++;
                String title = segments.size() == 1 ? lesson.getTitle()
                        : lesson.getTitle() + " · Phần " + segmentIndex + "/" + segments.size();
                var pkg = packages.save(LearningPathPackage.builder()
                        .learningPath(path)
                        .sourceLesson(lesson)
                        .sourceSegmentIndex(segmentIndex)
                        .contentRevision(revision)
                        .stageId(stageId)
                        .phase("WARM_UP")
                        .dayNumber(dayNumber)
                        .orderIndex(++packageOrder)
                        .title(title)
                        .status(packagesCreated == 0 && isFirstPackageOverall
                                ? LearningPackageStatus.AVAILABLE : LearningPackageStatus.LOCKED)
                        .estimatedMinutes(segment.estimatedMinutes())
                        .build());
                // Every part the packer put into this day is saved (UAT 2026-10-06: only the first was, dropping kana),
                // numbered 1..n for uk(package, activity_type, part_index).
                int partIndex = 0;
                for (var plan : segment.activities()) {
                    int next = ++itemOrder;
                    var activity = items.save(UserLearningPathItem.builder()
                            .learningPath(path)
                            .learningPackage(pkg)
                            .lesson(lesson)
                            .orderIndex(next).partIndex(++partIndex)
                            .phase("WARM_UP")
                            .dayNumber(dayNumber)
                            .estimatedMinutes(plan.estimatedMinutes())
                            .activityType(LearningActivityType.FOUNDATION)
                            .activityState(LearningActivityState.LOCKED)
                            .build());
                    List<LearningActivityMember> rows = new ArrayList<>();
                    int index = 0;
                    for (Long id : plan.memberIds()) {
                        rows.add(LearningActivityMember.builder().activity(activity)
                                .memberType(LearningMemberType.FOUNDATION_CHARACTER)
                                .canonicalId(id).orderIndex(++index).sourceRevision(revision).build());
                    }
                    members.saveAll(rows);
                }
                packagesCreated++;
            }
        }
        return new InteractiveCompositionResult(true, packagesCreated, gaps);
    }

    private boolean isFoundationLesson(Lesson lesson) {
        return lesson.getUnit() != null && lesson.getUnit().getTextbook() != null
                && Boolean.TRUE.equals(lesson.getUnit().getTextbook().getIsLearningPathSource())
                && lesson.getUnit().getTextbook().getJlptLevel() == null;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
