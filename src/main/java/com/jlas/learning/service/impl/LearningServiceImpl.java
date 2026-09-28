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
import com.jlas.learning.dto.CreateLearningPathResult;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.LearningSelectionResult;
import com.jlas.learning.dto.LearningStageScope;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.LessonQuizSubmitRequest;
import com.jlas.learning.dto.QuizAnswerKey;
import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.entity.LessonProgress;
import com.jlas.learning.entity.QuizAttempt;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LessonProgressRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.QuizAttemptRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.LearningResourceSelectionService;
import com.jlas.learning.service.LearningScheduleService;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.LearningService;
import com.jlas.learning.service.WarmUpFlashcardService;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    // [JLAS-W0108] Tầng AI Gemini (narrative only). Optional để slice test (DataJpaTest) không cần dựng
    // bean @Component; lúc đó lộ trình vẫn chạy bình thường.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LearningPathPlanGenerator planGenerator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.dictionary.repository.GrammarPointRepository grammarPoints;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.dictionary.repository.ContentVocabularyRepository contentVocabularies;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.dictionary.repository.VocabularyEntryRepository vocabularyEntries;
    private final UserRepository users;
    private final UserLearningPathRepository paths;
    private final UserLearningPathItemRepository items;
    private final UserLearningStageRepository stageRepository;
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
    private final LearningClock clock;
    private final EntityManager em;

    /** [JLAS-W0113] Số câu tối đa cho một lesson quiz. */
    private static final int LESSON_QUIZ_SIZE = 5;
    /** [JLAS-W0108] Số câu trắc nghiệm (chọn romaji / chọn mặt chữ) tối đa trong quiz bảng chữ cái. */
    private static final int KANA_MCQ_SIZE = 5;
    /** [JLAS-W0108] Số ký tự mỗi màn hình nối cặp (2 cột). */
    private static final int KANA_BATCH_SIZE = 5;
    /** [JLAS-W0108] P4/D2 — Ngưỡng đạt tổng của lesson quiz (%). */
    private static final int LESSON_QUIZ_PASS_PERCENT = 80;
    /** [JLAS-W0108] P4/D2 — Ngưỡng đạt của từng nhóm mục tiêu bắt buộc trong lesson quiz (%). */
    private static final int LESSON_QUIZ_GROUP_PASS_PERCENT = 70;
    /** [JLAS-W0108] Ngưỡng đạt gate quiz tổng kết warm-up (%). Gate thuộc P5, giữ nguyên 60. */
    private static final int GATE_QUIZ_PASS_PERCENT = 60;
    /** [JLAS-W0113] Số câu tối đa cho gate quiz tổng kết warm-up. */
    private static final int GATE_QUIZ_SIZE = 10;
    /** [JLAS-W0108] Số câu đọc / nghe tối đa đưa vào gate quiz khi warm-up có bài đọc/nghe. */
    private static final int GATE_COMPREHENSION_SIZE = 5;
    /** [JLAS-W0108] Giới hạn số lượt nộp quiz mỗi ngày (áp cho cả gate quiz lẫn lesson quiz). */
    private static final int MAX_QUIZ_ATTEMPTS_PER_DAY = 5;
    /** [JLAS-W0108] Loại quiz dùng cho bảng đếm lượt. */
    private static final String QUIZ_TYPE_GATE = "GATE";
    private static final String QUIZ_TYPE_LESSON = "LESSON";
    private static final String QUIZ_TYPE_GRAMMAR = "GRAMMAR";
    private static final String QUIZ_TYPE_FLASHCARD = "FLASHCARD";
    /** [JLAS-W0108] Số câu tối đa cho warm-up grammar quiz (retest đúng mẫu ngữ pháp đã sai). */
    private static final int GRAMMAR_QUIZ_SIZE = 5;
    /** [JLAS-W0108] P4 — ngưỡng đạt warm-up grammar quiz: đúng 4/5 (80%). */
    private static final int GRAMMAR_QUIZ_PASS_PERCENT = 80;
    /** [JLAS-W0108] P4/D3 — flashcard quiz phải đúng hết membership mới đạt (sai → ôn lại đến đúng). */
    private static final int FLASHCARD_QUIZ_PASS_PERCENT = 100;
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

        // [JLAS-W0108] P6/D5 — tạo các chặng nối tiếp (mỗi chặng một cấp mục tiêu); chặng đầu ACTIVE.
        // Path một cấp vẫn có đúng một chặng để giữ cùng contract với path nhiều cấp. Scope cũ (adapter/
        // test) không phân chặng thì stageByLevel rỗng -> item giữ stage_id null (tương thích ngược).
        List<UserLearningStage> createdStages = createStages(path, scope.stages());
        Map<String, UserLearningStage> stageByLevel = createdStages.stream()
                .collect(Collectors.toMap(UserLearningStage::getLevel, stage -> stage));
        UserLearningStage firstStage = createdStages.isEmpty() ? null : createdStages.get(0);

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
                    .stageId(stageIdFor(scheduled, stageByLevel, firstStage))
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
            recordStreakAndCredit(userId, "LESSON", lessonId, "Hoàn thành bài học trong lộ trình");
        }
    }

    /** [JLAS-W0108] P2 — đánh dấu hoàn thành nội dung một activity (item), độc lập source lesson. */
    @Override
    @Transactional
    public void completeItem(Long userId, Long itemId) {
        requirePositiveUserId(userId);
        if (itemId == null || itemId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An activity is required.");
        }
        // Ownership: chỉ chủ sở hữu lộ trình mới ghi tiến độ; item người khác trả NOT_FOUND (không lộ tồn tại).
        UserLearningPathItem item = items.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Activity not found."));
        if (!"STUDIED".equals(item.getStudyStatus())) {
            item.setStudyStatus("STUDIED");
            items.save(item);
            // [JLAS-W0108] P2c — item không có bài nguồn (grammar/vocab) vẫn được thưởng; reference
            // theo item id với loại ITEM để không đụng reference "LESSON" của bài học.
            Long referenceId = item.getLesson() != null ? item.getLesson().getId() : item.getId();
            String referenceType = item.getLesson() != null ? "LESSON" : "ITEM";
            recordStreakAndCredit(userId, referenceType, referenceId,
                    "Hoàn thành hoạt động trong lộ trình");
        }
    }

    /** [JLAS-W0108][MP06] Bắt đầu/mở lại một activity. Không đè lên STUDIED — start chỉ có ý nghĩa
     * khi item còn NOT_STARTED; gọi lại khi đang IN_PROGRESS hoặc đã STUDIED là no-op (idempotent,
     * an toàn khi tải lại trang hoặc mở hai tab). */
    @Override
    @Transactional
    public void startItem(Long userId, Long itemId) {
        requirePositiveUserId(userId);
        if (itemId == null || itemId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An activity is required.");
        }
        UserLearningPathItem item = items.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Activity not found."));
        if ("NOT_STARTED".equals(item.getStudyStatus())) {
            item.setStudyStatus("IN_PROGRESS");
            items.save(item);
        }
    }

    /**
     * [JLAS-W0108] P4 — đạt lesson quiz thì ghi {@code assessmentStatus = PASSED} trên item thuộc bài
     * nguồn. Tách bạch với {@code completeLesson}/{@code completeItem} (study side): quiz không đánh dấu
     * "đã học xong" và ngược lại, nút hoàn thành nội dung không ghi PASSED.
     */
    private void markLessonQuizPassed(Long userId, Long lessonId) {
        for (UserLearningPathItem item : items.findByLessonIdAndUserId(lessonId, userId)) {
            if (!"PASSED".equals(item.getAssessmentStatus())) {
                item.setAssessmentStatus("PASSED");
                items.save(item);
            }
        }
    }

    /** [JLAS-W0108] P4 — đạt grammar quiz ghi assessment PASSED trên item warm-up cùng mẫu ngữ pháp. */
    private void markGrammarQuizPassed(Long userId, Long grammarPointId) {
        for (UserLearningPathItem item : items.findByGrammarPointIdAndUserId(grammarPointId, userId)) {
            if (!"PASSED".equals(item.getAssessmentStatus())) {
                item.setAssessmentStatus("PASSED");
                items.save(item);
            }
        }
    }

    /** [JLAS-W0108] P4/D3 — đạt flashcard quiz ghi assessment PASSED trên mọi item từ/Kanji trong membership. */
    private void markFlashcardQuizPassed(Long userId, Long pathId) {
        for (UserLearningPathItem item : items.findByLearningPathIdOrderByOrderIndex(pathId)) {
            if (WarmUpFlashcardService.isFlashcardItem(item) && !"PASSED".equals(item.getAssessmentStatus())) {
                item.setAssessmentStatus("PASSED");
                items.save(item);
            }
        }
    }

    /** [JLAS-W0108] Ghi streak + credit khi hoàn thành bài/activity. Các phụ thuộc là optional để slice test không cần dựng. */
    private void recordStreakAndCredit(Long userId, String referenceType, Long referenceId, String note) {
        if (streakRepository != null) {
            updateStreak(userId);
        }
        if (creditService != null) {
            creditService.earn(userId, STUDY_REWARD_PER_LESSON, CreditReason.STUDY_REWARD,
                    referenceType, referenceId, note);
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
        return submitSession(session, request);
    }

    @Override
    @Transactional
    public LessonQuizResponse getGateQuiz(Long userId) {
        requirePositiveUserId(userId);
        UserLearningPath path = requireActivePath(userId);
        requireGateNotLocked(userId);
        // [JLAS-W0108] P5 — gate (đầu ra chuyển chặng) chỉ phát khi mọi hoạt động warm-up bắt buộc đã đạt.
        requireWarmUpComplete(path);
        // [JLAS-W0108] Gate quiz chỉ hỏi nội dung đã warm-up (anchor→ngân hàng), không còn "vơ cả cấp".
        List<GateQuestion> gateQuestions = resolveGateQuiz(path);
        if (gateQuestions.isEmpty()) {
            // [JLAS-W0108] P5 — không phát gate rỗng rồi cho đạt; thiếu câu -> BLOCKED_CONTENT.
            throw new ApiException(ErrorCode.QUIZ_NOT_READY);
        }
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
        requireGateNotLocked(userId);
        LearningQuizSession session = loadOwnedSession(userId, QUIZ_TYPE_GATE, null, request.attemptId());
        return submitSession(session, request);
    }

    /**
     * [JLAS-W0108] P4 — warm-up grammar quiz: retest đúng mẫu ngữ pháp đã sai bằng câu ngân hàng gắn
     * {@code grammarPointId} (5 câu, khác câu đầu vào, cùng mẫu). Không có câu curated -> QUIZ_NOT_READY
     * (BLOCKED_CONTENT), không phát đề rỗng rồi cho đạt.
     */
    @Override
    @Transactional
    public LessonQuizResponse getGrammarQuiz(Long userId, Long grammarPointId) {
        requirePositiveUserId(userId);
        if (grammarPointId == null || grammarPointId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A grammar point is required.");
        }
        QuizData data = buildGrammarQuiz(grammarPointId);
        if (data.questions().isEmpty()) {
            throw new ApiException(ErrorCode.QUIZ_NOT_READY);
        }
        Long attemptId = issueActivitySession(userId, QUIZ_TYPE_GRAMMAR, grammarPointId, data);
        return new LessonQuizResponse(attemptId, grammarPointId, "Ôn tập ngữ pháp", data.questions());
    }

    /** [JLAS-W0108] P4 — chấm warm-up grammar quiz; đạt 4/5 ghi assessment PASSED trên item mẫu ngữ pháp. */
    @Override
    @Transactional
    public LessonQuizResult submitGrammarQuiz(Long userId, Long grammarPointId, LessonQuizSubmitRequest request) {
        requirePositiveUserId(userId);
        LearningQuizSession session = loadOwnedActivitySession(userId, grammarPointId, request.attemptId());
        return submitSession(session, request);
    }

    /**
     * [JLAS-W0108] P4/D3 — flashcard quiz: kiểm tra toàn bộ membership (từ/Kanji sai Smart Test) bằng
     * câu trắc nghiệm "chọn nghĩa". Không dùng tự đánh dấu nhớ thay quiz; phải đúng hết mới đạt (sai →
     * frontend vòng ôn lại đến đúng). Gắn version lộ trình để replan đổi membership thì làm lại đề.
     */
    @Override
    @Transactional
    public LessonQuizResponse getFlashcardQuiz(Long userId) {
        requirePositiveUserId(userId);
        UserLearningPath path = requireActivePath(userId);
        List<VocabularyEntry> membership = flashcardMembership(path.getId());
        if (membership.isEmpty()) {
            throw new ApiException(ErrorCode.QUIZ_NOT_READY);
        }
        QuizData data = buildFlashcardQuiz(membership);
        Long attemptId = issueSession(userId, QUIZ_TYPE_FLASHCARD, null, path.getId(), path.getVersion(), data);
        return new LessonQuizResponse(attemptId, null, "Kiểm tra bộ từ đã ôn", data.questions());
    }

    /** [JLAS-W0108] P4/D3 — chấm flashcard quiz; đúng hết membership ghi assessment PASSED trên các item từ/Kanji. */
    @Override
    @Transactional
    public LessonQuizResult submitFlashcardQuiz(Long userId, LessonQuizSubmitRequest request) {
        requirePositiveUserId(userId);
        LearningQuizSession session = loadOwnedSession(userId, QUIZ_TYPE_FLASHCARD, null, request.attemptId());
        return submitSession(session, request);
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
        String tagCode = lesson.getKnowledgeTag().getCode();
        List<AssessmentQuestion> bank = questionRepository
                .findByIsActiveTrueAndKnowledgeTagCodeOrderByIdAsc(tagCode)
                .stream().limit(LESSON_QUIZ_SIZE).toList();
        if (!bank.isEmpty()) {
            List<LessonQuizResponse.LessonQuizQuestion> questions = bank.stream()
                    .map(this::toQuizQuestion).toList();
            Map<Long, QuizAnswerKey> answerKey = bank.stream().collect(Collectors.toMap(
                    AssessmentQuestion::getId,
                    question -> new QuizAnswerKey(question.getCorrectOption(), question.getExplanation(),
                            "TAG:" + tagCode, tagCode)));
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

    /** [JLAS-W0108] P4 — nạp session quiz per-activity (GRAMMAR) theo activity_id (grammarPointId). */
    private LearningQuizSession loadOwnedActivitySession(Long userId, Long grammarPointId, Long attemptId) {
        if (attemptId == null || attemptId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "attemptId là bắt buộc.");
        }
        return quizSessions.findOwnedByActivity(attemptId, userId, QUIZ_TYPE_GRAMMAR, grammarPointId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN,
                        "Bài kiểm tra không hợp lệ hoặc đã hết hạn."));
    }

    /** [JLAS-W0108] P4 — phát session quiz per-activity (GRAMMAR), lưu snapshot + đáp án, trả attemptId. */
    private Long issueActivitySession(Long userId, String quizType, Long activityId, QuizData data) {
        var session = LearningQuizSession.builder()
                .user(em.getReference(User.class, userId))
                .quizType(quizType)
                .activityId(activityId)
                .status(QUIZ_STATUS_ISSUED)
                .questionsJson(mapper.writeQuizQuestions(data.questions()))
                .answerKeyJson(mapper.writeQuizAnswerKey(data.answerKey()))
                .questionCount(data.answerKey().size())
                .build();
        quizSessions.save(session);
        return session.getId();
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
    private LessonQuizResult submitSession(LearningQuizSession session, LessonQuizSubmitRequest request) {
        Long userId = session.getUser().getId();
        if (QUIZ_STATUS_SUBMITTED.equals(session.getStatus())) {
            return mapper.readQuizResult(session.getResultJson());
        }
        // [JLAS-W0108] Khoá user (như khi tạo path) để hai lần nộp đồng thời không cùng vượt đếm lượt.
        users.findForCreditUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found."));
        // Nạp lại dưới khoá: có thể đã bị nộp bởi transaction trước đó.
        LearningQuizSession fresh = reloadOwned(userId, session.getQuizType(), session.getLessonId(),
                session.getActivityId(), session.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "Bài kiểm tra không hợp lệ."));
        if (QUIZ_STATUS_SUBMITTED.equals(fresh.getStatus())) {
            return mapper.readQuizResult(fresh.getResultJson());
        }

        boolean gate = QUIZ_TYPE_GATE.equals(fresh.getQuizType());
        boolean pathAnchored = gate || QUIZ_TYPE_FLASHCARD.equals(fresh.getQuizType());
        UserLearningPath path = null;
        if (pathAnchored) {
            path = requireActivePath(userId);
            // [JLAS-W0108] Quiz gắn version lộ trình (gate/flashcard): lộ trình đã đổi thì làm lại đề mới.
            if (!path.getId().equals(fresh.getPathId()) || !path.getVersion().equals(fresh.getPathVersion())) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Lộ trình đã thay đổi. Hãy làm lại bài kiểm tra.");
            }
            // [JLAS-W0108] P5 — gate chặn khi warm-up chưa đạt; chặn TRƯỚC quota nên không mất lượt.
            if (gate) {
                requireWarmUpComplete(path);
            }
        }

        Map<Long, QuizAnswerKey> answerKey = mapper.readQuizAnswerKey(fresh.getAnswerKeyJson());
        GradeOutcome outcome = gradeAgainstKey(request.answers(), answerKey);

        // [JLAS-W0108] Quota khoá theo activity (grammarPointId) cho GRAMMAR quiz, theo lessonId cho các loại khác.
        Long quotaAnchor = QUIZ_TYPE_GRAMMAR.equals(fresh.getQuizType()) ? fresh.getActivityId() : fresh.getLessonId();
        assertQuizAttemptAllowed(userId, fresh.getQuizType(), quotaAnchor);
        recordQuizAttempt(userId, fresh.getQuizType(), quotaAnchor);

        int score = (int) Math.round(outcome.correct() * 100.0 / outcome.total());
        int passThreshold;
        boolean passed;
        if (gate) {
            // [JLAS-W0108] Gate quiz (P5) giữ ngưỡng tổng 60%.
            passThreshold = GATE_QUIZ_PASS_PERCENT;
            passed = score >= GATE_QUIZ_PASS_PERCENT;
        } else if (QUIZ_TYPE_GRAMMAR.equals(fresh.getQuizType())) {
            // [JLAS-W0108] P4 — grammar quiz đạt 4/5 (80%), không tách nhóm (một mẫu ngữ pháp duy nhất).
            passThreshold = GRAMMAR_QUIZ_PASS_PERCENT;
            passed = score >= GRAMMAR_QUIZ_PASS_PERCENT;
        } else if (QUIZ_TYPE_FLASHCARD.equals(fresh.getQuizType())) {
            // [JLAS-W0108] P4/D3 — flashcard quiz đạt khi đúng hết membership (sai → vòng ôn lại đến đúng).
            passThreshold = FLASHCARD_QUIZ_PASS_PERCENT;
            passed = score >= FLASHCARD_QUIZ_PASS_PERCENT;
        } else {
            // [JLAS-W0108] P4/D2 — lesson quiz: đạt tổng >= 80% VÀ từng nhóm mục tiêu >= 70% (điểm tổng không
            // che nhóm chưa đạt).
            passThreshold = LESSON_QUIZ_PASS_PERCENT;
            passed = score >= LESSON_QUIZ_PASS_PERCENT
                    && outcome.groupResults().stream().allMatch(LessonQuizResult.GroupResult::passed);
        }
        if (passed) {
            if (gate) {
                path.setGatePassedAt(LocalDateTime.now());
                paths.save(path);
            } else if (QUIZ_TYPE_GRAMMAR.equals(fresh.getQuizType())) {
                // [JLAS-W0108] P4 — đạt grammar quiz ghi assessment PASSED trên item warm-up cùng mẫu.
                markGrammarQuizPassed(userId, fresh.getActivityId());
            } else if (QUIZ_TYPE_FLASHCARD.equals(fresh.getQuizType())) {
                // [JLAS-W0108] P4/D3 — đạt flashcard quiz ghi assessment PASSED trên mọi item từ/Kanji.
                markFlashcardQuizPassed(userId, fresh.getPathId());
            } else {
                // [JLAS-W0108] P4 — đạt quiz ghi assessment PASSED trên item, KHÔNG hoàn thành study
                // (study ≠ PASSED). Nút "hoàn thành nội dung" (completeItem/completeLesson) giữ study.
                markLessonQuizPassed(userId, fresh.getLessonId());
            }
        }
        boolean relearnRequired = gate && !passed
                && gateFailuresToday(userId) >= MAX_GATE_FAILURES_BEFORE_RELEARN;

        LessonQuizResult result = new LessonQuizResult(outcome.total(), outcome.correct(), score, passed,
                passThreshold, relearnRequired, outcome.review(), outcome.groupResults());

        fresh.setStatus(QUIZ_STATUS_SUBMITTED);
        fresh.setCorrectCount(outcome.correct());
        fresh.setScorePercent(score);
        fresh.setPassed(passed);
        fresh.setResultJson(mapper.writeQuizResult(result));
        fresh.setSubmittedAt(LocalDateTime.now());
        quizSessions.save(fresh);
        return result;
    }

    /** [JLAS-W0108] Nạp lại session dưới khoá user; GRAMMAR quiz khóa theo activity_id, còn lại theo lesson_id. */
    private java.util.Optional<LearningQuizSession> reloadOwned(Long userId, String quizType, Long lessonId,
            Long activityId, Long attemptId) {
        if (QUIZ_TYPE_GRAMMAR.equals(quizType)) {
            return quizSessions.findOwnedByActivity(attemptId, userId, quizType, activityId);
        }
        return quizSessions.findOwned(attemptId, userId, quizType, lessonId);
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
        Map<String, Integer> groupCorrect = new LinkedHashMap<>();
        Map<String, Integer> groupTotal = new LinkedHashMap<>();
        Map<String, String> groupLabel = new LinkedHashMap<>();
        for (var entry : answerKey.entrySet()) {
            Long questionId = entry.getKey();
            QuizAnswerKey expected = entry.getValue();
            Integer chosen = selected.get(questionId);
            boolean isCorrect = chosen != null && chosen == expected.correctOption();
            if (isCorrect) correct++;
            review.add(new LessonQuizResult.AnswerReview(questionId, expected.correctOption(), chosen,
                    isCorrect, expected.explanation()));
            if (expected.groupKey() != null) {
                groupTotal.merge(expected.groupKey(), 1, Integer::sum);
                groupLabel.putIfAbsent(expected.groupKey(), expected.groupLabel());
                if (isCorrect) {
                    groupCorrect.merge(expected.groupKey(), 1, Integer::sum);
                }
            }
        }
        List<LessonQuizResult.GroupResult> groups = new ArrayList<>(groupTotal.size());
        for (String key : groupTotal.keySet()) {
            int groupQuestionCount = groupTotal.get(key);
            int required = requiredCorrect(groupQuestionCount, LESSON_QUIZ_GROUP_PASS_PERCENT);
            int groupScore = groupCorrect.getOrDefault(key, 0);
            groups.add(new LessonQuizResult.GroupResult(key, groupLabel.get(key), groupScore,
                    groupQuestionCount, required, groupScore >= required));
        }
        return new GradeOutcome(review, correct, answerKey.size(), groups);
    }

    /** [JLAS-W0108] P4/D2 — số câu đúng tối thiểu để đạt một nhóm/tổng: ceil(percent × total / 100). */
    private static int requiredCorrect(int total, int percent) {
        return (int) Math.ceil(total * percent / 100.0);
    }

    /** [JLAS-W0108] Kết quả chấm trung gian (review + đếm + nhóm) trước khi đóng gói {@link LessonQuizResult}. */
    private record GradeOutcome(List<LessonQuizResult.AnswerReview> review, int correct, int total,
            List<LessonQuizResult.GroupResult> groupResults) { }

    private UserLearningPath requireActivePath(Long userId) {
        return paths.findByUserIdAndStatus(userId, "ACTIVE")
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No active learning path found."));
    }

    /**
     * [JLAS-W0108] P5 — gate quiz (đầu ra chuyển chặng) chỉ được phát/nộp khi mọi hoạt động warm-up
     * bắt buộc đã đạt (assessmentStatus = PASSED). Item nền tảng bảng chữ cái ({@code KANA_FOUNDATION})
     * do chính gate kiểm tra nền tảng, không cần quiz trước gate nên được miễn. Chặn ở đây nên nộp
     * không tốn lượt và không phát gate rỗng để "vượt" chặng.
     */
    private void requireWarmUpComplete(UserLearningPath path) {
        boolean incomplete = items.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .filter(item -> LearningPhase.WARM_UP.name().equals(item.getPhase()))
                .anyMatch(item -> !REASON_KANA_FOUNDATION.equals(item.getReasonCode())
                        && !"PASSED".equals(item.getAssessmentStatus()));
        if (incomplete) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Hãy hoàn thành các hoạt động khởi động trước khi làm bài tổng kết.");
        }
    }

    private Set<String> warmUpTagCodes(Long pathId) {
        return items.findByLearningPathIdOrderByOrderIndex(pathId).stream()
                .filter(item -> LearningPhase.WARM_UP.name().equals(item.getPhase()))
                .flatMap(item -> mapper.readMatchedTags(item.getMatchedTagCodes()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * [JLAS-W0108] Câu gate quiz — "ngân hàng trước, không AI ở runtime", chỉ hỏi nội dung đã warm-up:
     * <ol>
     *   <li>KANJI/VOCAB gắn trực tiếp các từ đã warm-up ({@code vocabularyEntry});</li>
     *   <li>GRAMMAR gắn trực tiếp các mẫu ngữ pháp đã warm-up ({@code grammarPoint});</li>
     *   <li>câu theo knowledge tag làm fallback (WEAK_TAG / khi specific question chưa đủ), tối đa
     *       {@value #GATE_QUIZ_SIZE} câu curated;</li>
     *   <li>câu Kana deterministic cho warm-up bảng chữ cái.</li>
     * </ol>
     * [JLAS-W0108] P4/R9 — KHÔNG còn sinh câu AI bù ({@code learning_generated_question}) ở runtime:
     * mọi câu quiz chỉ đến từ ngân hàng {@code assessment_question} đã duyệt + Kana deterministic.
     * Anchor thiếu câu curated -> xử lý readiness/BLOCKED_CONTENT ở P5, không phát câu AI.
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

    /** [JLAS-W0108][MP08] Số lần gate quiz đã nộp trong ngày theo giờ VN (tất cả đều "chưa đạt" vì
     * đạt rồi thì không thi lại). */
    private long gateFailuresToday(Long userId) {
        return quizAttempts.countSince(userId, QUIZ_TYPE_GATE, null, clock.today().atStartOfDay());
    }

    /** [JLAS-W0108][MP-gate] Đã fail đủ {@value #MAX_GATE_FAILURES_BEFORE_RELEARN} lần gate quiz hôm
     * nay (giờ VN) thì chặn cả phát đề mới lẫn nộp đề đang cầm — trước đây chỉ trả cờ
     * {@code relearnRequired} cho FE tự xử lý, không có gì chặn thật ở server. Tự hết hạn khi
     * {@link #gateFailuresToday} tính lại sang ngày VN mới; chưa có đường mở khoá sớm (vd đổi CP). */
    private void requireGateNotLocked(Long userId) {
        if (gateFailuresToday(userId) >= MAX_GATE_FAILURES_BEFORE_RELEARN) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Bạn đã làm sai gate quiz " + MAX_GATE_FAILURES_BEFORE_RELEARN
                            + " lần hôm nay. Hãy ôn lại warm-up và quay lại vào ngày mai.");
        }
    }

    /** [JLAS-W0108][MP08] Ném 429 nếu user đã dùng hết {@value #MAX_QUIZ_ATTEMPTS_PER_DAY} lượt quiz
     * hôm nay theo giờ Asia/Ho_Chi_Minh (không phải timezone JVM). */
    private void assertQuizAttemptAllowed(Long userId, String quizType, Long lessonId) {
        long used = quizAttempts.countSince(userId, quizType, lessonId, clock.today().atStartOfDay());
        if (used >= MAX_QUIZ_ATTEMPTS_PER_DAY) {
            throw new ApiException(ErrorCode.QUIZ_ATTEMPT_LIMIT,
                    "Bạn đã dùng hết " + MAX_QUIZ_ATTEMPTS_PER_DAY
                            + " lượt quiz hôm nay. Hãy quay lại vào ngày mai.");
        }
    }

    /** [JLAS-W0108][MP08] Ghi nhận một lượt nộp quiz (tính cả khi chưa đạt), mốc giờ VN — khớp mốc
     * {@link #gateFailuresToday}/{@link #assertQuizAttemptAllowed} dùng để so sánh. */
    private void recordQuizAttempt(Long userId, String quizType, Long lessonId) {
        quizAttempts.save(QuizAttempt.builder()
                .user(em.getReference(User.class, userId))
                .quizType(quizType)
                .lessonId(lessonId)
                .attemptedAt(clock.now())
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
     *
     * [JLAS-W0108] P4/D2 — blueprint theo mục tiêu bài: mỗi mẫu ngữ pháp / từ vựng là một nhóm bắt buộc,
     * phủ đủ (KHÔNG giữ fixed {@value #LESSON_QUIZ_SIZE} khi bài có nhiều mục tiêu) để điểm tổng không che
     * nhóm chưa đạt.
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
                        "「" + pattern + "」nghĩa là «" + meaning + "»", meanings,
                        "GRAMMAR:" + point.getId(), "Ngữ pháp「" + pattern + "」"));
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
                        "「" + expression + "」nghĩa là «" + meaning + "»", meanings,
                        "VOCAB:" + entry.getId(), "Từ vựng「" + expression + "」"));
            }
        }
        List<LessonQuizResponse.LessonQuizQuestion> questions = new ArrayList<>();
        Map<Long, QuizAnswerKey> answerKey = new LinkedHashMap<>();
        for (int i = 0; i < specs.size(); i++) {
            McqSpec spec = specs.get(i);
            List<String> options = buildOptions(spec.correctOption(), spec.distractors(), i);
            int correct = options.indexOf(spec.correctOption()) + 1;
            questions.add(new LessonQuizResponse.LessonQuizQuestion(spec.questionId(), spec.skill(), null,
                    null, null, null, null, spec.questionText(), options, "MCQ"));
            answerKey.put(spec.questionId(),
                    new QuizAnswerKey(correct, spec.explanation(), spec.groupKey(), spec.groupLabel()));
        }
        return new QuizData(questions, answerKey);
    }

    /**
     * [JLAS-W0108] P4 — warm-up grammar quiz: câu ngân hàng gắn {@code grammarPointId}, tối đa
     * {@value #GRAMMAR_QUIZ_SIZE} câu (khác câu đầu vào Smart Test, cùng mẫu). Không có câu curated -> đề
     * rỗng (caller trả QUIZ_NOT_READY). Không tách nhóm vì chỉ một mẫu ngữ pháp duy nhất.
     */
    private QuizData buildGrammarQuiz(Long grammarPointId) {
        List<AssessmentQuestion> bank = questionRepository
                .findByIsActiveTrueAndGrammarPointIdInOrderByIdAsc(List.of(grammarPointId))
                .stream().limit(GRAMMAR_QUIZ_SIZE).toList();
        List<LessonQuizResponse.LessonQuizQuestion> questions = bank.stream()
                .map(this::toQuizQuestion).toList();
        Map<Long, QuizAnswerKey> answerKey = bank.stream().collect(Collectors.toMap(
                AssessmentQuestion::getId,
                question -> new QuizAnswerKey(question.getCorrectOption(), question.getExplanation())));
        return new QuizData(questions, answerKey);
    }

    /**
     * [JLAS-W0108] P4/D3 — membership flashcard quiz: các từ/Kanji yếu (WEAK_* + REMEDIATION_*) đã khử trùng
     * theo identity (expression+reading), sắp theo id để phát đề ổn định. KHÔNG cắt 10 (batching là việc UI),
     * hỏi hết membership.
     */
    private List<VocabularyEntry> flashcardMembership(Long pathId) {
        if (vocabularyEntries == null) {
            return List.of();
        }
        List<Long> ids = items.findVocabularyEntryIdsIn(pathId, WarmUpFlashcardService.WARM_UP_WEAK_REASON_CODES);
        var distinct = new LinkedHashMap<String, VocabularyEntry>();
        vocabularyEntries.findAllById(ids).stream()
                .filter(entry -> entry.getExpression() != null && !entry.getExpression().isBlank()
                        && entry.getMeaning() != null && !entry.getMeaning().isBlank())
                .forEach(entry -> distinct.putIfAbsent(
                        (entry.getExpression().strip()) + "\u0000" + (entry.getReading() == null ? "" : entry.getReading().strip()),
                        entry));
        return distinct.values().stream()
                .sorted(Comparator.comparing(VocabularyEntry::getId))
                .toList();
    }

    /** [JLAS-W0108] P4/D3 — đề flashcard quiz: mỗi membership một câu "chọn nghĩa của từ", nhiễu từ chính membership. */
    private QuizData buildFlashcardQuiz(List<VocabularyEntry> membership) {
        List<String> meanings = distinctNonBlank(membership.stream().map(VocabularyEntry::getMeaning).toList());
        List<LessonQuizResponse.LessonQuizQuestion> questions = new ArrayList<>();
        Map<Long, QuizAnswerKey> answerKey = new LinkedHashMap<>();
        int i = 0;
        for (VocabularyEntry entry : membership) {
            String expression = nonBlank(entry.getExpression());
            String meaning = nonBlank(entry.getMeaning());
            if (expression.isEmpty() || meaning.isEmpty()) {
                continue;
            }
            long questionId = -3_000_000L - entry.getId();
            List<String> options = buildOptions(meaning, meanings, i);
            int correct = options.indexOf(meaning) + 1;
            questions.add(new LessonQuizResponse.LessonQuizQuestion(questionId, "VOCABULARY", entry.getJlptLevel(),
                    null, null, null, null, "Chọn nghĩa đúng của từ「" + expression + "」", options, "MCQ"));
            answerKey.put(questionId, new QuizAnswerKey(correct, "「" + expression + "」nghĩa là «" + meaning + "»"));
            i++;
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
            String correctOption, String explanation, List<String> distractors,
            String groupKey, String groupLabel) { }

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
        return mapper.toResponse(path, pathItems,
                stageRepository.findByLearningPathIdOrderByStageIndexAsc(path.getId()),
                warmUpFlashcards != null && warmUpFlashcards.isCompleted(path));
    }

    private void supersedeActive(Long userId) {
        paths.findByUserIdAndStatus(userId, "ACTIVE").ifPresent(active -> {
            active.setStatus("SUPERSEDED");
            paths.save(active);
        });
    }

    /**
     * [JLAS-W0108] P6/D5 — tạo các chặng nối tiếp theo thứ tự scope đã resolve. Chặng đầu ACTIVE, các
     * chặng sau PENDING (chỉ mở khi đánh giá cuối chặng trước đạt). Trả về danh sách theo stage_index.
     */
    private List<UserLearningStage> createStages(UserLearningPath path, List<LearningStageScope> stageScopes) {
        List<UserLearningStage> result = new ArrayList<>();
        int index = 0;
        for (LearningStageScope scope : stageScopes) {
            result.add(UserLearningStage.builder()
                    .learningPath(path)
                    .level(scope.level())
                    .stageIndex(index)
                    .curriculumVersion(scope.curriculumVersion())
                    .assessmentCycle("1")
                    .status(index == 0 ? "ACTIVE" : "PENDING")
                    .build());
            index++;
        }
        return stageRepository.saveAll(result);
    }

    /**
     * [JLAS-W0108] P6 — gán chặng cho từng activity: WARM_UP thuộc chặng đầu (điểm yếu cấp đã kết luận);
     * GOAL_LEARNING theo đúng cấp mục tiêu của bài. Scope cũ không phân chặng trả null (tương thích).
     */
    private Long stageIdFor(ScheduledLearningResource scheduled, Map<String, UserLearningStage> stageByLevel,
            UserLearningStage firstStage) {
        if (stageByLevel.isEmpty()) {
            return null;
        }
        if (scheduled.phase() == LearningPhase.WARM_UP) {
            return firstStage == null ? null : firstStage.getId();
        }
        UserLearningStage stage = stageByLevel.get(scheduled.resource().level());
        return stage != null ? stage.getId() : (firstStage == null ? null : firstStage.getId());
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
