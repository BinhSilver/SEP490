package com.jlas.learning.service;

import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.learning.dto.LearningResourceCandidate;
import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Creates the immutable-survey-backed Release-1 BA/Travel/Daily interactive path. */
@Service
@RequiredArgsConstructor
public class GoalSpecificPathService {
    private static final Set<String> SUPPORTED = Set.of("BA", "TRAVEL", "DAILY_LIFE");
    private final UserRepository users;
    private final LearningGoalRepository goals;
    private final UserLearningPathRepository paths;
    private final UserLearningStageRepository stages;
    private final GoalLessonSelectionService selection;
    private final GoalPrerequisitePlanner prerequisites;
    private final BrseFoundationQualificationPort foundation;
    private final InteractivePackageComposer composer;

    @Transactional
    public Result create(Long userId, Long submissionId, Long assessmentAttemptId) {
        var selected = selection.select(userId, submissionId);
        LearningGoal goal = goals.findById(selected.goalId())
                .filter(LearningGoal::isActive)
                .filter(row -> "APPROVED".equals(row.getReviewStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.BLOCKED_CONTENT,
                        "Mục tiêu học chưa được duyệt để phát hành."));
        if (!SUPPORTED.contains(goal.getGoalCode())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mục tiêu không hỗ trợ lộ trình theo survey.");
        }

        String assessedLevel = null;
        Integer dailyMinutes = null;
        if ("BA".equals(goal.getGoalCode())) {
            var qualification = foundation.verify(userId, assessmentAttemptId);
            if (!qualification.qualified()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Kết quả Japanese Foundation chưa hợp lệ: " + qualification.reasonCode());
            }
            assessedLevel = qualification.assessedLevel();
            dailyMinutes = qualification.dailyStudyMinutes();
        } else if (assessmentAttemptId != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Travel và Daily Life chỉ dùng survey, không nhận assessment attempt.");
        }

        var existing = paths.findByUserIdAndSurveyIdAndExperienceVersion(
                userId, submissionId, LearningExperienceVersion.INTERACTIVE_V2);
        if (existing.isPresent()) return new Result(existing.get().getId(), false, goal.getGoalCode());

        var user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy người học."));
        paths.findByUserIdAndStatus(userId, "ACTIVE").ifPresent(active -> {
            active.setStatus("SUPERSEDED");
            paths.save(active);
        });

        UserLearningPath path = paths.save(UserLearningPath.builder()
                .user(user).title("Lộ trình " + goal.getDisplayNameVi()).goal(goal.getGoalCode())
                .learningMode(selected.learningMode()).surveyId(submissionId)
                .assessmentAttemptId("BA".equals(goal.getGoalCode()) ? assessmentAttemptId : null)
                .assessmentRevision("goal-survey-v1").policyVersion("GOAL_PATH_R1")
                .currentLevel(assessedLevel).minutesPerDay(dailyMinutes != null ? dailyMinutes : 20)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2)
                .status("ACTIVE").version(paths.findMaxVersionByUserId(userId) + 1).build());

        List<Lesson> ordered = prerequisites.plan(selected.lessons());
        if (ordered.isEmpty()) throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                "Chưa có bài học đã duyệt cho nhu cầu này.");
        LinkedHashSet<String> levels = new LinkedHashSet<>();
        ordered.forEach(lesson -> levels.add(selected.levelByLesson().getOrDefault(lesson.getId(), "BASIC")));
        int stageIndex = 0;
        for (String level : levels) {
            stages.save(UserLearningStage.builder().learningPath(path).level(level).stageIndex(stageIndex)
                    .curriculumVersion("goal-scope-v1").assessmentCycle("R1")
                    .status(stageIndex++ == 0 ? "ACTIVE" : "PENDING").build());
        }

        List<ScheduledLearningResource> schedule = new ArrayList<>();
        int day = 0;
        for (Lesson lesson : ordered) {
            String level = selected.levelByLesson().getOrDefault(lesson.getId(), "BASIC");
            int minutes = lesson.getEstimatedMinutes() == null ? 20 : Math.max(1, lesson.getEstimatedMinutes());
            var resource = new LearningResourceCandidate(lesson.getId(), lesson.getTitle(),
                    lesson.getUnit().getTextbook().getId(), lesson.getUnit().getId(), Set.of(), level, minutes,
                    lesson.getUnit().getOrderIndex(), lesson.getOrderIndex(), null, null, lesson.getSkill());
            schedule.add(new ScheduledLearningResource(resource, LearningPhase.GOAL_LEARNING,
                    "GOAL_SURVEY_MATCH", "Phù hợp nhu cầu đã chọn", Set.of(), ++day, day, minutes));
        }
        var composition = composer.compose(path, schedule);
        if (!composition.published()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Học liệu của lộ trình chưa đủ để phát hành.");
        }
        return new Result(path.getId(), true, goal.getGoalCode());
    }

    public record Result(Long pathId, boolean created, String goalCode) { }
}
