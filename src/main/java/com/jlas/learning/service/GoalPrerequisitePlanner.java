package com.jlas.learning.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.learning.entity.LessonPrerequisite;
import com.jlas.learning.repository.LessonPrerequisiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Applies reviewed dependency rules without turning recommendations into access gates. */
@Service
@RequiredArgsConstructor
public class GoalPrerequisitePlanner {
    private final LessonPrerequisiteRepository prerequisites;
    private final LessonRepository lessons;

    @Transactional(readOnly = true)
    public List<Lesson> plan(List<Lesson> selected) {
        if (selected == null || selected.isEmpty()) return List.of();
        LinkedHashMap<Long, Lesson> byId = selected.stream().collect(Collectors.toMap(
                Lesson::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<Long> selectedIds = new ArrayList<>(byId.keySet());
        List<LessonPrerequisite> rules = prerequisites
                .findByLessonIdInAndReviewStatus(selectedIds, "APPROVED");

        for (LessonPrerequisite rule : rules) {
            if ("HARD_SEQUENCE".equals(rule.getPrerequisiteType()) && rule.getRequiredLessonId() != null
                    && !byId.containsKey(rule.getRequiredLessonId())) {
                throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                        "Lộ trình thiếu bài học tiên quyết bắt buộc đã được duyệt.");
            }
        }

        List<Long> warmupIds = rules.stream()
                .filter(rule -> "JIT_WARMUP".equals(rule.getPrerequisiteType()))
                .map(LessonPrerequisite::getWarmupLessonId).filter(Objects::nonNull)
                .filter(id -> !byId.containsKey(id)).distinct().toList();
        if (!warmupIds.isEmpty()) {
            Map<Long, Lesson> published = lessons.findAllById(warmupIds).stream()
                    .filter(lesson -> "PUBLISHED".equals(lesson.getStatus()))
                    .collect(Collectors.toMap(Lesson::getId, Function.identity()));
            if (!published.keySet().containsAll(warmupIds)) {
                throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                        "Bài ôn nhanh của lộ trình chưa được xuất bản.");
            }
            published.forEach(byId::putIfAbsent);
        }

        Map<Long, Set<Long>> before = new HashMap<>();
        byId.keySet().forEach(id -> before.put(id, new LinkedHashSet<>()));
        for (LessonPrerequisite rule : rules) {
            if ("HARD_SEQUENCE".equals(rule.getPrerequisiteType()) && rule.getRequiredLessonId() != null) {
                before.get(rule.getLessonId()).add(rule.getRequiredLessonId());
            } else if ("JIT_WARMUP".equals(rule.getPrerequisiteType()) && rule.getWarmupLessonId() != null) {
                before.get(rule.getLessonId()).add(rule.getWarmupLessonId());
            }
        }

        List<Lesson> ordered = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        while (ordered.size() < byId.size()) {
            boolean progressed = false;
            for (var entry : byId.entrySet()) {
                if (!done.contains(entry.getKey()) && done.containsAll(before.get(entry.getKey()))) {
                    done.add(entry.getKey()); ordered.add(entry.getValue()); progressed = true;
                }
            }
            if (!progressed) throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Cấu hình bài học tiên quyết tạo thành vòng lặp.");
        }
        return List.copyOf(ordered);
    }
}
