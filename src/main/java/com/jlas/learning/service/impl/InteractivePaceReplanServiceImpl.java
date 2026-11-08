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
    /** D3-05 (owner 2026-10-24): a path starts at 30, 45, 60 or 90 minutes; 15 and 20 stay as lighter steps. */
    static final int[] PRESETS = {15, 20, 30, 45, 60, 90};
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

    /** One preset step from {@code current}; a non-preset value steps to the nearest preset in that direction. A pace
     * already beyond the last preset that way (an older 15- or 120-minute path) stays as it is, never steps backwards. */
    public static int nextPreset(int current, String direction) {
        switch (direction) {
            case "SAME":
                return current;
            case "LIGHTER":
                for (int i = PRESETS.length - 1; i >= 0; i--) {
                    if (PRESETS[i] < current) return PRESETS[i];
                }
                return current;
            case "FASTER":
                for (int preset : PRESETS) {
                    if (preset > current) return preset;
                }
                return current;
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
    @Transactional(readOnly = true)
    public com.jlas.learning.dto.LearningPaceMeasurement measure(Long userId) {
        UserLearningPath path = paths.findByUserIdAndStatusAndExperienceVersion(
                        userId, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình đang hoạt động."));
        return LearningPaceMeasurementPolicy.measure(packages.findByLearningPathIdOrderByOrderIndex(path.getId()),
                currentMinutes(path));
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

        Schedule schedule = schedule(path, all, activities, target, inputs);
        applySchedule(all, activities, schedule);
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
        Schedule proposed = target == current ? Schedule.empty() : schedule(path, all, activities, target, inputs);
        return new LearningPacePreviewResponse(direction, current, target, target != current,
                (int) all.stream().filter(p -> p.getStatus() != LearningPackageStatus.COMPLETED).count(),
                currentDays, remainingDays(all, activities, proposed.packageDays(), proposed.activityDays()),
                token(path, direction, current, target, all, activities, inputs));
    }

    private record Inputs(List<LearningActivityMember> members, Set<Long> history) { }
    private record ScheduledPart(PaceActivityPartitioner.Part part, int day) { }
    /** One part of an existing activity, in study order; part 0 keeps the activity row, later parts get new rows. */
    private record PlannedPart(UserLearningPathItem activity, int partNumber, ScheduledPart scheduled) { }
    /**
     * One package of the new layout. {@code reuse} is the existing row it keeps (null: a new row like {@code template});
     * a re-split chapter's packages are its day pieces, numbered {@code segmentIndex}/{@code segmentCount}.
     */
    private record PlannedPackage(LearningPathPackage reuse, LearningPathPackage template, int day,
            List<PlannedPart> parts, boolean resplit, int segmentIndex, int segmentCount) { }
    private record Schedule(Map<Long, Integer> packageDays, Map<Long, Integer> activityDays,
                            Map<Long, List<ScheduledPart>> parts, List<PlannedPackage> plan,
                            List<LearningPathPackage> surplus) {
        static Schedule empty() {
            return new Schedule(Map.of(), Map.of(), Map.of(), List.of(), List.of());
        }
    }

    private Inputs inputs(Long pathId) {
        return new Inputs(members.findByPathInStudyOrder(pathId), Set.copyOf(items.findActivityIdsWithHistory(pathId)));
    }

    private static Schedule schedule(UserLearningPath path, List<LearningPathPackage> all, List<UserLearningPathItem> activities,
            int budget, Inputs inputs) {
        // D3-05 (owner 2026-10-24): like the composer, a J-LAS compiled-book package starts in what is left of the day
        // its previous compiled package (same stage and phase) ended in; every other package keeps its own start day.
        boolean shareDays = !com.jlas.learning.service.GoalPathPolicy.isCommunicationGoal(path);
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
        List<PlannedPackage> plan = new ArrayList<>();
        List<LearningPathPackage> surplus = new ArrayList<>();
        var membership = inputs.members().stream().collect(java.util.stream.Collectors.groupingBy(m -> m.getActivity().getId()));
        // Owner 2026-10-25: a target chapter nobody has started is re-split into parts sized to the new pace (one part a
        // day); a chapter with a protected part keeps its packages and only moves to new days, as do warm-ups.
        Set<Long> protectedLessons = all.stream().filter(p -> p.getOrderIndex() <= protectedOrder && p.getSourceLesson() != null)
                .map(p -> p.getSourceLesson().getId()).collect(java.util.stream.Collectors.toSet());
        List<List<LearningPathPackage>> chapters = new ArrayList<>();
        for (var pkg : all) {
            if (pkg.getOrderIndex() <= protectedOrder) continue;
            var last = chapters.isEmpty() ? null : chapters.getLast();
            if (last != null && resplittable(last.getFirst(), protectedLessons) && resplittable(pkg, protectedLessons)
                    && last.getFirst().getSourceLesson().getId().equals(pkg.getSourceLesson().getId())) {
                last.add(pkg);
            } else {
                chapters.add(new ArrayList<>(List.of(pkg)));
            }
        }
        int used = 0;
        String previousGroup = null;
        for (var chapter : chapters) {
            var first = chapter.getFirst();
            boolean resplit = resplittable(first, protectedLessons);
            List<UserLearningPathItem> work = chapter.stream()
                    .flatMap(pkg -> grouped.getOrDefault(pkg.getId(), List.of()).stream()).toList();
            if (chapter.stream().anyMatch(pkg -> grouped.getOrDefault(pkg.getId(), List.of()).isEmpty()) && !resplit
                    || work.isEmpty()) {
                throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Bài học chưa đủ thông tin thời lượng để đổi nhịp an toàn.");
            }
            String group = shareDays && InteractivePackageComposerImpl.isCompiledChapter(first.getSourceLesson())
                    ? first.getPhase() + "|" + first.getStageId() : null;
            if (used > 0 && (group == null || !group.equals(previousGroup))) { day++; used = 0; }
            previousGroup = group;
            List<List<PlannedPart>> segments = new ArrayList<>();
            List<Integer> segmentDays = new ArrayList<>();
            List<PlannedPart> current = null;
            for (var activity : work) {
                var partition = PaceActivityPartitioner.partition(activity,
                        membership.getOrDefault(activity.getId(), List.of()), budget);
                List<ScheduledPart> scheduled = new ArrayList<>();
                for (int i = 0; i < partition.size(); i++) {
                    var part = partition.get(i);
                    if (used > 0 && used + part.minutes() > budget) {
                        day++;
                        used = 0;
                        if (resplit) current = null;
                    }
                    if (current == null) {
                        current = new ArrayList<>();
                        segments.add(current);
                        segmentDays.add(day);
                    }
                    var piece = new ScheduledPart(part, day);
                    scheduled.add(piece);
                    current.add(new PlannedPart(activity, i, piece));
                    used += part.minutes();
                }
                parts.put(activity.getId(), List.copyOf(scheduled));
                // For remaining-day preview use the LAST part, not only the original activity's start.
                activityDays.put(activity.getId(), day);
            }
            if (!resplit) {
                // One package as before: its parts may run over several days; it starts on its first part's day.
                plan.add(new PlannedPackage(first, first, segmentDays.getFirst(), segments.getFirst(), false,
                        first.getSourceSegmentIndex() == null ? 1 : first.getSourceSegmentIndex(), 0));
                packageDays.put(first.getId(), segmentDays.getFirst());
                continue;
            }
            for (int s = 0; s < segments.size(); s++) {
                var reuse = s < chapter.size() ? chapter.get(s) : null;
                plan.add(new PlannedPackage(reuse, first, segmentDays.get(s), segments.get(s), true, s + 1, segments.size()));
                if (reuse != null) packageDays.put(reuse.getId(), segmentDays.get(s));
            }
            for (int s = segments.size(); s < chapter.size(); s++) {
                surplus.add(chapter.get(s));
                packageDays.put(chapter.get(s).getId(), segmentDays.getLast());
            }
        }
        return new Schedule(packageDays, activityDays, parts, List.copyOf(plan), List.copyOf(surplus));
    }

    /** A not-started target chapter can be re-split; warm-ups and packages without a source lesson cannot. */
    private static boolean resplittable(LearningPathPackage pkg, Set<Long> protectedLessons) {
        return "GOAL_LEARNING".equals(pkg.getPhase()) && pkg.getSourceLesson() != null
                && !protectedLessons.contains(pkg.getSourceLesson().getId());
    }

    /**
     * Writes the new layout. Every activity and member keeps its row (a split adds rows for the extra parts); a re-split
     * chapter reuses its package rows in order, adds rows when it needs more parts and drops the rows it no longer needs
     * (they have no history: only not-started chapters are re-split). MySQL checks unique keys row by row, so every
     * moving package and activity is first parked beyond both layouts.
     */
    private void applySchedule(List<LearningPathPackage> all, List<UserLearningPathItem> activities, Schedule schedule) {
        if (schedule.plan().isEmpty()) return;
        Set<Long> movingPackages = new java.util.HashSet<>();
        schedule.plan().stream().map(PlannedPackage::reuse).filter(java.util.Objects::nonNull)
                .forEach(pkg -> movingPackages.add(pkg.getId()));
        schedule.surplus().forEach(pkg -> movingPackages.add(pkg.getId()));
        Set<Long> movingActivities = new java.util.HashSet<>();
        schedule.plan().forEach(planned -> planned.parts().forEach(part -> movingActivities.add(part.activity().getId())));
        int keptPackageOrder = all.stream().filter(pkg -> !movingPackages.contains(pkg.getId()))
                .mapToInt(LearningPathPackage::getOrderIndex).max().orElse(0);
        int keptActivityOrder = activities.stream().filter(activity -> !movingActivities.contains(activity.getId()))
                .mapToInt(UserLearningPathItem::getOrderIndex).max().orElse(0);

        int parking = Math.addExact(Math.max(
                all.stream().mapToInt(pkg -> Math.max(Math.max(pkg.getOrderIndex(), pkg.getDayNumber()),
                        pkg.getSourceSegmentIndex() == null ? 0 : pkg.getSourceSegmentIndex())).max().orElse(0),
                schedule.plan().stream().mapToInt(PlannedPackage::day).max().orElse(0)), schedule.plan().size() + 1);
        for (var pkg : all) {
            if (!movingPackages.contains(pkg.getId())) continue;
            pkg.setOrderIndex(parking);
            pkg.setDayNumber(parking);
            pkg.setSourceSegmentIndex(parking++);
            packages.save(pkg);
        }
        List<LearningPathPackage> targets = new ArrayList<>();
        for (var planned : schedule.plan()) {
            var pkg = planned.reuse();
            if (pkg == null) {
                var template = planned.template();
                pkg = LearningPathPackage.builder().learningPath(template.getLearningPath()).sourceLesson(template.getSourceLesson())
                        .contentRevision(template.getContentRevision()).stageId(template.getStageId()).phase(template.getPhase())
                        .status(template.getStatus()).title(template.getTitle()).estimatedMinutes(1)
                        .orderIndex(parking).dayNumber(parking).sourceSegmentIndex(parking++).build();
                // The @Version default makes Spring Data merge rather than persist: keep the managed copy it returns.
                var saved = packages.save(pkg);
                if (saved != null) pkg = saved;
            }
            targets.add(pkg);
        }
        packages.flush();

        int activityParking = Math.addExact(Math.max(activities.stream().mapToInt(activity -> Math.max(activity.getOrderIndex(),
                activity.getPartIndex() == null ? 0 : activity.getPartIndex())).max().orElse(0), keptActivityOrder),
                schedule.parts().values().stream().mapToInt(List::size).sum() + 1);
        for (var activity : activities) {
            if (!movingActivities.contains(activity.getId())) continue;
            activity.setOrderIndex(activityParking);
            activity.setPartIndex(activityParking++);
            items.save(activity);
        }
        items.flush();

        int order = keptActivityOrder;
        // uk(package, activity_type, part_index): same-type activities of one package are parts 1..n.
        Map<LearningPathPackage, Map<Object, Integer>> partIndexes = new java.util.IdentityHashMap<>();
        for (int p = 0; p < schedule.plan().size(); p++) {
            var planned = schedule.plan().get(p);
            var pkg = targets.get(p);
            int minutes = 0;
            for (var part : planned.parts()) {
                var activity = part.partNumber() == 0 ? part.activity() : copyUntouched(part.activity(), 0);
                activity.setLearningPackage(pkg);
                activity.setPartIndex(partIndexes.computeIfAbsent(pkg, ignored -> new java.util.HashMap<>())
                        .merge(String.valueOf(activity.getActivityType()), 1, Integer::sum));
                activity.setOrderIndex(++order);
                activity.setDayNumber(part.scheduled().day());
                activity.setEstimatedMinutes(part.scheduled().part().minutes());
                items.save(activity);
                for (var member : part.scheduled().part().members()) {
                    member.setActivity(activity);
                    members.save(member); // same row id, canonical id, source revision and source ordering
                }
                // Splitting can add required quiz overhead; publish the actual sum, not the old shorter estimate.
                minutes += part.scheduled().part().minutes();
            }
            pkg.setEstimatedMinutes(minutes);
        }
        items.flush();
        schedule.surplus().forEach(packages::delete);
        packages.flush();

        int packageOrder = keptPackageOrder;
        for (int p = 0; p < schedule.plan().size(); p++) {
            var planned = schedule.plan().get(p);
            var pkg = targets.get(p);
            pkg.setOrderIndex(++packageOrder);
            pkg.setDayNumber(planned.day());
            pkg.setSourceSegmentIndex(planned.segmentIndex());
            if (planned.resplit()) {
                // The chapter's first package is renumbered first; stripping the part suffix gives its title back.
                String base = baseTitle(planned.template().getTitle());
                pkg.setTitle(planned.segmentCount() == 1 ? base
                        : base + " · Phần " + planned.segmentIndex() + "/" + planned.segmentCount());
            }
            packages.save(pkg);
        }
    }

    /** "Bài 3 · Phần 2/5" → "Bài 3": the chapter title a re-split numbers again. */
    private static String baseTitle(String title) {
        return title == null ? "" : title.replaceFirst("\\s·\\sPhần\\s\\d+/\\d+$", "");
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
