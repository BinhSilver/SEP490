package com.jlas.learning.service.impl;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.enums.JlptLevel;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.learning.ai.LearningPathPlanGenerator;
import com.jlas.learning.ai.LearningQuizGenerator;
import com.jlas.learning.dto.CreateLearningPathResult;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.LearningSelectionResult;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.LessonQuizSubmitRequest;
import com.jlas.learning.dto.QuizAnswerKey;
import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.LearningGeneratedQuestion;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.entity.LessonProgress;
import com.jlas.learning.entity.QuizAttempt;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LessonProgressRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.QuizAttemptRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.LearningResourceSelectionService;
import com.jlas.learning.service.LearningScheduleService;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.LearningService;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cài đặt {@link LearningService}. */
@Service
@RequiredArgsConstructor
public class LearningServiceImpl implements LearningService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.learning.service.WarmUpFlashcardService warmUpFlashcards;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserStreaksRepository streakRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserCreditService creditService;
    // [JLAS-W0108] Tầng AI Gemini + repo câu quiz sinh. Optional để slice test (DataJpaTest)
    // không cần dựng bean @Component; lúc đó gate quiz/route vẫn chạy fallback không-Gemini.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LearningPathPlanGenerator planGenerator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LearningQuizGenerator quizGenerator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.learning.repository.LearningGeneratedQuestionRepository generatedQuestions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.dictionary.repository.GrammarPointRepository grammarPoints;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.dictionary.repository.ContentVocabularyRepository contentVocabularies;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.dictionary.repository.VocabularyEntryRepository vocabularyEntries;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.dictionary.repository.TagRepository tags;
    private final UserRepository users;
    private final UserLearningPathRepository paths;
    private final UserLearningPathItemRepository items;
    private final LessonProgressRepository progress;
    private final LessonRepository lessons;
    private final AssessmentQuestionRepository questionRepository;
    private final QuizAttemptRepository quizAttempts;
    private final LearningQuizSessionRepository quizSessions;
    private final ContentItemRepository contentItems;
    private final LearningResourceSelectionService selector;
    private final LearningScheduleService scheduler;
    private final LearningMapper mapper;
    private final ObjectProvider<LearningProfileSource> profileSources;
    private final ObjectProvider<LearningScopeSource> scopeSources;
    private final ObjectProvider<WeaknessNotebook> notebook;
    private final EntityManager em;

    /** [JLAS-W0113] Số câu tối đa cho một lesson quiz. */
    private static final int LESSON_QUIZ_SIZE = 5;
    /** [JLAS-W0108] Số câu trắc nghiệm (chọn romaji / chọn mặt chữ) tối đa trong quiz bảng chữ cái. */
    private static final int KANA_MCQ_SIZE = 5;
    /** [JLAS-W0108] Số ký tự mỗi màn hình nối cặp (2 cột). */
    private static final int KANA_BATCH_SIZE = 5;
    /** [JLAS-W0113] Ngưỡng đạt quiz (%). */
    private static final int LESSON_QUIZ_PASS_PERCENT = 60;
    /** [JLAS-W0113] Số câu tối đa cho gate quiz tổng kết warm-up. */
    private static final int GATE_QUIZ_SIZE = 10;
    /** [JLAS-W0108] Số câu đọc / nghe tối đa đưa vào gate quiz khi warm-up có bài đọc/nghe. */
    private static final int GATE_COMPREHENSION_SIZE = 5;
    /** [JLAS-W0108] Giới hạn số lượt nộp quiz mỗi ngày (áp cho cả gate quiz lẫn lesson quiz). */
    private static final int MAX_QUIZ_ATTEMPTS_PER_DAY = 5;
    /** [JLAS-W0108] Loại quiz dùng cho bảng đếm lượt. */
    private static final String QUIZ_TYPE_GATE = "GATE";
    private static final String QUIZ_TYPE_LESSON = "LESSON";
    /** [JLAS-W0108] Trạng thái session đề thi: đã phát (chưa nộp) / đã nộp. */
    private static final String QUIZ_STATUS_ISSUED = "ISSUED";
    private static final String QUIZ_STATUS_SUBMITTED = "SUBMITTED";
    /** [JLAS-W0108] Số lần gate quiz chưa đạt trong ngày trước khi yêu cầu ôn lại (fail 2 lần -> học lại). */
    private static final int MAX_GATE_FAILURES_BEFORE_RELEARN = 2;
    /** [JLAS-W0108] Credit thưởng khi hoàn thành một bài học. */
    private static final int STUDY_REWARD_PER_LESSON = 10;
    /** [JLAS-W0108] Reason code của warm-up item bảng chữ cái (from-zero). */
    private static final String REASON_KANA_FOUNDATION = "KANA_FOUNDATION";

    @Override
    @Transactional
    public CreateLearningPathResult create(Long userId, Long assessmentAttemptId) {
        requirePositiveUserId(userId);
        if (assessmentAttemptId == null || assessmentAttemptId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A finalized assessment attempt is required.");
        }
        User user = users.findForCreditUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found."));
        LearningProfileSnapshot profile = profileSource().loadForUser(userId, assessmentAttemptId);
        if (profile == null || !userId.equals(profile.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return createPath(user, profile, scopeSource());
    }

    /** [JLAS-W0108] Tạo lộ trình from-zero từ survey đã hoàn thành (không cần Smart Test). */
    @Override
    @Transactional
    public CreateLearningPathResult createFromSurvey(Long userId) {
        requirePositiveUserId(userId);
        User user = users.findForCreditUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found."));
        LearningProfileSnapshot profile = profileSource().loadFromSurvey(userId);
        if (profile == null || !userId.equals(profile.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return createPath(user, profile, scopeSource());
    }

    private LearningProfileSource profileSource() {
        return profileSources.getIfAvailable(() -> {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Learning profile source is not configured yet.");
        });
    }

    private LearningScopeSource scopeSource() {
        return scopeSources.getIfAvailable(() -> {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Curriculum scope source is not configured yet.");
        });
    }

    /** Shared tail: resolve scope → select → schedule → idempotency → persist → best-effort AI. */
    private CreateLearningPathResult createPath(User user, LearningProfileSnapshot profile,
            LearningScopeSource scopeSource) {
        // [JLAS-W0108] Defense-in-depth: chặn target chưa phát hành cho lộ trình học.
        if (profile.targetLevel() != null && !JlptLevel.isAvailableForLearning(profile.targetLevel())) {
            throw new ApiException(ErrorCode.TARGET_NOT_AVAILABLE,
                    "Cấp độ " + profile.targetLevel() + " chưa được phát hành cho lộ trình học.");
        }
        LearningCatalogScope scope = scopeSource.resolve(user.getId(), profile);
        LearningSelectionResult selection = selector.select(user.getId(), profile, scope);
        var schedule = scheduler.schedule(selection.resources(), profile.minutesPerDay());

        // The user-row lock above already serializes concurrent creation for this user.
        // [JLAS-W0122] Chỉ xét path ACTIVE: replan giữ nguyên generation identity qua các version,
        // nên cùng generation có thể có nhiều hàng (SUPERSEDED + ACTIVE).
        // [JLAS-W0108] From-zero không có attempt nên idempotency theo null attempt + revision.
        var existing = profile.assessmentAttemptId() == null
                ? paths.findByUserIdAndAssessmentAttemptIdIsNullAndAssessmentRevisionAndPolicyVersionAndStatus(
                        user.getId(), profile.assessmentRevision(), selection.policyVersion(), "ACTIVE")
                : paths.findByUserIdAndAssessmentAttemptIdAndAssessmentRevisionAndPolicyVersionAndStatus(
                        user.getId(), profile.assessmentAttemptId(), profile.assessmentRevision(),
                        selection.policyVersion(), "ACTIVE");
        if (existing.isPresent()) {
            return new CreateLearningPathResult(loadResponse(existing.get()), false);
        }

        // [JLAS-W0108] Lát 2.4 — ghi điểm yếu đã kết luận vào Sổ tay (Weakness Notebook) trước khi
        // tạo path mới. Notebook là Optional để slice test (DataJpaTest) không cần dựng bean này.
        var notebook = this.notebook.getIfAvailable();
        if (notebook != null) {
            notebook.record(user, profile.assessmentAttemptId(), selection.weakEvidence());
        }

        int nextVersion = paths.findMaxVersionByUserId(user.getId()) + 1;
        supersedeActive(user.getId());

        var path = UserLearningPath.builder()
                .user(user)
                .title(buildTitle(profile))
                .goal(profile.goalCode())
                .targetLevel(profile.targetLevel())
                .currentLevel(profile.assessedLevel())
                .surveyId(profile.surveyId())
                .assessmentAttemptId(profile.assessmentAttemptId())
                .assessmentRevision(profile.assessmentRevision())
                .policyVersion(selection.policyVersion())
                .minutesPerDay(profile.minutesPerDay())
                .coverageGapsJson(mapper.writeCoverageGaps(selection.coverageGaps()))
                .status("ACTIVE")
                .version(nextVersion)
                .build();
        paths.save(path);

        List<UserLearningPathItem> persisted = new ArrayList<>();
        for (var scheduled : schedule) {
            Long grammarPointId = scheduled.resource().grammarPointId();
            Long vocabularyEntryId = scheduled.resource().vocabularyEntryId();
            persisted.add(UserLearningPathItem.builder()
                    .learningPath(path)
                    .lesson(em.getReference(Lesson.class, scheduled.resource().lessonId()))
                    .grammarPoint(grammarPointId == null
                            ? null : em.getReference(GrammarPoint.class, grammarPointId))
                    .vocabularyEntry(vocabularyEntryId == null
                            ? null : em.getReference(VocabularyEntry.class, vocabularyEntryId))
                    .orderIndex(scheduled.orderIndex())
                    .phase(scheduled.phase().name())
                    .reasonCode(scheduled.reasonCode())
                    .reason(scheduled.reasonText())
                    .dayNumber(scheduled.dayNumber())
                    .estimatedMinutes(scheduled.estimatedMinutes())
                    .matchedTagCodes(mapper.writeMatchedTags(scheduled.matchedTagCodes()))
                    .build());
        }
        items.saveAll(persisted);

        // [JLAS-W0108] Lớp AI Gemini best-effort: narrative lộ trình. Quiz sinh deterministic từ nội
        // dung bài (không dùng Gemini); chạy sau khi đã lưu path/items, lỗi không làm hỏng tạo lộ trình.
        generateAiPlan(path, profile, selection.weakEvidence(), schedule);

        return new CreateLearningPathResult(loadResponse(path), true);
    }

    @Override
    @Transactional
    public LearningPathResponse current(Long userId) {
        requirePositiveUserId(userId);
        return paths.findByUserIdAndStatus(userId, "ACTIVE")
                .map(this::loadResponse)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No learning path has been generated yet."));
    }

    @Override
    @Transactional
    public void completeLesson(Long userId, Long lessonId) {
        requirePositiveUserId(userId);
        if (lessonId == null || lessonId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A lesson is required.");
        }
        if (!lessons.existsById(lessonId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Lesson not found.");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found."));
        LessonProgress record = progress.findByUserIdAndLessonId(userId, lessonId)
                .orElseGet(() -> LessonProgress.builder()
                        .user(user)
                        .lesson(em.getReference(Lesson.class, lessonId))
                        .status("NOT_STARTED")
                        .build());
        if (!"COMPLETED".equals(record.getStatus())) {
            record.setStatus("COMPLETED");
            if (record.getStartedAt() == null) {
                record.setStartedAt(LocalDateTime.now());
            }
            record.setCompletedAt(LocalDateTime.now());
            progress.save(record);
            // [JLAS-W0108] Hoàn thành bài lần đầu -> cộng streak (chuỗi ngày học) + thưởng credit.
            // Idempotent theo (bài, streak theo ngày): hoàn thành lại cùng bài/ngày không cộng lại.
            recordStreakAndCredit(userId, lessonId);
        }
    }

    /** [JLAS-W0108] Ghi streak + credit khi hoàn thành bài. Các phụ thuộc là optional để slice test không cần dựng. */
    private void recordStreakAndCredit(Long userId, Long lessonId) {
        if (streakRepository != null) {
            updateStreak(userId);
        }
        if (creditService != null) {
            creditService.earn(userId, STUDY_REWARD_PER_LESSON, CreditReason.STUDY_REWARD,
                    "LESSON", lessonId, "Hoàn thành bài học trong lộ trình");
        }
    }

    /** [JLAS-W0108] Cộng 1 ngày vào chuỗi học liên tiếp; ngày trùng không cộng lại, ngày đứt quãng reset về 1. */
    private void updateStreak(Long userId) {
        LocalDate today = LocalDate.now();
        UserStreaks streak = streakRepository.findByUserId(userId)
                .orElseGet(() -> UserStreaks.builder()
                        .user(em.getReference(User.class, userId))
                        .build());
        StreakState next = advanceStreak(streak.getCurrentStreak(), streak.getLastCompletedDate(), today);
        if (!next.changed()) {
            return;
        }
        streak.setCurrentStreak(next.current());
        streak.setLongestStreak(next.longest());
        streak.setLastCompletedDate(today);
        streakRepository.save(streak);
    }

    /** [JLAS-W0108] Phép tính streak thuần túy (không I/O) để kiểm thử độc lập. */
    static StreakState advanceStreak(Integer currentStreak, LocalDate lastCompletedDate, LocalDate today) {
        int current = currentStreak == null ? 0 : currentStreak;
        if (lastCompletedDate != null && lastCompletedDate.equals(today)) {
            return new StreakState(current, current, false);
        }
        int next = (lastCompletedDate != null && lastCompletedDate.equals(today.minusDays(1))) ? current + 1 : 1;
        return new StreakState(next, Math.max(next, current), true);
    }

    record StreakState(int current, int longest, boolean changed) { }

    @Override
    @Transactional
    public LessonQuizResponse getLessonQuiz(Long userId, Long lessonId) {
        requirePositiveUserId(userId);
        Lesson lesson = requireLesson(lessonId);
        // [JLAS-W0108] Phát đề: chụp snapshot (câu hỏi + đáp án) vào session, trả attemptId để nộp theo snapshot.
        QuizData data = buildLessonQuizData(lesson);
        Long attemptId = issueSession(userId, QUIZ_TYPE_LESSON, lessonId, null, null, data);
        return new LessonQuizResponse(attemptId, lessonId, lesson.getTitle(), data.questions());
    }

    @Override
    @Transactional
    public LessonQuizResult submitLessonQuiz(Long userId, Long lessonId, LessonQuizSubmitRequest request) {
        requirePositiveUserId(userId);
        requireLesson(lessonId);
        LearningQuizSession session = loadOwnedSession(userId, QUIZ_TYPE_LESSON, lessonId, request.attemptId());
        return submitSession(session, request, false);
    }

    @Override
    @Transactional
    public LessonQuizResponse getGateQuiz(Long userId) {
        requirePositiveUserId(userId);
        UserLearningPath path = requireActivePath(userId);
        // [JLAS-W0108] Gate quiz chỉ hỏi nội dung đã warm-up (anchor→ngân hàng), không còn "vơ cả cấp".
        List<GateQuestion> gateQuestions = resolveGateQuiz(path);
        List<LessonQuizResponse.LessonQuizQuestion> questions = gateQuestions.stream()
                .map(GateQuestion::toDto).toList();
        Map<Long, QuizAnswerKey> answerKey = gateQuestions.stream()
                .collect(Collectors.toMap(GateQuestion::questionId,
                        question -> new QuizAnswerKey(question.correctOption(), question.explanation()),
                        (first, second) -> first));
        Long attemptId = issueSession(userId, QUIZ_TYPE_GATE, null, path.getId(), path.getVersion(),
                new QuizData(questions, answerKey));
        return new LessonQuizResponse(attemptId, null, "Kiểm tra tổng kết khởi động", questions);
    }

    @Override
    @Transactional
    public LessonQuizResult submitGateQuiz(Long userId, LessonQuizSubmitRequest request) {
        requirePositiveUserId(userId);
        LearningQuizSession session = loadOwnedSession(userId, QUIZ_TYPE_GATE, null, request.attemptId());
        return submitSession(session, request, true);
    }

    /**
     * [JLAS-W0108] Dựng đề quiz của một bài học ở dạng (câu hỏi, đáp án) tự nhất quán cho cả ba nguồn:
     * bài Kana (content_item), câu ngân hàng theo knowledge tag, và quiz sinh deterministic khi không có
     * ngân hàng. Dùng chung cho cả phát đề (GET) lẫn chụp snapshot.
     */
    private QuizData buildLessonQuizData(Lesson lesson) {
        if (isKanaLesson(lesson)) {
            return buildKanaQuiz(lesson.getId());
        }
        List<AssessmentQuestion> bank = questionRepository
                .findByIsActiveTrueAndKnowledgeTagCodeOrderByIdAsc(lesson.getKnowledgeTag().getCode())
                .stream().limit(LESSON_QUIZ_SIZE).toList();
        if (!bank.isEmpty()) {
            List<LessonQuizResponse.LessonQuizQuestion> questions = bank.stream()
                    .map(this::toQuizQuestion).toList();
            Map<Long, QuizAnswerKey> answerKey = bank.stream().collect(Collectors.toMap(
                    AssessmentQuestion::getId,
                    question -> new QuizAnswerKey(question.getCorrectOption(), question.getExplanation())));
            return new QuizData(questions, answerKey);
        }
        return buildLessonQuiz(lesson.getId());
    }

    /** [JLAS-W0108] Phát đề: lưu snapshot (câu hỏi + đáp án) vào session và trả attemptId. */
    private Long issueSession(Long userId, String quizType, Long lessonId, Long pathId, Integer pathVersion,
            QuizData data) {
        var session = LearningQuizSession.builder()
                .user(em.getReference(User.class, userId))
                .quizType(quizType)
                .lessonId(lessonId)
                .pathId(pathId)
                .pathVersion(pathVersion)
                .status(QUIZ_STATUS_ISSUED)
                .questionsJson(mapper.writeQuizQuestions(data.questions()))
                .answerKeyJson(mapper.writeQuizAnswerKey(data.answerKey()))
                .questionCount(data.answerKey().size())
                .build();
        quizSessions.save(session);
        return session.getId();
    }

    /** [JLAS-W0108] Nạp session theo attemptId + chủ sở hữu + loại quiz; sai chủ / thuộc đề khác -> từ chối. */
    private LearningQuizSession loadOwnedSession(Long userId, String quizType, Long lessonId, Long attemptId) {
        if (attemptId == null || attemptId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "attemptId là bắt buộc.");
        }
        return quizSessions.findOwned(attemptId, userId, quizType, lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN,
                        "Bài kiểm tra không hợp lệ hoặc đã hết hạn."));
    }

    /**
     * [JLAS-W0108] Chấm bài nộp theo snapshot đề thi đã phát:
     * <ul>
     *   <li>nộp lại (đã SUBMITTED) -> trả kết quả đã lưu, không tốn thêm lượt;</li>
     *   <li>khoá user để hai lần nộp đồng thời không cùng vượt đếm lượt;</li>
     *   <li>từ chối câu ngoài đề / trùng id trước khi tốn lượt;</li>
     *   <li>mẫu số = toàn bộ câu đã phát; câu bỏ trống tính là sai.</li>
     * </ul>
     */
    private LessonQuizResult submitSession(LearningQuizSession session, LessonQuizSubmitRequest request,
            boolean gate) {
        Long userId = session.getUser().getId();
        if (QUIZ_STATUS_SUBMITTED.equals(session.getStatus())) {
            return mapper.readQuizResult(session.getResultJson());
        }
        // [JLAS-W0108] Khoá user (như khi tạo path) để hai lần nộp đồng thời không cùng vượt đếm lượt.
        users.findForCreditUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found."));
        // Nạp lại dưới khoá: có thể đã bị nộp bởi transaction trước đó.
        LearningQuizSession fresh = quizSessions
                .findOwned(session.getId(), userId, session.getQuizType(), session.getLessonId())
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "Bài kiểm tra không hợp lệ."));
        if (QUIZ_STATUS_SUBMITTED.equals(fresh.getStatus())) {
            return mapper.readQuizResult(fresh.getResultJson());
        }

        UserLearningPath path = null;
        if (gate) {
            path = requireActivePath(userId);
            // [JLAS-W0108] Gate quiz gắn version lộ trình: lộ trình đã đổi thì bắt làm lại đề mới.
            if (!path.getId().equals(fresh.getPathId()) || !path.getVersion().equals(fresh.getPathVersion())) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Lộ trình đã thay đổi. Hãy làm lại bài kiểm tra tổng kết.");
            }
        }

        Map<Long, QuizAnswerKey> answerKey = mapper.readQuizAnswerKey(fresh.getAnswerKeyJson());
        GradeOutcome outcome = gradeAgainstKey(request.answers(), answerKey);

        // [JLAS-W0108] Tốn lượt chỉ khi nộp hợp lệ (đã qua validate câu ngoài đề / trùng id).
        assertQuizAttemptAllowed(userId, fresh.getQuizType(), fresh.getLessonId());
        recordQuizAttempt(userId, fresh.getQuizType(), fresh.getLessonId());

        int score = (int) Math.round(outcome.correct() * 100.0 / outcome.total());
        boolean passed = score >= LESSON_QUIZ_PASS_PERCENT;
        if (passed) {
            if (gate) {
                path.setGatePassedAt(LocalDateTime.now());
                paths.save(path);
            } else {
                completeLesson(userId, fresh.getLessonId());
            }
        }
        boolean relearnRequired = gate && !passed
                && gateFailuresToday(userId) >= MAX_GATE_FAILURES_BEFORE_RELEARN;

        LessonQuizResult result = new LessonQuizResult(outcome.total(), outcome.correct(), score, passed,
                LESSON_QUIZ_PASS_PERCENT, passed, relearnRequired, outcome.review());

        fresh.setStatus(QUIZ_STATUS_SUBMITTED);
        fresh.setCorrectCount(outcome.correct());
        fresh.setScorePercent(score);
        fresh.setPassed(passed);
        fresh.setResultJson(mapper.writeQuizResult(result));
        fresh.setSubmittedAt(LocalDateTime.now());
        quizSessions.save(fresh);
        return result;
    }

    /**
     * [JLAS-W0108] Chấm theo đáp án snapshot: mẫu số = toàn bộ câu đã phát; từ chối câu ngoài đề / trùng id.
     */
    private GradeOutcome gradeAgainstKey(List<LessonQuizSubmitRequest.LessonQuizAnswerRequest> answers,
            Map<Long, QuizAnswerKey> answerKey) {
        if (answers == null || answers.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Vui lòng trả lời ít nhất một câu.");
        }
        Map<Long, Integer> selected = new LinkedHashMap<>();
        for (var answer : answers) {
            if (!answerKey.containsKey(answer.questionId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Câu hỏi không thuộc bài kiểm tra đã phát.");
            }
            if (selected.putIfAbsent(answer.questionId(), answer.selectedOption()) != null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bài nộp chứa questionId trùng lặp.");
            }
        }
        int correct = 0;
        List<LessonQuizResult.AnswerReview> review = new ArrayList<>(answerKey.size());
        for (var entry : answerKey.entrySet()) {
            Long questionId = entry.getKey();
            QuizAnswerKey expected = entry.getValue();
            Integer chosen = selected.get(questionId);
            boolean isCorrect = chosen != null && chosen == expected.correctOption();
            if (isCorrect) correct++;
            review.add(new LessonQuizResult.AnswerReview(questionId, expected.correctOption(), chosen,
                    isCorrect, expected.explanation()));
        }
        return new GradeOutcome(review, correct, answerKey.size());
    }

    /** [JLAS-W0108] Kết quả chấm trung gian (review + đếm) trước khi đóng gói {@link LessonQuizResult}. */
    private record GradeOutcome(List<LessonQuizResult.AnswerReview> review, int correct, int total) { }

    private UserLearningPath requireActivePath(Long userId) {
        return paths.findByUserIdAndStatus(userId, "ACTIVE")
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No active learning path found."));
    }

    private Set<String> warmUpTagCodes(Long pathId) {
        return items.findByLearningPathIdOrderByOrderIndex(pathId).stream()
                .filter(item -> LearningPhase.WARM_UP.name().equals(item.getPhase()))
                .flatMap(item -> mapper.readMatchedTags(item.getMatchedTagCodes()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * [JLAS-W0108] Câu gate quiz — "ngân hàng trước, Gemini bù", chỉ hỏi nội dung đã warm-up:
     * <ol>
     *   <li>KANJI/VOCAB gắn trực tiếp các từ đã warm-up ({@code vocabularyEntry});</li>
     *   <li>GRAMMAR gắn trực tiếp các mẫu ngữ pháp đã warm-up ({@code grammarPoint});</li>
     *   <li>câu theo knowledge tag làm fallback (WEAK_TAG / khi specific question chưa đủ), tối đa
     *       {@value #GATE_QUIZ_SIZE} câu curated;</li>
     *   <li>câu Gemini đã sinh lúc tạo path (bảng {@code learning_generated_question}) được phơi
     *       với id ÂM để không đụng id ngân hàng.</li>
     * </ol>
     * Không còn cộng 5 đọc + 5 nghe "vơ cả cấp" như trước.
     */
    private List<GateQuestion> resolveGateQuiz(UserLearningPath path) {
        Long pathId = path.getId();
        var ordered = new LinkedHashMap<Long, GateQuestion>();
        var vocabIds = items.findVocabularyEntryIdsIn(pathId,
                List.of("WEAK_KANJI_ITEM", "WEAK_VOCAB_ITEM"));
        if (!vocabIds.isEmpty()) {
            questionRepository.findByIsActiveTrueAndVocabularyEntryIdInOrderByIdAsc(vocabIds)
                    .forEach(question -> putIfRoom(ordered, question.getId(), toGateQuestion(question)));
        }
        var grammarIds = items.findGrammarPointIdsIn(pathId, List.of("WEAK_GRAMMAR_POINT"));
        if (!grammarIds.isEmpty()) {
            questionRepository.findByIsActiveTrueAndGrammarPointIdInOrderByIdAsc(grammarIds)
                    .forEach(question -> putIfRoom(ordered, question.getId(), toGateQuestion(question)));
        }
        // [JLAS-W0108] Đọc/nghe: warm-up có bài đọc/nghe thì hỏi câu đọc/nghe cùng cấp (có passage/audio),
        // không còn "vơ cả cấp" khi chưa ôn kĩ năng đó.
        if (!items.findLessonIdsIn(pathId, List.of("WEAK_READING_ITEM")).isEmpty()) {
            questionRepository.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc(
                            WeaknessEvidence.SKILL_READING, path.getCurrentLevel())
                    .stream().limit(GATE_COMPREHENSION_SIZE)
                    .forEach(question -> putIfRoom(ordered, question.getId(), toGateQuestion(question)));
        }
        if (!items.findLessonIdsIn(pathId, List.of("WEAK_LISTENING_ITEM")).isEmpty()) {
            questionRepository.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc(
                            WeaknessEvidence.SKILL_LISTENING, path.getCurrentLevel())
                    .stream().limit(GATE_COMPREHENSION_SIZE)
                    .forEach(question -> putIfRoom(ordered, question.getId(), toGateQuestion(question)));
        }
        questionRepository.findByIsActiveTrueAndKnowledgeTagCodeInOrderByIdAsc(warmUpTagCodes(pathId))
                .forEach(question -> putIfRoom(ordered, question.getId(), toGateQuestion(question)));
        // [JLAS-W0108] Bù câu Kana deterministic cho warm-up bảng chữ cái (từ content_item, không dùng Gemini).
        for (GateQuestion question : kanaGateQuestions(pathId)) {
            putIfRoom(ordered, question.questionId(), question);
        }
        return List.copyOf(ordered.values());
    }

    private void putIfRoom(LinkedHashMap<Long, GateQuestion> ordered, Long id, GateQuestion question) {
        if (ordered.size() < GATE_QUIZ_SIZE) {
            ordered.putIfAbsent(id, question);
        }
    }

    /** Dạng thống nhất cho cả câu ngân hàng lẫn câu Gemini để hiển thị và chấm điểm self-consistent. */
    private record GateQuestion(long questionId, int correctOption, String explanation,
            String skill, String jlptLevel, String title, String passageHtml, String audioUrl,
            String imageUrl, String questionText, List<String> options) {
        LessonQuizResponse.LessonQuizQuestion toDto() {
            return new LessonQuizResponse.LessonQuizQuestion(questionId, skill, jlptLevel, title,
                    passageHtml, audioUrl, imageUrl, questionText, options, "MCQ");
        }
    }

    private GateQuestion toGateQuestion(AssessmentQuestion question) {
        return new GateQuestion(question.getId(), question.getCorrectOption(), question.getExplanation(),
                question.getSkill(), question.getJlptLevel(), question.getTitle(), question.getPassageHtml(),
                question.getAudioUrl(), question.getImageUrl(), question.getQuestionText(),
                List.of(text(question.getOption1()), text(question.getOption2()),
                        text(question.getOption3()), text(question.getOption4())));
    }

    private GateQuestion toGateQuestion(LearningGeneratedQuestion question) {
        return new GateQuestion(question.negativeId(), question.getCorrectOption(), question.getExplanation(),
                question.getSkill(), question.getJlptLevel(), null, null, null, null, question.getQuestionText(),
                List.of(text(question.getOption1()), text(question.getOption2()),
                        text(question.getOption3()), text(question.getOption4())));
    }

    /**
     * [JLAS-W0108] Sinh (best-effort) narrative lộ trình cá nhân hoá và ghi vào {@code ai_plan_json}.
     * Chỉ chạy khi {@code planGenerator} có sẵn (production); null/lỗi thì UI vẫn chạy bình thường.
     */
    private void generateAiPlan(UserLearningPath path, LearningProfileSnapshot profile,
            List<WeaknessEvidence> weaknesses, List<ScheduledLearningResource> schedule) {
        if (planGenerator == null) return;
        try {
            var plan = planGenerator.generate(profile, weaknesses, schedule);
            if (plan != null) {
                path.setAiPlanJson(plan.toString());
            }
        } catch (Exception ignored) {
            // best-effort: narrative lỗi không làm hỏng tạo lộ trình.
        }
    }

    /**
     * [JLAS-W0108] Sinh (best-effort) câu Gemini bù cho anchor đã warm-up nhưng ngân hàng không có
     * câu curated. Chỉ chạy khi {@code quizGenerator} có sẵn (production); lỗi/không key thì bỏ qua.
     */
    private void generateMissingQuizQuestions(UserLearningPath path, LearningProfileSnapshot profile,
            List<WeaknessEvidence> weaknesses) {
        if (quizGenerator == null || generatedQuestions == null) return;
        Long pathId = path.getId();
        var grammarIds = items.findGrammarPointIdsIn(pathId, List.of("WEAK_GRAMMAR_POINT"));
        var vocabIds = items.findVocabularyEntryIdsIn(pathId,
                List.of("WEAK_KANJI_ITEM", "WEAK_VOCAB_ITEM"));
        var tagCodes = warmUpTagCodes(pathId);
        Set<String> covered = coveredBankAnchors(grammarIds, vocabIds, tagCodes);
        Map<String, WeaknessEvidence> byAnchor = weaknessByAnchor(weaknesses);

        List<LearningQuizGenerator.Anchor> missing = new ArrayList<>();
        for (Long id : grammarIds) {
            if (!covered.contains(LearningGeneratedQuestion.ANCHOR_GRAMMAR_POINT + ":" + id)) {
                missing.add(buildAnchor(LearningGeneratedQuestion.ANCHOR_GRAMMAR_POINT, String.valueOf(id),
                        "GRAMMAR", profile.assessedLevel(), grammarDetail(id),
                        byAnchor.get(LearningGeneratedQuestion.ANCHOR_GRAMMAR_POINT + ":" + id)));
            }
        }
        for (Long id : vocabIds) {
            if (!covered.contains(LearningGeneratedQuestion.ANCHOR_VOCABULARY_ENTRY + ":" + id)) {
                missing.add(buildAnchor(LearningGeneratedQuestion.ANCHOR_VOCABULARY_ENTRY, String.valueOf(id),
                        "VOCABULARY", profile.assessedLevel(), vocabDetail(id),
                        byAnchor.get(LearningGeneratedQuestion.ANCHOR_VOCABULARY_ENTRY + ":" + id)));
            }
        }
        for (String code : tagCodes) {
            if (!covered.contains(LearningGeneratedQuestion.ANCHOR_KNOWLEDGE_TAG + ":" + code)) {
                missing.add(buildAnchor(LearningGeneratedQuestion.ANCHOR_KNOWLEDGE_TAG, code,
                        "GRAMMAR", profile.assessedLevel(), tagDetail(code),
                        byAnchor.get(LearningGeneratedQuestion.ANCHOR_KNOWLEDGE_TAG + ":" + code)));
            }
        }
        if (!missing.isEmpty()) {
            try {
                quizGenerator.generate(path, missing);
            } catch (Exception ignored) {
                // best-effort: sinh câu lỗi không làm hỏng tạo lộ trình.
            }
        }
    }

    private Set<String> coveredBankAnchors(List<Long> grammarIds, List<Long> vocabIds, Set<String> tagCodes) {
        Set<String> covered = new HashSet<>();
        if (!vocabIds.isEmpty()) {
            questionRepository.findByIsActiveTrueAndVocabularyEntryIdInOrderByIdAsc(vocabIds)
                    .forEach(q -> covered.add(LearningGeneratedQuestion.ANCHOR_VOCABULARY_ENTRY
                            + ":" + q.getVocabularyEntry().getId()));
        }
        if (!grammarIds.isEmpty()) {
            questionRepository.findByIsActiveTrueAndGrammarPointIdInOrderByIdAsc(grammarIds)
                    .forEach(q -> covered.add(LearningGeneratedQuestion.ANCHOR_GRAMMAR_POINT
                            + ":" + q.getGrammarPoint().getId()));
        }
        if (!tagCodes.isEmpty()) {
            questionRepository.findByIsActiveTrueAndKnowledgeTagCodeInOrderByIdAsc(tagCodes)
                    .forEach(q -> covered.add(LearningGeneratedQuestion.ANCHOR_KNOWLEDGE_TAG
                            + ":" + q.getKnowledgeTag().getCode()));
        }
        return covered;
    }

    private Map<String, WeaknessEvidence> weaknessByAnchor(List<WeaknessEvidence> weaknesses) {
        Map<String, WeaknessEvidence> map = new HashMap<>();
        if (weaknesses == null) return map;
        for (var weakness : weaknesses) {
            if (weakness.grammarPointId() != null) {
                map.put(LearningGeneratedQuestion.ANCHOR_GRAMMAR_POINT + ":" + weakness.grammarPointId(), weakness);
            }
            if (weakness.vocabularyEntryId() != null) {
                map.put(LearningGeneratedQuestion.ANCHOR_VOCABULARY_ENTRY + ":" + weakness.vocabularyEntryId(), weakness);
            }
            if (weakness.tagCode() != null) {
                map.put(LearningGeneratedQuestion.ANCHOR_KNOWLEDGE_TAG + ":" + weakness.tagCode(), weakness);
            }
        }
        return map;
    }

    private LearningQuizGenerator.Anchor buildAnchor(String type, String id, String skill, String level,
            String detail, WeaknessEvidence weakness) {
        if (weakness != null) {
            detail = detail + "\nĐiểm yếu Smart Test: sai " + weakness.wrongCount()
                    + "/" + weakness.assessedCount() + " câu.";
        }
        return new LearningQuizGenerator.Anchor(type, id, skill, level, detail);
    }

    private String grammarDetail(Long id) {
        if (grammarPoints != null) {
            var point = grammarPoints.findById(id).orElse(null);
            if (point != null) {
                return "Mẫu ngữ pháp: " + point.getPattern() + "\nNghĩa: " + point.getMeaning()
                        + (point.getExample() != null && !point.getExample().isBlank()
                                ? "\nVí dụ: " + point.getExample() : "");
            }
        }
        return "Mẫu ngữ pháp id " + id;
    }

    private String vocabDetail(Long id) {
        if (vocabularyEntries != null) {
            var entry = vocabularyEntries.findById(id).orElse(null);
            if (entry != null) {
                return "Từ: " + entry.getExpression() + " (" + entry.getReading() + ")\nNghĩa: "
                        + (entry.getMeaning() == null ? "" : entry.getMeaning());
            }
        }
        return "Từ vựng id " + id;
    }

    private String tagDetail(String code) {
        if (tags != null) {
            var tag = tags.findByCodeAndTagType(code, "KNOWLEDGE").orElse(null);
            if (tag != null) {
                return "Điểm kiến thức: " + tag.getName() + " (" + code + ")";
            }
        }
        return "Điểm kiến thức: " + code;
    }

    /** [JLAS-W0108] Số lần gate quiz đã nộp trong ngày (tất cả đều là "chưa đạt" vì đạt rồi thì không thi lại). */
    private long gateFailuresToday(Long userId) {
        return quizAttempts.countSince(userId, QUIZ_TYPE_GATE, null, LocalDate.now().atStartOfDay());
    }

    /** [JLAS-W0108] Ném 429 nếu user đã dùng hết {@value #MAX_QUIZ_ATTEMPTS_PER_DAY} lượt quiz hôm nay. */
    private void assertQuizAttemptAllowed(Long userId, String quizType, Long lessonId) {
        long used = quizAttempts.countSince(userId, quizType, lessonId, LocalDate.now().atStartOfDay());
        if (used >= MAX_QUIZ_ATTEMPTS_PER_DAY) {
            throw new ApiException(ErrorCode.QUIZ_ATTEMPT_LIMIT,
                    "Bạn đã dùng hết " + MAX_QUIZ_ATTEMPTS_PER_DAY
                            + " lượt quiz hôm nay. Hãy quay lại vào ngày mai.");
        }
    }

    /** [JLAS-W0108] Ghi nhận một lượt nộp quiz (tính cả khi chưa đạt). */
    private void recordQuizAttempt(Long userId, String quizType, Long lessonId) {
        quizAttempts.save(QuizAttempt.builder()
                .user(em.getReference(User.class, userId))
                .quizType(quizType)
                .lessonId(lessonId)
                .attemptedAt(LocalDateTime.now())
                .build());
    }

    private Lesson requireLesson(Long lessonId) {
        if (lessonId == null || lessonId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A lesson is required.");
        }
        return lessons.findById(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found."));
    }

    private LessonQuizResponse.LessonQuizQuestion toQuizQuestion(AssessmentQuestion question) {
        return new LessonQuizResponse.LessonQuizQuestion(
                question.getId(), question.getSkill(), question.getJlptLevel(),
                question.getTitle(), question.getPassageHtml(), question.getAudioUrl(),
                question.getImageUrl(), question.getQuestionText(),
                List.of(text(question.getOption1()), text(question.getOption2()),
                        text(question.getOption3()), text(question.getOption4())), "MCQ");
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** [JLAS-W0108] Bài bảng chữ cái (kiến thức nền tảng) có tag {@code KANA-*}. */
    private static boolean isKanaLesson(Lesson lesson) {
        return lesson.getKnowledgeTag() != null
                && lesson.getKnowledgeTag().getCode() != null
                && lesson.getKnowledgeTag().getCode().startsWith("KANA-");
    }

    /** [JLAS-W0108] Cặp (mặt chữ, romaji) từ content_item READING của một bài bảng chữ cái. */
    private List<KanaPair> kanaPairsForLesson(Long lessonId) {
        return contentItems.findByLessonIdAndContentType(lessonId, "READING").stream()
                .filter(item -> item.getContent() != null && !item.getContent().isBlank())
                .map(item -> new KanaPair(item.getId(), item.getContent().trim(), item.getTitle()))
                .toList();
    }

    /**
     * [JLAS-W0108] Sinh quiz bảng chữ cái trực tiếp từ {@code content_item} (READING) của bài,
     * mỗi item = một ký tự (title=romaji, content=mặt chữ). Deterministic để nộp bài chấm cùng
     * một đáp án với lúc hiển thị:
     * <ol>
     *   <li>Nối cặp: chia thành từng nhóm {@value #KANA_BATCH_SIZE} ký tự, mỗi nhóm là một màn
     *       2 cột (trái = mặt chữ, phải = romaji đã xáo);</li>
     *   <li>Trắc nghiệm: xen kẽ "chọn cách đọc (romaji)" và "chọn mặt chữ", tối đa
     *       {@value #KANA_MCQ_SIZE} câu.</li>
     * </ol>
     * Id câu dùng số ÂM (từ content_item id) để không đụng id ngân hàng. Đáp án lưu 1-based
     * (index + 1) cho khớp với lựa chọn client gửi lên (option 1..N).
     */
    private QuizData buildKanaQuiz(Long lessonId) {
        List<KanaPair> pairs = kanaPairsForLesson(lessonId);
        if (pairs.isEmpty()) {
            return new QuizData(List.of(), Map.of());
        }
        List<String> kanaList = pairs.stream().map(KanaPair::kana).toList();
        List<String> romajiList = pairs.stream().map(KanaPair::romaji).toList();

        List<LessonQuizResponse.LessonQuizQuestion> questions = new ArrayList<>();
        Map<Long, QuizAnswerKey> answerKey = new LinkedHashMap<>();

        // 1) Nối cặp mặt chữ ↔ romaji, chia nhóm 5 ký tự/màn, xáo romaji deterministic.
        for (int start = 0; start < pairs.size(); start += KANA_BATCH_SIZE) {
            int end = Math.min(start + KANA_BATCH_SIZE, pairs.size());
            List<KanaPair> batch = pairs.subList(start, end);
            List<String> shuffledRomaji = new ArrayList<>(
                    batch.stream().map(KanaPair::romaji).toList());
            Collections.shuffle(shuffledRomaji, new Random(lessonId * 31L + start));
            for (KanaPair pair : batch) {
                long questionId = -pair.contentItemId();
                int correct = shuffledRomaji.indexOf(pair.romaji()) + 1; // 1-based
                questions.add(new LessonQuizResponse.LessonQuizQuestion(questionId, "KANA", null,
                        null, null, null, null, pair.kana(), shuffledRomaji, "MATCHING"));
                answerKey.put(questionId, new QuizAnswerKey(correct, kanaReading(pair.kana(), pair.romaji())));
            }
        }
        // 2) Trắc nghiệm: chọn romaji đúng hoặc chọn mặt chữ đúng.
        int mcqCount = Math.min(KANA_MCQ_SIZE, pairs.size());
        for (int i = 0; i < mcqCount; i++) {
            KanaPair pair = pairs.get(i);
            long questionId = -1_000_000L - pair.contentItemId();
            if (i % 2 == 0) {
                List<String> options = buildOptions(pair.romaji(), romajiList, i);
                questions.add(new LessonQuizResponse.LessonQuizQuestion(questionId, "KANA", null,
                        null, null, null, null, "Chọn cách đọc (romaji) của: " + pair.kana(),
                        options, "MCQ"));
                answerKey.put(questionId, new QuizAnswerKey(options.indexOf(pair.romaji()) + 1,
                        kanaReading(pair.kana(), pair.romaji())));
            } else {
                List<String> options = buildOptions(pair.kana(), kanaList, i);
                questions.add(new LessonQuizResponse.LessonQuizQuestion(questionId, "KANA", null,
                        null, null, null, null, "Chọn mặt chữ của: " + pair.romaji(),
                        options, "MCQ"));
                answerKey.put(questionId, new QuizAnswerKey(options.indexOf(pair.kana()) + 1,
                        "«" + pair.romaji() + "» viết là 「" + pair.kana() + "」"));
            }
        }
        return new QuizData(questions, answerKey);
    }

    /** 4 lựa chọn: đáp án đúng + 3 nhiễu kế cận, xoay vòng deterministic theo {@code seed}. */
    private List<String> buildOptions(String correctValue, List<String> allValues, int seed) {
        int idx = allValues.indexOf(correctValue);
        LinkedHashSet<String> options = new LinkedHashSet<>();
        options.add(correctValue);
        for (int k = 1; options.size() < 4 && k <= allValues.size(); k++) {
            options.add(allValues.get(Math.floorMod(idx + k, allValues.size())));
        }
        List<String> base = new ArrayList<>(options);
        int shift = Math.floorMod(seed, base.size());
        List<String> rotated = new ArrayList<>(base.size());
        for (int k = 0; k < base.size(); k++) {
            rotated.add(base.get((k + shift) % base.size()));
        }
        return rotated;
    }

    private static String kanaReading(String kana, String romaji) {
        return "「" + kana + "」đọc là «" + romaji + "»";
    }

    /**
     * [JLAS-W0108] Sinh quiz deterministic từ nội dung bài target (ngữ pháp + từ vựng) khi ngân hàng
     * assessment_question không có câu gắn tag bài. Id câu dùng số ÂM (từ grammar_point_id /
     * vocabulary_entry_id) để không đụng id ngân hàng; đáp án lưu 1-based (index + 1).
     */
    private QuizData buildLessonQuiz(Long lessonId) {
        List<McqSpec> specs = new ArrayList<>();
        if (grammarPoints != null) {
            List<GrammarPoint> points = grammarPoints.findByLessonIdOrderByOrderIndexAsc(lessonId);
            List<String> meanings = distinctNonBlank(points.stream().map(GrammarPoint::getMeaning).toList());
            for (GrammarPoint point : points) {
                String pattern = nonBlank(point.getPattern());
                String meaning = nonBlank(point.getMeaning());
                if (pattern.isEmpty() || meaning.isEmpty()) continue;
                specs.add(new McqSpec(-2_000_000L - point.getId(), "GRAMMAR",
                        "Chọn nghĩa đúng của mẫu ngữ pháp「" + pattern + "」", meaning,
                        "「" + pattern + "」nghĩa là «" + meaning + "»", meanings));
            }
        }
        if (contentVocabularies != null) {
            List<VocabularyEntry> entries = contentVocabularies.findVocabularyByLessonId(lessonId);
            List<String> meanings = distinctNonBlank(entries.stream().map(VocabularyEntry::getMeaning).toList());
            for (VocabularyEntry entry : entries) {
                String expression = nonBlank(entry.getExpression());
                String meaning = nonBlank(entry.getMeaning());
                if (expression.isEmpty() || meaning.isEmpty()) continue;
                specs.add(new McqSpec(-3_000_000L - entry.getId(), "VOCABULARY",
                        "Chọn nghĩa đúng của từ「" + expression + "」", meaning,
                        "「" + expression + "」nghĩa là «" + meaning + "»", meanings));
            }
        }
        List<LessonQuizResponse.LessonQuizQuestion> questions = new ArrayList<>();
        Map<Long, QuizAnswerKey> answerKey = new LinkedHashMap<>();
        int size = Math.min(LESSON_QUIZ_SIZE, specs.size());
        for (int i = 0; i < size; i++) {
            McqSpec spec = specs.get(i);
            List<String> options = buildOptions(spec.correctOption(), spec.distractors(), i);
            int correct = options.indexOf(spec.correctOption()) + 1;
            questions.add(new LessonQuizResponse.LessonQuizQuestion(spec.questionId(), spec.skill(), null,
                    null, null, null, null, spec.questionText(), options, "MCQ"));
            answerKey.put(spec.questionId(), new QuizAnswerKey(correct, spec.explanation()));
        }
        return new QuizData(questions, answerKey);
    }

    /**
     * [JLAS-W0108] Câu gate quiz cho warm-up bảng chữ cái (from-zero): sinh deterministic từ
     * content_item READING của các bài Kana đã ôn, thay cho câu Gemini rác. Tối đa
     * {@value #GATE_QUIZ_SIZE} câu trắc nghiệm "chọn romaji".
     */
    private List<GateQuestion> kanaGateQuestions(Long pathId) {
        List<Long> lessonIds = items.findLessonIdsIn(pathId, List.of(REASON_KANA_FOUNDATION));
        if (lessonIds.isEmpty()) {
            return List.of();
        }
        List<KanaPair> pairs = new ArrayList<>();
        for (Long lessonId : lessonIds) {
            kanaPairsForLesson(lessonId).stream()
                    .filter(pair -> pair.romaji() != null && !pair.romaji().isBlank())
                    .forEach(pairs::add);
        }
        if (pairs.isEmpty()) {
            return List.of();
        }
        List<String> romajiList = distinctNonBlank(pairs.stream().map(KanaPair::romaji).toList());
        List<GateQuestion> questions = new ArrayList<>();
        int count = Math.min(GATE_QUIZ_SIZE, pairs.size());
        for (int i = 0; i < count; i++) {
            KanaPair pair = pairs.get(i);
            long questionId = -10_000_000L - pair.contentItemId();
            List<String> options = buildOptions(pair.romaji(), romajiList, i);
            questions.add(new GateQuestion(questionId, options.indexOf(pair.romaji()) + 1,
                    kanaReading(pair.kana(), pair.romaji()), "KANA", null, null, null, null, null,
                    "Chọn cách đọc (romaji) của: " + pair.kana(), options));
        }
        return questions;
    }

    /** [JLAS-W0108] Câu trắc nghiệm deterministic chưa chọn lựa chọn (đáp án + nhiễu). */
    private record McqSpec(long questionId, String skill, String questionText,
            String correctOption, String explanation, List<String> distractors) { }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> distinctNonBlank(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String trimmed = nonBlank(value);
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** [JLAS-W0108] Quiz đã sinh + đáp án để chấm (không lộ đáp án cho client). */
    private record QuizData(
            List<LessonQuizResponse.LessonQuizQuestion> questions,
            Map<Long, QuizAnswerKey> answerKey) { }

    private record KanaPair(long contentItemId, String kana, String romaji) { }

    private LearningPathResponse loadResponse(UserLearningPath path) {
        List<UserLearningPathItem> pathItems = items.findWithLessonByLearningPathId(path.getId());
        if (pathItems.isEmpty() || pathItems.stream().anyMatch(item -> item.getPhase() == null
                || item.getDayNumber() == null || item.getEstimatedMinutes() == null)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "This learning path predates the two-phase schedule; please regenerate it.");
        }
        Set<Long> completed = progress.findCompletedLessonIds(path.getUser().getId());
        return mapper.toResponse(path, pathItems, completed,
                warmUpFlashcards != null && warmUpFlashcards.isCompleted(path));
    }

    private void supersedeActive(Long userId) {
        paths.findByUserIdAndStatus(userId, "ACTIVE").ifPresent(active -> {
            active.setStatus("SUPERSEDED");
            paths.save(active);
        });
    }

    private String buildTitle(LearningProfileSnapshot profile) {
        // [JLAS-W0108] From-zero: tiêu đề thân thiện thay vì "JLPT BELOW_N5 to N5".
        if ("BELOW_N5".equals(profile.assessedLevel())) {
            return "JLPT " + profile.targetLevel() + " (từ con số 0)";
        }
        if (profile.targetLevel().equals(profile.assessedLevel())) {
            return "JLPT " + profile.assessedLevel();
        }
        return "JLPT " + profile.assessedLevel() + " to " + profile.targetLevel();
    }

    private static void requirePositiveUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
    }
}
