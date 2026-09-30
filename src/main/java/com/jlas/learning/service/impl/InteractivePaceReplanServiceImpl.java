package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.LearningPacePreviewResponse;
import com.jlas.learning.dto.LearningPaceRequest;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Task 10 - pace replan. Only {@code dayNumber} of unfinished packages (and their activities) and
 * the path's {@code minutesPerDay} change; ids, membership, completion, scores and target level are untouched.
 * The preview token fingerprints the layout the learner saw, so a stale confirm is a CONFLICT while a repeated
 * confirm of an already-applied pace is a harmless no-op. */
@Service
public class InteractivePaceReplanServiceImpl implements InteractivePaceReplanService {
    static final int[] PRESETS = {15, 20, 30, 45, 60};
    private static final int DEFAULT_MINUTES = 30;

    private final UserLearningPathRepository paths;
    private final LearningPathPackageRepository packages;
    private final UserLearningPathItemRepository items;

    public InteractivePaceReplanServiceImpl(UserLearningPathRepository paths, LearningPathPackageRepository packages,
            UserLearningPathItemRepository items) {
        this.paths = paths;
        this.packages = packages;
        this.items = items;
    }

    public record Slot(Long id, int minutes) { }

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

    /** Packs packages in order into days of {@code budget} minutes starting at {@code startDay}. A package that
     * fits in a fresh day never straddles two days; one longer than a day spills over as many as it needs and
     * the next package continues in the spill day. */
    public static Map<Long, Integer> layout(List<Slot> slots, int budget, int startDay) {
        Map<Long, Integer> days = new LinkedHashMap<>();
        int day = startDay;
        int used = 0;
        for (Slot slot : slots) {
            if (used > 0 && slot.minutes() <= budget && used + slot.minutes() > budget) {
                day++;
                used = 0;
            }
            days.put(slot.id(), day);
            used += slot.minutes();
            while (used > budget) {
                day++;
                used -= budget;
            }
            if (used == budget) {
                day++;
                used = 0;
            }
        }
        return days;
    }

    @Override
    @Transactional(readOnly = true)
    public LearningPacePreviewResponse preview(Long userId, String direction) {
        UserLearningPath path = paths.findByUserIdAndStatusAndExperienceVersion(
                        userId, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình đang hoạt động."));
        return describe(path, direction, packages.findByLearningPathIdOrderByOrderIndex(path.getId()));
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
        List<LearningPathPackage> all = packages.findByLearningPathIdOrderByOrderIndex(path.getId());
        int target = request.newMinutesPerDay();
        boolean alreadyApplied = currentMinutes(path) == target && layoutMatches(all, target);
        LearningPacePreviewResponse now = alreadyApplied
                ? describe(path, "SAME", all) : describe(path, request.direction(), all);
        if (!alreadyApplied && (now.newMinutesPerDay() != target
                || !now.previewToken().equals(request.previewToken()))) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Tiến độ đã thay đổi kể từ lúc xem trước. Hãy xem lại rồi xác nhận.");
        }
        if (alreadyApplied || !now.changed()) {
            return new LearningPacePreviewResponse(now.direction(), now.currentMinutesPerDay(), target, false,
                    now.remainingPackages(), now.newRemainingDays(), now.newRemainingDays(), now.previewToken());
        }

        Map<Long, Integer> days = layout(slotsOf(all), target, startDay(all));
        for (LearningPathPackage pkg : all) {
            Integer day = days.get(pkg.getId());
            if (day != null) {
                pkg.setDayNumber(day);
                packages.save(pkg);
            }
        }
        for (UserLearningPathItem item : items.findByLearningPathIdOrderByOrderIndex(path.getId())) {
            if (item.getLearningPackage() == null) continue;
            Integer day = days.get(item.getLearningPackage().getId());
            if (day != null) {
                item.setDayNumber(day);
                items.save(item);
            }
        }
        path.setMinutesPerDay(target);
        paths.save(path);
        return new LearningPacePreviewResponse(now.direction(), now.currentMinutesPerDay(), target, true,
                now.remainingPackages(), now.currentRemainingDays(), now.newRemainingDays(), now.previewToken());
    }

    private LearningPacePreviewResponse describe(UserLearningPath path, String direction,
            List<LearningPathPackage> all) {
        int current = currentMinutes(path);
        int target = nextPreset(current, direction);
        List<Slot> slots = slotsOf(all);
        int start = startDay(all);
        Map<Long, Integer> currentLayout = layout(slots, current, start);
        Map<Long, Integer> newLayout = layout(slots, target, start);
        return new LearningPacePreviewResponse(direction, current, target, target != current, slots.size(),
                span(currentLayout, start), span(newLayout, start), token(path, direction, current, target, all));
    }

    private boolean layoutMatches(List<LearningPathPackage> all, int budget) {
        Map<Long, Integer> expected = layout(slotsOf(all), budget, startDay(all));
        for (LearningPathPackage pkg : all) {
            Integer day = expected.get(pkg.getId());
            if (day != null && !day.equals(pkg.getDayNumber())) return false;
        }
        return true;
    }

    private static int currentMinutes(UserLearningPath path) {
        return path.getMinutesPerDay() == null ? DEFAULT_MINUTES : path.getMinutesPerDay();
    }

    private static List<Slot> slotsOf(List<LearningPathPackage> all) {
        return all.stream().filter(p -> p.getStatus() != LearningPackageStatus.COMPLETED)
                .map(p -> new Slot(p.getId(),
                        p.getEstimatedMinutes() == null ? DEFAULT_MINUTES : p.getEstimatedMinutes()))
                .toList();
    }

    private static int startDay(List<LearningPathPackage> all) {
        return all.stream().filter(p -> p.getStatus() == LearningPackageStatus.COMPLETED)
                .map(LearningPathPackage::getDayNumber).filter(d -> d != null)
                .mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    private static int span(Map<Long, Integer> layout, int start) {
        return layout.isEmpty() ? 0 : layout.values().stream().mapToInt(Integer::intValue).max().getAsInt() - start + 1;
    }

    private static String token(UserLearningPath path, String direction, int current, int target,
            List<LearningPathPackage> all) {
        StringBuilder text = new StringBuilder().append(path.getId()).append("|").append(path.getVersion())
                .append("|").append(direction).append("|").append(current).append(">").append(target);
        for (LearningPathPackage pkg : all) {
            if (pkg.getStatus() == LearningPackageStatus.COMPLETED) continue;
            text.append("|").append(pkg.getId()).append(":").append(pkg.getStatus()).append(":")
                    .append(pkg.getDayNumber());
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
