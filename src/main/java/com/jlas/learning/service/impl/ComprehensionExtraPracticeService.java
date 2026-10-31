package com.jlas.learning.service.impl;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.assessment.repository.SmartTestSkillResultRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.learning.CompiledBookPolicy;
import com.jlas.learning.dto.ExtraPracticeIssue;
import com.jlas.learning.dto.ExtraPracticeSuggestions;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import com.jlas.learning.service.GoalPathPolicy;
import com.jlas.learning.service.impl.engine.AssessmentComprehensionContentAdapter;
import com.jlas.learning.service.impl.engine.ComprehensionContent;
import com.jlas.learning.service.impl.engine.ComprehensionSourceSnapshot;
import com.jlas.learning.service.impl.engine.InteractiveStudyEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * §2.32 (owner 2026-10-26) — "Luyện thêm": per-learner extra Reading/Listening passages beside a JLPT compiled-book
 * chapter's required activity, practised as {@code EXTRA_PRACTICE} quiz sessions. Ranking is {@link ExtraPracticeSelection};
 * this class gathers the learner's signals and guards the session: the passage must still be one of the learner's
 * suggestions, and nothing here scores, unlocks, rewards or completes anything.
 */
@Service
public class ComprehensionExtraPracticeService {
    static final int RECENT_DAYS = 14;
    private static final String SUBMITTED = "SUBMITTED";

    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final LearningQuizSessionRepository quizSessions;
    private final AssessmentQuestionRepository questions;
    private final SmartTestSkillResultRepository skillResults;
    private final LearningMapper mapper;
    private final AssessmentComprehensionContentAdapter readingAdapter;
    private final AssessmentComprehensionContentAdapter listeningAdapter;
    private final InteractiveStudyEngine<ComprehensionContent> readingEngine;
    private final InteractiveStudyEngine<ComprehensionContent> listeningEngine;

    public ComprehensionExtraPracticeService(UserLearningPathItemRepository items,
            LearningActivityMemberRepository members, LearningStudySessionRepository studySessions,
            LearningStudyCardRepository studyCards, LearningMicroCheckStateRepository checkStates,
            LearningQuizSessionRepository quizSessions, AssessmentQuestionRepository questions,
            SmartTestSkillResultRepository skillResults, ComprehensionExerciseCatalog catalog, LearningMapper mapper) {
        this.items = items;
        this.members = members;
        this.quizSessions = quizSessions;
        this.questions = questions;
        this.skillResults = skillResults;
        this.mapper = mapper;
        this.readingAdapter = new AssessmentComprehensionContentAdapter(LearningActivityType.READING, questions, catalog);
        this.listeningAdapter = new AssessmentComprehensionContentAdapter(LearningActivityType.LISTENING, questions, catalog);
        this.readingEngine = new InteractiveStudyEngine<>(items, members, studySessions, studyCards,
                checkStates, quizSessions, mapper, readingAdapter);
        this.listeningEngine = new InteractiveStudyEngine<>(items, members, studySessions, studyCards,
                checkStates, quizSessions, mapper, listeningAdapter);
    }

    /** The activity's place in the store: its skill, the compiled book's level and the chapter it was placed in. */
    private record Chapter(UserLearningPathItem activity, String skill, String level, int number,
            List<AssessmentQuestion> ownRows) { }

    @Transactional(readOnly = true)
    public ExtraPracticeSuggestions suggestions(Long userId, Long activityId) {
        UserLearningPathItem activity = ownedComprehension(userId, activityId);
        Chapter chapter = chapterOf(activity);
        String skill = activity.getActivityType().name();
        if (chapter == null) {
            return new ExtraPracticeSuggestions(activityId, skill, null, List.of(), "Bài này chưa có phần luyện thêm.");
        }
        boolean focus = InteractiveLearningPathServiceImpl.focusSkills(activity.getLearningPath()).contains(skill);
        List<ExtraPracticeSuggestions.Item> list = select(chapter, userId, focus).stream()
                .map(s -> new ExtraPracticeSuggestions.Item(s.group(), s.chapterHint(), s.difficulty(), s.minutes(),
                        s.questionIds().size(), s.reasons(), s.label()))
                .toList();
        return new ExtraPracticeSuggestions(activityId, skill, chapter.number(), list,
                list.isEmpty() ? "Bạn đã làm hết bài luyện thêm của chương này." : null, focus);
    }

    @Transactional
    public ExtraPracticeIssue start(Long userId, Long activityId, String passage) {
        UserLearningPathItem activity = ownedComprehension(userId, activityId);
        Chapter chapter = chapterOf(activity);
        if (chapter == null) throw new ApiException(ErrorCode.NOT_FOUND, "Bài này chưa có phần luyện thêm.");
        boolean focus = InteractiveLearningPathServiceImpl.focusSkills(activity.getLearningPath())
                .contains(activity.getActivityType().name());
        ExtraPracticeSelection.Suggestion chosen = select(chapter, userId, focus).stream()
                .filter(s -> s.group().equals(passage)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Bài này không còn trong danh sách luyện thêm."));
        var adapter = adapter(activity.getActivityType());
        Map<Long, ComprehensionContent> loaded = adapter.loadByIds(chosen.questionIds());
        List<ComprehensionContent> ordered = chosen.questionIds().stream().map(loaded::get).toList();
        if (ordered.stream().anyMatch(Objects::isNull) || adapter.partition(ordered).size() != 1) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Nội dung bài luyện thêm chưa sẵn sàng.");
        }
        var issued = engine(activity.getActivityType())
                .issueExtraPractice(userId, activityId, ordered, adapter.currentRevision(ordered));
        var source = ComprehensionSourceSnapshot.parse(issued.sourceSnapshotJson())
                .orElseThrow(() -> new ApiException(ErrorCode.BLOCKED_CONTENT, "Không dựng được nguồn bài luyện thêm."));
        var response = issued.response();
        return new ExtraPracticeIssue(response.attemptId(), activityId, chosen.group(), source, response.questions(),
                chosen.label());
    }

    @Transactional
    public MicroCheckResult submit(Long userId, Long activityId, MicroCheckSubmitRequest request) {
        return engine(ownedComprehension(userId, activityId).getActivityType())
                .submitExtraPractice(userId, activityId, request);
    }

    private List<ExtraPracticeSelection.Suggestion> select(Chapter chapter, Long userId, boolean focus) {
        UserLearningPathItem activity = chapter.activity();
        LearningActivityType type = activity.getActivityType();
        String microCheck = type.name() + "_MICRO_CHECK";
        List<AssessmentQuestion> store = questions.findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                chapter.skill(), chapter.level(), "LP-OWNED-JLAS-" + chapter.level() + "-");

        List<UserLearningPathItem> sameSkill = items.findByLearningPathIdOrderByOrderIndex(activity.getLearningPath().getId())
                .stream().filter(item -> item.getActivityType() == type).toList();
        Set<Long> sameSkillIds = new HashSet<>();
        sameSkill.forEach(item -> sameSkillIds.add(item.getId()));

        // Never suggest the learner's own required content that is still ahead, nor this activity's passages.
        List<Long> ahead = sameSkill.stream().filter(item -> item.getId().equals(activity.getId()) || !finished(item))
                .map(UserLearningPathItem::getId).toList();
        Set<Long> excludedIds = new HashSet<>();
        if (!ahead.isEmpty()) {
            members.findByActivityIdsInOrder(ahead).stream()
                    .filter(member -> member.getMemberType() == LearningMemberType.COMPREHENSION_QUESTION)
                    .forEach(member -> excludedIds.add(member.getCanonicalId()));
        }
        // Nor a passage done (checked, replayed or practised) in the last 14 days on this path.
        for (LearningQuizSession done : quizSessions.findByUserIdAndStatusAndSubmittedAtAfter(userId, SUBMITTED,
                LocalDateTime.now().minusDays(RECENT_DAYS))) {
            if (!sameSkillIds.contains(done.getActivityId()) || done.getQuestionsJson() == null) continue;
            String quizType = done.getQuizType();
            if (!microCheck.equals(quizType) && !InteractiveStudyEngine.PRACTICE_REPLAY.equals(quizType)
                    && !InteractiveStudyEngine.EXTRA_PRACTICE.equals(quizType)) continue;
            mapper.readQuizQuestions(done.getQuestionsJson()).stream()
                    .map(LessonQuizResponse.LessonQuizQuestion::questionId).forEach(excludedIds::add);
        }
        Set<String> excludedGroups = new HashSet<>();
        for (AssessmentQuestion row : store) {
            if (excludedIds.contains(row.getId())) {
                String group = ExtraPracticeSelection.group(row, chapter.skill(), chapter.level());
                if (group != null) excludedGroups.add(group);
            }
        }

        Long packageId = activity.getLearningPackage() == null ? null : activity.getLearningPackage().getId();
        List<Long> chapterActivities = sameSkill.stream().filter(item -> item.getLearningPackage() != null
                && Objects.equals(item.getLearningPackage().getId(), packageId)).map(UserLearningPathItem::getId).toList();
        Set<Long> wrong = latestWrongAnswers(chapterActivities, microCheck);
        int target = ExtraPracticeSelection.targetDifficulty(referencePercent(userId, activity, microCheck, sameSkillIds),
                ExtraPracticeSelection.averageDifficulty(chapter.ownRows(), chapter.skill(), chapter.level()));
        return ExtraPracticeSelection.select(store, chapter.skill(), chapter.level(), chapter.number(), excludedGroups,
                wrong, target, focus ? ExtraPracticeSelection.FOCUS_LIMIT : ExtraPracticeSelection.LIMIT);
    }

    /** Questions answered wrong in the newest submitted check of every batch of the chapter's activities of this skill. */
    private Set<Long> latestWrongAnswers(List<Long> chapterActivities, String microCheck) {
        Set<Long> wrong = new HashSet<>();
        if (chapterActivities.isEmpty()) return wrong;
        Set<String> seen = new HashSet<>();
        for (LearningQuizSession session : quizSessions.findByActivityIdInAndQuizTypeAndStatusOrderBySubmittedAtDesc(
                chapterActivities, microCheck, SUBMITTED)) {
            if (session.getResultJson() == null || !seen.add(session.getActivityId() + ":" + session.getBatchIndex())) continue;
            LessonQuizResult result = mapper.readQuizResult(session.getResultJson());
            result.review().stream().filter(review -> !review.correct())
                    .forEach(review -> wrong.add(review.questionId()));
        }
        return wrong;
    }

    /** §2.32 step 2: the average of the skill's two latest checks on this path, else the Smart Test skill score, else null. */
    private Integer referencePercent(Long userId, UserLearningPathItem activity, String microCheck, Set<Long> sameSkillIds) {
        List<LearningQuizSession> latest = sameSkillIds.isEmpty() ? List.of()
                : quizSessions.findTop2ByUserIdAndQuizTypeAndStatusAndActivityIdInOrderBySubmittedAtDesc(
                        userId, microCheck, SUBMITTED, sameSkillIds);
        var scores = latest.stream().map(LearningQuizSession::getScorePercent).filter(Objects::nonNull).toList();
        if (!scores.isEmpty()) {
            return (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));
        }
        Long attemptId = activity.getLearningPath().getAssessmentAttemptId();
        if (attemptId == null) return null;
        return skillResults.findBySessionAttemptIdOrderByIdSkillAsc(attemptId).stream()
                .filter(result -> result.getId() != null && activity.getActivityType().name().equals(result.getId().getSkill()))
                .map(result -> result.getMasteryScore() == null ? null : result.getMasteryScore().intValue())
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** Null unless every question of the activity is a compiled-book copy of one level and one chapter. */
    private Chapter chapterOf(UserLearningPathItem activity) {
        if (GoalPathPolicy.isCommunicationGoal(activity.getLearningPath())) return null;
        List<Long> ids = members.findByActivityIdOrderByOrderIndex(activity.getId()).stream()
                .filter(member -> member.getMemberType() == LearningMemberType.COMPREHENSION_QUESTION)
                .map(LearningActivityMember::getCanonicalId).toList();
        if (ids.isEmpty()) return null;
        List<AssessmentQuestion> rows = questions.findAllById(ids);
        Set<String> levels = new HashSet<>();
        Set<Integer> chapters = new HashSet<>();
        for (AssessmentQuestion row : rows) {
            levels.add(CompiledBookPolicy.levelOfQuestion(row.getSourceCode()));
            chapters.add(CompiledBookPolicy.chapterOfQuestion(row.getSourceCode()).orElse(null));
        }
        if (rows.size() != ids.size() || levels.size() != 1 || chapters.size() != 1
                || levels.contains(null) || chapters.contains(null)) return null;
        return new Chapter(activity, activity.getActivityType().name(), levels.iterator().next(),
                chapters.iterator().next(), rows);
    }

    private UserLearningPathItem ownedComprehension(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findByIdAndUserId(activityId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (activity.getActivityType() != LearningActivityType.READING
                && activity.getActivityType() != LearningActivityType.LISTENING) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        InteractiveActivityStatePolicy.requireUnlocked(activity);
        return activity;
    }

    private static boolean finished(UserLearningPathItem item) {
        return item.getActivityState() == LearningActivityState.PASSED
                || item.getActivityState() == LearningActivityState.COMPLETED;
    }

    private InteractiveStudyEngine<ComprehensionContent> engine(LearningActivityType type) {
        return type == LearningActivityType.READING ? readingEngine : listeningEngine;
    }

    private AssessmentComprehensionContentAdapter adapter(LearningActivityType type) {
        return type == LearningActivityType.READING ? readingAdapter : listeningAdapter;
    }
}
