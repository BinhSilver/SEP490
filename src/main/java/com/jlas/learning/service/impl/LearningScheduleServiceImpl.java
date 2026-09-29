package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.dto.SelectedLearningResource;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.service.LearningScheduleService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Packs resources into days without splitting a lesson. Warm-up runs first; the goal
 * phase always starts on a fresh day. A lesson longer than the budget takes a whole day.
 * <p>
 * [JLAS-W0108] Warm-up chia theo kỹ năng để mỗi ngày cân bằng: tối đa 3 mẫu ngữ pháp
 * ({@code WEAK_GRAMMAR_POINT}), 1 bài đọc ({@code WEAK_READING_ITEM}) và 1 bài nghe
 * ({@code WEAK_LISTENING_ITEM}) mỗi ngày — trên nền quy tắc ngân sách phút hiện có.
 */
@Component
public class LearningScheduleServiceImpl implements LearningScheduleService {

    private static final String REASON_GRAMMAR_POINT = "WEAK_GRAMMAR_POINT";
    private static final String REASON_READING = "WEAK_READING_ITEM";
    private static final String REASON_LISTENING = "WEAK_LISTENING_ITEM";
    private static final int MAX_GRAMMAR_PER_DAY = 3;
    /** [JLAS-W0108] P3 — tổng bài đọc + nghe tối đa mỗi ngày (cho phép 2 đọc hoặc 2 nghe, không bắt 1+1). */
    private static final int MAX_COMPREHENSION_PER_DAY = 2;

    @Override
    public List<ScheduledLearningResource> schedule(List<SelectedLearningResource> resources, int minutesPerDay) {
        if (minutesPerDay <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Daily study budget must be positive.");
        }
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        var scheduled = new ArrayList<ScheduledLearningResource>();
        int day = 0;
        int dayMinutes = 0;
        LearningPhase currentPhase = null;
        int order = 0;
        int grammarInDay = 0;
        int readingInDay = 0;
        int listeningInDay = 0;
        for (var selection : resources) {
            int minutes = selection.resource().estimatedMinutes();
            String reason = selection.reasonCode();
            if (currentPhase != selection.phase()) {
                day++;
                dayMinutes = 0;
                currentPhase = selection.phase();
                grammarInDay = 0;
                readingInDay = 0;
                listeningInDay = 0;
            } else if (dayMinutes > 0 && dayMinutes + minutes > minutesPerDay) {
                day++;
                dayMinutes = 0;
                grammarInDay = 0;
                readingInDay = 0;
                listeningInDay = 0;
            } else if (exceedsDailySkillQuota(reason, grammarInDay, readingInDay, listeningInDay)) {
                day++;
                dayMinutes = 0;
                grammarInDay = 0;
                readingInDay = 0;
                listeningInDay = 0;
            }
            dayMinutes += minutes;
            if (REASON_GRAMMAR_POINT.equals(reason)) grammarInDay++;
            if (REASON_READING.equals(reason)) readingInDay++;
            if (REASON_LISTENING.equals(reason)) listeningInDay++;
            scheduled.add(new ScheduledLearningResource(selection.resource(), selection.phase(),
                    selection.reasonCode(), selection.reasonText(), selection.matchedTagCodes(),
                    day, ++order, minutes));
        }
        return List.copyOf(scheduled);
    }

    /** [JLAS-W0108] Đã chạm trần kỹ năng trong ngày (3 ngữ pháp; tổng đọc+nghe tối đa 2) thì sang ngày mới. */
    private static boolean exceedsDailySkillQuota(String reason, int grammarInDay,
            int readingInDay, int listeningInDay) {
        if (REASON_GRAMMAR_POINT.equals(reason)) return grammarInDay >= MAX_GRAMMAR_PER_DAY;
        if (REASON_READING.equals(reason) || REASON_LISTENING.equals(reason)) {
            return readingInDay + listeningInDay >= MAX_COMPREHENSION_PER_DAY;
        }
        return false;
    }
}
