package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.LearningPacePreviewResponse;
import com.jlas.learning.dto.LearningPaceRequest;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.InteractivePaceReplanService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Pace changes schedule existing activities across days, without creating new progress units.
 * Started packages stay intact; ids, membership, completion, scores and target level are untouched.
 * The preview token fingerprints the layout the learner saw, so a stale confirm is a CONFLICT while a repeated
 * confirm of an already-applied pace is a harmless no-op. */
@Service
public class InteractivePaceReplanServiceImpl implements InteractivePaceReplanService {
    static final int[] PRESETS = {15, 20, 30, 45, 60};
    private static final int DEFAULT_MINUTES = 30;

    private final UserLearningPathRepository paths;
    private final LearningPathPackageRepository packages;
    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;

    public InteractivePaceReplanServiceImpl(UserLearningPathRepository paths, LearningPathPackageRepository packages,
            UserLearningPathItemRepository items, LearningActivityMemberRepository members) {
        this.paths = paths;
        this.packages = packages;
        this.items = items;
        this.members = members;
    }

    /** One preset step from {@code current}; a non-preset value steps to the nearest preset in that direction. */
    public static int nextPreset(int current, String direction) {
        switch (direction) {
            case "SAME":
                return current;
            case "LIGHTER":
                for (int i = PRESETS.length - 1; i >= 0; i--) {
                    if (PRESETS[i] < current) return PRESETS[i];
                }
                return PRESETS[0];
            case "FASTER":
                for (int preset : PRESETS) {
                    if (preset > current) return preset;
                }
                return PRESETS[PRESETS.length - 1];
            default:
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Nhịp học không hợp lệ.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public LearningPacePreviewResponse preview(Long userId, String direction) {
        UserLearningPath path = paths.findByUserIdAndStatusAndExperienceVersion(
                        userId, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình đang hoạt động."));
        return describe(path, direction, packages.findByLearningPathIdOrderByOrderIndex(path.getId()),
                items.findByLearningPathIdOrderByOrderIndex(path.getId()), inputs(path.getId()));
    }

    @Override
    @Transactional
    public LearningPacePreviewResponse apply(Long userId, LearningPaceRequest request) {
        if (request == null || request.direction() == null || request.newMinutesPerDay() == null
                || request.previewToken() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu thông tin xác nhận thay đổi nhịp học.");
        }
        UserLearningPath path = paths.lockActiveInteractive(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình đang hoạt động."));
        // Lock in package -> activity order, matching package start/refresh. Study writers lock activities.
        List<LearningPathPackage> all = packages.findByPathForUpdate(path.getId());
        List<UserLearningPathItem> activities = items.findByPathForUpdate(path.getId());
        Inputs inputs = inputs(path.getId());
        int target = request.newMinutesPerDay();
        int expectedTarget = nextPreset(currentMinutes(path), request.direction()); // validate retries too
        boolean alreadyApplied = currentMinutes(path) == target;
        if (!alreadyApplied && (expectedTarget != target
                || !token(path, request.direction(), currentMinutes(path), target, all, activities, inputs)
                        .equals(request.previewToken()))) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Tiến độ đã thay đổi kể từ lúc xem trước. Hãy xem lại rồi xác nhận.");
        }
        LearningPacePreviewResponse now = alreadyApplied
                ? describe(path, "SAME", all, activities, inputs) : describe(path, request.direction(), all, activities, inputs);
        if (alreadyApplied || !now.changed()) {
            return new LearningPacePreviewResponse(now.direction(), now.currentMinutesPerDay(), target, false,
                    now.remainingPackages(), now.newRemainingDays(), now.newRemainingDays(), now.previewToken());
        }

        Schedule schedule = schedule(all, activities, target, inputs);
        // MySQL checks unique(path, day) immediately. Park changing rows beyond both layouts first;
        // assigning final days in place can collide with a later package's old day.
        int parking = Math.addExact(Math.max(all.stream().mapToInt(LearningPathPackage::getDayNumber).max().orElse(0),
                schedule.packageDays().values().stream().mapToInt(Integer::intValue).max().orElse(0)), 1);
        for (LearningPathPackage pkg : all) {
            if (schedule.packageDays().containsKey(pkg.getId())) {
                pkg.setDayNumber(parking++);
                packages.save(pkg);
            }
        }
        packages.flush();
        for (LearningPathPackage pkg : all) {
            Integer day = schedule.packageDays().get(pkg.getId());
            if (day != null) {
                pkg.setDayNumber(day);
                packages.save(pkg);
            }
        }
        applyActivities(activities, schedule);
        path.setMinutesPerDay(target);
        paths.save(path);
        return new LearningPacePreviewResponse(now.direction(), now.currentMinutesPerDay(), target, true,
                now.remainingPackages(), now.currentRemainingDays(), now.newRemainingDays(), now.previewToken());
    }

    private LearningPacePreviewResponse describe(UserLearningPath path, String direction,
            List<LearningPathPackage> all, List<UserLearningPathItem> activities, Inputs inputs) {
        int current = currentMinutes(path);
        int target = nextPreset(current, direction);
        int currentDays = remainingDays(all, activities, Map.of(), Map.of());
        Schedule proposed = target == current ? new Schedule(Map.of(), Map.of(), Map.of()) : schedule(all, activities, target, inputs);
        return new LearningPacePreviewResponse(direction, current, target, target != current,
                (int) all.stream().filter(p -> p.getStatus() != LearningPackageStatus.COMPLETED).count(),
                currentDays, remainingDays(all, activities, proposed.packageDays(), proposed.activityDays()),
                token(path, direction, current, target, all, activities, inputs));
    }

    private record Inputs(List<LearningActivityMember> members, Set<Long> history) { }
    private record ScheduledPart(PaceActivityPartitioner.Part part, int day) { }
    private record Schedule(Map<Long, Integer> packageDays, Map<Long, Integer> activityDays,
                            Map<Long, List<ScheduledPart>> parts) { }

    private Inputs inputs(Long pathId) {
        return new Inputs(members.findByPathInStudyOrder(pathId), Set.copyOf(items.findActivityIdsWithHistory(pathId)));
    }

    private static Schedule schedule(List<LearningPathPackage> all, List<UserLearningPathItem> activities, int budget, Inputs inputs) {
        Map<Long, List<UserLearningPathItem>> grouped = activities.stream()
                .filter(a -> a.getLearningPackage() != null)
                .collect(java.util.stream.Collectors.groupingBy(a -> a.getLearningPackage().getId()));
        int protectedOrder = all.stream().filter(p -> p.getStatus() == LearningPackageStatus.COMPLETED
                        || p.getStatus() == LearningPackageStatus.IN_PROGRESS || p.getStartedAt() != null
                        || grouped.getOrDefault(p.getId(), List.of()).stream().anyMatch(a ->
                            a.getActivityState() != LearningActivityState.LOCKED
                            || inputs.history().contains(a.getId())
                            || !"NOT_STARTED".equals(a.getStudyStatus())
                            || !"NOT_READY".equals(a.getAssessmentStatus())))
                .mapToInt(LearningPathPackage::getOrderIndex).max().orElse(0);
        int day = all.stream().filter(p -> p.getOrderIndex() <= protectedOrder)
                .mapToInt(p -> Math.max(p.getDayNumber(), grouped.getOrDefault(p.getId(), List.of()).stream()
                        .map(UserLearningPathItem::getDayNumber).filter(java.util.Objects::nonNull)
                        .mapToInt(Integer::intValue).max().orElse(p.getDayNumber())))
                .max().orElse(0) + 1;
        Map<Long, Integer> packageDays = new LinkedHashMap<>();
        Map<Long, Integer> activityDays = new LinkedHashMap<>();
        Map<Long, List<ScheduledPart>> parts = new LinkedHashMap<>();
        var membership = inputs.members().stream().collect(java.util.stream.Collectors.groupingBy(m -> m.getActivity().getId()));
        for (var pkg : all) {
            if (pkg.getOrderIndex() <= protectedOrder) continue;
            List<UserLearningPathItem> work = grouped.getOrDefault(pkg.getId(), List.of());
            if (work.isEmpty()) throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Bài học chưa đủ thông tin thời lượng để đổi nhịp an toàn.");
            packageDays.put(pkg.getId(), day);
            int used = 0;
            for (var activity : work) {
                var partition = PaceActivityPartitioner.partition(activity,
                        membership.getOrDefault(activity.getId(), List.of()), budget);
                List<ScheduledPart> scheduled = new ArrayList<>();
                for (var part : partition) {
                    if (used + part.minutes() > budget) { day++; used = 0; }
                    scheduled.add(new ScheduledPart(part, day));
                    used += part.minutes();
                }
                parts.put(activity.getId(), List.copyOf(scheduled));
                // For remaining-day preview use the LAST part, not only the original activity's start.
                activityDays.put(activity.getId(), day);
            }
            day++; // distinct start day per logical package; preserves the schema's unique key
        }
        return new Schedule(packageDays, activityDays, parts);
    }

    private void applyActivities(List<UserLearningPathItem> originals, Schedule schedule) {
        record Write(UserLearningPathItem activity, ScheduledPart part, int order) { }
        List<Write> writes = new ArrayList<>();
        Map<String, Integer> lastPart = new LinkedHashMap<>();
        originals.forEach(a -> lastPart.merge(partKey(a), a.getPartIndex(), Math::max));
        int order = 0;
        for (var original : originals) {
            order = Math.max(order, original.getOrderIndex());
            var planned = schedule.parts().get(original.getId());
            if (planned == null) { order++; continue; }
            for (int i = 0; i < planned.size(); i++) {
                var part = planned.get(i);
                var activity = i == 0 ? original : copyUntouched(original,
                        lastPart.merge(partKey(original), 1, Integer::sum));
                writes.add(new Write(activity, part, order++));
            }
        }
        // Preserve all existing IDs, but make room for extra parts before assigning final path-wide orders.
        int parking = Math.max(order, originals.stream().mapToInt(UserLearningPathItem::getOrderIndex).max().orElse(0)) + 1;
        for (var write : writes) {
            if (write.activity().getId() != null && write.activity().getOrderIndex() != write.order()) {
                write.activity().setOrderIndex(parking++);
                items.save(write.activity());
            }
        }
        items.flush();
        for (var write : writes) {
            var activity = write.activity();
            activity.setOrderIndex(write.order());
            activity.setDayNumber(write.part().day());
            activity.setEstimatedMinutes(write.part().part().minutes());
            items.save(activity);
            for (var member : write.part().part().members()) {
                member.setActivity(activity);
                members.save(member); // same row id, canonical id, source revision and source ordering
            }
        }
        // Splitting can add required quiz overhead; publish the actual sum, not the old shorter estimate.
        writes.stream().map(w -> w.activity().getLearningPackage()).distinct().forEach(pkg -> {
            pkg.setEstimatedMinutes(writes.stream().filter(w -> w.activity().getLearningPackage().getId().equals(pkg.getId()))
                    .mapToInt(w -> w.part().part().minutes()).sum());
            packages.save(pkg);
        });
    }

    private static String partKey(UserLearningPathItem activity) {
        return (activity.getLearningPackage() == null ? "legacy" : activity.getLearningPackage().getId())
                + ":" + activity.getActivityType();
    }

    private static UserLearningPathItem copyUntouched(UserLearningPathItem source, int partIndex) {
        return UserLearningPathItem.builder().learningPath(source.getLearningPath()).learningPackage(source.getLearningPackage())
                .activityType(source.getActivityType()).activityState(LearningActivityState.LOCKED).partIndex(partIndex)
                .lesson(source.getLesson()).grammarPoint(source.getGrammarPoint()).vocabularyEntry(source.getVocabularyEntry())
                .stageId(source.getStageId()).reason(source.getReason()).kind(source.getKind()).phase(source.getPhase())
                .reasonCode(source.getReasonCode()).matchedTagCodes(source.getMatchedTagCodes()).build();
    }

    private static int remainingDays(List<LearningPathPackage> all, List<UserLearningPathItem> activities,
            Map<Long, Integer> packageDays, Map<Long, Integer> activityDays) {
        var pending = all.stream().filter(p -> p.getStatus() != LearningPackageStatus.COMPLETED).toList();
        if (pending.isEmpty()) return 0;
        var ids = pending.stream().map(LearningPathPackage::getId).collect(java.util.stream.Collectors.toSet());
        int first = pending.stream().mapToInt(p -> packageDays.getOrDefault(p.getId(), p.getDayNumber())).min().orElseThrow();
        int last = pending.stream().mapToInt(p -> packageDays.getOrDefault(p.getId(), p.getDayNumber())).max().orElseThrow();
        for (var activity : activities) {
            if (activity.getLearningPackage() != null && ids.contains(activity.getLearningPackage().getId())) {
                Integer day = activityDays.getOrDefault(activity.getId(), activity.getDayNumber());
                if (day != null) last = Math.max(last, day);
            }
        }
        return last - first + 1;
    }

    private static int currentMinutes(UserLearningPath path) {
        return path.getMinutesPerDay() == null ? DEFAULT_MINUTES : path.getMinutesPerDay();
    }

    private static String token(UserLearningPath path, String direction, int current, int target,
            List<LearningPathPackage> all, List<UserLearningPathItem> activities, Inputs inputs) {
        StringBuilder text = new StringBuilder().append(path.getId()).append("|").append(path.getVersion())
                .append("|").append(direction).append("|").append(current).append(">").append(target);
        for (LearningPathPackage pkg : all) {
            if (pkg.getStatus() == LearningPackageStatus.COMPLETED) continue;
            text.append("|").append(pkg.getId()).append(":").append(pkg.getStatus()).append(":")
                    .append(pkg.getDayNumber()).append(":").append(pkg.getEstimatedMinutes())
                    .append(":").append(pkg.getStartedAt()).append(":").append(pkg.getOrderIndex());
        }
        for (var activity : activities) {
            text.append("|a:").append(activity.getId()).append(":").append(activity.getDayNumber())
                    .append(":").append(activity.getEstimatedMinutes()).append(":").append(activity.getActivityState())
                    .append(":").append(activity.getStudyStatus()).append(":").append(activity.getAssessmentStatus())
                    .append(":").append(activity.getOrderIndex()).append(":")
                    .append(activity.getLearningPackage() == null ? null : activity.getLearningPackage().getId())
                    .append(":").append(activity.getPartIndex()).append(":").append(activity.getActivityType())
                    .append(":").append(inputs.history().contains(activity.getId()));
        }
        for (var member : inputs.members()) {
            text.append("|m:").append(member.getId()).append(":").append(member.getActivity().getId())
                    .append(":").append(member.getCanonicalId()).append(":").append(member.getMemberType())
                    .append(":").append(member.getOrderIndex()).append(":").append(member.getSourceRevision());
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
