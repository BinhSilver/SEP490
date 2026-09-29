package com.jlas.learning.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.CoverageGap;
import com.jlas.learning.dto.LearningPathItemDto;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.dto.LearningPhaseGroup;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningPhase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** Maps persisted learning-path entities into the API read model and (de)serializes JSON snapshots. */
@Component
public class LearningMapper {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<CoverageGap>> GAP_LIST = new TypeReference<>() { };

    // The app does not expose a Spring-managed ObjectMapper (webmvc starter without starter-json),
    // so this mapper owns its own instance for the small JSON snapshot columns it persists.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public LearningPathResponse toResponse(UserLearningPath path, List<UserLearningPathItem> items,
            Set<Long> completedLessonIds) {
        Map<LearningPhase, List<UserLearningPathItem>> byPhase = new LinkedHashMap<>();
        for (var item : items) {
            byPhase.computeIfAbsent(LearningPhase.valueOf(item.getPhase()), key -> new ArrayList<>()).add(item);
        }
        List<LearningPhaseGroup> groups = new ArrayList<>();
        int totalLessons = 0;
        int totalMinutes = 0;
        int completed = 0;
        for (var entry : byPhase.entrySet()) {
            List<LearningPathItemDto> dtos = entry.getValue().stream()
                    .map(item -> toItem(item, completedLessonIds.contains(item.getLesson().getId())))
                    .toList();
            int minutes = dtos.stream().mapToInt(LearningPathItemDto::estimatedMinutes).sum();
            long dayCount = dtos.stream().mapToInt(LearningPathItemDto::dayNumber).distinct().count();
            groups.add(new LearningPhaseGroup(entry.getKey(), dtos, dtos.size(), minutes, (int) dayCount));
            totalLessons += dtos.size();
            totalMinutes += minutes;
        }
        completed = (int) items.stream().filter(item -> completedLessonIds.contains(item.getLesson().getId())).count();
        int totalDays = items.stream().mapToInt(UserLearningPathItem::getDayNumber).max().orElse(0);
        return new LearningPathResponse(path.getId(), path.getUser().getId(), path.getGoal(), path.getTargetLevel(),
                path.getCurrentLevel(), path.getMinutesPerDay(), path.getStatus(), path.getVersion(), path.getCreatedAt(),
                groups, readCoverageGaps(path.getCoverageGapsJson()),
                totalLessons, totalMinutes, totalDays, completed);
    }

    private LearningPathItemDto toItem(UserLearningPathItem item, boolean completed) {
        var lesson = item.getLesson();
        Long textbookId = null;
        Long unitId = null;
        String level = null;
        if (lesson.getUnit() != null) {
            unitId = lesson.getUnit().getId();
            if (lesson.getUnit().getTextbook() != null) {
                textbookId = lesson.getUnit().getTextbook().getId();
                level = lesson.getUnit().getTextbook().getJlptLevel();
            }
        }
        return new LearningPathItemDto(lesson.getId(), lesson.getTitle(), textbookId, unitId, level,
                item.getReasonCode(), item.getReason(), readMatchedTags(item.getMatchedTagCodes()),
                item.getDayNumber(), item.getOrderIndex(), item.getEstimatedMinutes(), completed);
    }

    public String writeMatchedTags(Set<String> tags) {
        try {
            return OBJECT_MAPPER.writeValueAsString(new TreeSet<>(tags));
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to serialize matched tags.");
        }
    }

    public String writeCoverageGaps(List<CoverageGap> gaps) {
        try {
            return OBJECT_MAPPER.writeValueAsString(gaps);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to serialize coverage gaps.");
        }
    }

    private List<String> readMatchedTags(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OBJECT_MAPPER.readValue(json, STRING_LIST);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to read matched tags snapshot.");
        }
    }

    private List<CoverageGap> readCoverageGaps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OBJECT_MAPPER.readValue(json, GAP_LIST);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to read coverage gaps snapshot.");
        }
    }
}
