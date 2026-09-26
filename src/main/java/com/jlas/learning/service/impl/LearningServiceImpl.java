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
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.learning.dto.CreateLearningPathResult;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.LearningSelectionResult;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.LessonQuizSubmitRequest;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.entity.LessonProgress;
import com.jlas.learning.entity.QuizAttempt;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LessonProgressRepository;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cài đặt {@link LearningService}. */
@Service
@RequiredArgsConstructor
public class LearningServiceImpl implements LearningService {
    private final UserRepository users;
    private final UserLearningPathRepository paths;
    private final UserLearningPathItemRepository items;
    private final LessonProgressRepository progress;
    private final LessonRepository lessons;
    private final AssessmentQuestionRepository questionRepository;
    private final QuizAttemptRepository quizAttempts;
    private final LearningResourceSelectionService selector;
    private final LearningScheduleService scheduler;
    private final LearningMapper mapper;
    private final ObjectProvider<LearningProfileSource> profileSources;
    private final ObjectProvider<LearningScopeSource> scopeSources;
    private final ObjectProvider<WeaknessNotebook> notebook;
    private final EntityManager em;

    /** [JLAS-W0113] Số câu tối đa cho một lesson quiz. */
    private static final int LESSON_QUIZ_SIZE = 5;
    /** [JLAS-W0113] Ngưỡng đạt quiz (%). */
    private static final int LESSON_QUIZ_PASS_PERCENT = 60;
    /** [JLAS-W0113] Số câu tối đa cho gate quiz tổng kết warm-up. */
    private static final int GATE_QUIZ_SIZE = 10;
    /** [JLAS-W0108] Số câu đọc/nghe bổ sung vào gate quiz (5 đọc + 5 nghe theo cấp sai). */
    private static final int GATE_COMPREHENSION_SIZE = 5;
    /** [JLAS-W0108] Giới hạn số lượt nộp quiz mỗi ngày (áp cho cả gate quiz lẫn lesson quiz). */
    private static final int MAX_QUIZ_ATTEMPTS_PER_DAY = 5;
    /** [JLAS-W0108] Loại quiz dùng cho bảng đếm lượt. */
    private static final String QUIZ_TYPE_GATE = "GATE";
    private static final String QUIZ_TYPE_LESSON = "LESSON";
    /** [JLAS-W0108] Số lần gate quiz chưa đạt trong ngày trước khi yêu cầu ôn lại (fail 2 lần -> học lại). */
    private static final int MAX_GATE_FAILURES_BEFORE_RELEARN = 2;

    @Override
    @Transactional
    public CreateLearningPathResult create(Long userId, Long assessmentAttemptId) {
        requirePositiveUserId(userId);
        if (assessmentAttemptId == null || assessmentAttemptId <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A finalized assessment attempt is required.");
        }
        // Lock the user row up front: this validates the user exists (404) and serializes
        // concurrent path creation before version/idempotency decisions. findForCreditUpdate is
        // the auth module's existing pessimistic-write lock on the user row.
        User user = users.findForCreditUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found."));
        LearningProfileSource profileSource = profileSources.getIfAvailable(() -> {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Learning profile source is not configured yet.");
        });
        LearningScopeSource scopeSource = scopeSources.getIfAvailable(() -> {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Curriculum scope source is not configured yet.");
        });
        LearningProfileSnapshot profile = profileSource.loadForUser(userId, assessmentAttemptId);
        if (profile == null || !userId.equals(profile.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        // [JLAS-W0108] Defense-in-depth: path cho target N2/N1 bị chặn ngay cả khi entry khác lọt qua.
        if (profile.targetLevel() != null && !JlptLevel.isAvailableForLearning(profile.targetLevel())) {
            throw new ApiException(ErrorCode.TARGET_NOT_AVAILABLE,
                    "Cấp độ " + profile.targetLevel() + " đang được phát triển. Hiện chỉ hỗ trợ N5, N4 và N3.");
        }
        LearningCatalogScope scope = scopeSource.resolve(userId, profile);
        LearningSelectionResult selection = selector.select(userId, profile, scope);
        var schedule = scheduler.schedule(selection.resources(), profile.minutesPerDay());

        // The user-row lock above already serializes concurrent creation for this user.
        var existing = paths.findByUserIdAndAssessmentAttemptIdAndAssessmentRevisionAndPolicyVersion(
                userId, profile.assessmentAttemptId(), profile.assessmentRevision(), selection.policyVersion());
        if (existing.isPresent()) {
            return new CreateLearningPathResult(loadResponse(existing.get()), false);
        }

        // [JLAS-W0108] Lát 2.4 — ghi điểm yếu đã kết luận vào Sổ tay (Weakness Notebook) trước khi
        // tạo path mới. Notebook là Optional để slice test (DataJpaTest) không cần dựng bean này.
        var notebook = this.notebook.getIfAvailable();
        if (notebook != null) {
            notebook.record(user, profile.assessmentAttemptId(), selection.weakEvidence());
        }

        int nextVersion = paths.findMaxVersionByUserId(userId) + 1;
        supersedeActive(userId);

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
        }
    }

    @Override
    @Transactional(readOnly = true)
    public LessonQuizResponse getLessonQuiz(Long userId, Long lessonId) {
        requirePositiveUserId(userId);
        Lesson lesson = requireLesson(lessonId);
        List<LessonQuizResponse.LessonQuizQuestion> questions = questionRepository
                .findByIsActiveTrueAndKnowledgeTagCodeOrderByIdAsc(lesson.getKnowledgeTag().getCode())
                .stream().limit(LESSON_QUIZ_SIZE).map(this::toQuizQuestion).toList();
        return new LessonQuizResponse(lessonId, lesson.getTitle(), questions);
    }

    @Override
    @Transactional
    public LessonQuizResult submitLessonQuiz(Long userId, Long lessonId, LessonQuizSubmitRequest request) {
        requirePositiveUserId(userId);
        Lesson lesson = requireLesson(lessonId);
        List<LessonQuizSubmitRequest.LessonQuizAnswerRequest> answers = request.answers();
        if (answers == null || answers.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Vui lòng trả lời ít nhất một câu.");
        }
        // [JLAS-W0108] Lát C — chặn lượt nộp thứ 6 trong ngày cho chính quiz này.
        assertQuizAttemptAllowed(userId, QUIZ_TYPE_LESSON, lessonId);
        recordQuizAttempt(userId, QUIZ_TYPE_LESSON, lessonId);
        Map<Long, AssessmentQuestion> bank = questionRepository
                .findByIsActiveTrueAndKnowledgeTagCodeOrderByIdAsc(lesson.getKnowledgeTag().getCode())
                .stream().limit(LESSON_QUIZ_SIZE)
                .collect(Collectors.toMap(AssessmentQuestion::getId, Function.identity()));
        int correct = 0;
        List<LessonQuizResult.AnswerReview> review = new ArrayList<>(answers.size());
        for (var answer : answers) {
            AssessmentQuestion question = bank.get(answer.questionId());
            if (question == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Câu hỏi không thuộc bài học này.");
            }
            boolean isCorrect = answer.selectedOption() != null
                    && answer.selectedOption().equals(question.getCorrectOption());
            if (isCorrect) correct++;
            review.add(new LessonQuizResult.AnswerReview(question.getId(), question.getCorrectOption(),
                    answer.selectedOption(), isCorrect, question.getExplanation()));
        }
        int total = review.size();
        int scorePercent = total == 0 ? 0 : (int) Math.round(correct * 100.0 / total);
        boolean passed = scorePercent >= LESSON_QUIZ_PASS_PERCENT;
        boolean completed = false;
        if (passed) {
            completeLesson(userId, lessonId);
            completed = true;
        }
        return new LessonQuizResult(total, correct, scorePercent, passed, LESSON_QUIZ_PASS_PERCENT, completed, false, review);
    }

    @Override
    @Transactional(readOnly = true)
    public LessonQuizResponse getGateQuiz(Long userId) {
        requirePositiveUserId(userId);
        UserLearningPath path = requireActivePath(userId);
        List<LessonQuizResponse.LessonQuizQuestion> questions = new ArrayList<>(gateCoreQuestions(path.getId())
                .stream().map(this::toQuizQuestion).toList());
        // [JLAS-W0108] Lát C — gate quiz gồm cả câu nghe + đọc cùng cấp sai (fallback question bank).
        questions.addAll(comprehensionQuestions(path.getCurrentLevel(), "READING"));
        questions.addAll(comprehensionQuestions(path.getCurrentLevel(), "LISTENING"));
        return new LessonQuizResponse(null, "Kiểm tra tổng kết khởi động", questions);
    }

    @Override
    @Transactional
    public LessonQuizResult submitGateQuiz(Long userId, LessonQuizSubmitRequest request) {
        requirePositiveUserId(userId);
        UserLearningPath path = requireActivePath(userId);
        List<LessonQuizSubmitRequest.LessonQuizAnswerRequest> answers = request.answers();
        if (answers == null || answers.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Vui lòng trả lời ít nhất một câu.");
        }
        // [JLAS-W0108] Lát C — chặn lượt nộp thứ 6 trong ngày cho gate quiz.
        assertQuizAttemptAllowed(userId, QUIZ_TYPE_GATE, null);
        recordQuizAttempt(userId, QUIZ_TYPE_GATE, null);
        Map<Long, AssessmentQuestion> bank = new LinkedHashMap<>();
        gateCoreQuestions(path.getId()).forEach(q -> bank.put(q.getId(), q));
        addComprehensionToBank(bank, path.getCurrentLevel(), "READING");
        addComprehensionToBank(bank, path.getCurrentLevel(), "LISTENING");
        int correct = 0;
        List<LessonQuizResult.AnswerReview> review = new ArrayList<>(answers.size());
        for (var answer : answers) {
            AssessmentQuestion question = bank.get(answer.questionId());
            if (question == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Câu hỏi không thuộc bài kiểm tra tổng kết.");
            }
            boolean isCorrect = answer.selectedOption() != null
                    && answer.selectedOption().equals(question.getCorrectOption());
            if (isCorrect) correct++;
            review.add(new LessonQuizResult.AnswerReview(question.getId(), question.getCorrectOption(),
                    answer.selectedOption(), isCorrect, question.getExplanation()));
        }
        int total = review.size();
        int scorePercent = total == 0 ? 0 : (int) Math.round(correct * 100.0 / total);
        boolean passed = scorePercent >= LESSON_QUIZ_PASS_PERCENT;
        // [JLAS-W0108] Lát C — fail đủ 2 lần trong ngày thì yêu cầu ôn lại phần khởi động trước khi thử lại.
        boolean relearnRequired = !passed && gateFailuresToday(userId) >= MAX_GATE_FAILURES_BEFORE_RELEARN;
        if (passed) {
            path.setGatePassedAt(LocalDateTime.now());
            paths.save(path);
        }
        return new LessonQuizResult(total, correct, scorePercent, passed, LESSON_QUIZ_PASS_PERCENT, passed, relearnRequired, review);
    }

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
     * [JLAS-W0108] Câu "lõi" của gate quiz — đủ kĩ năng đã warm-up thay vì chỉ gom theo tag:
     * <ol>
     *   <li>KANJI/VOCAB gắn trực tiếp các từ đã warm-up ({@code vocabularyEntry});</li>
     *   <li>GRAMMAR gắn trực tiếp các mẫu ngữ pháp đã warm-up ({@code grammarPoint});</li>
     *   <li>câu theo knowledge tag làm fallback (WEAK_TAG / khi specific question chưa đủ), tối đa
     *       {@value #GATE_QUIZ_SIZE} câu.</li>
     * </ol>
     */
    private List<AssessmentQuestion> gateCoreQuestions(Long pathId) {
        var ordered = new LinkedHashMap<Long, AssessmentQuestion>();
        var vocabIds = items.findVocabularyEntryIdsIn(pathId,
                List.of("WEAK_KANJI_ITEM", "WEAK_VOCAB_ITEM"));
        if (!vocabIds.isEmpty()) {
            questionRepository.findByIsActiveTrueAndVocabularyEntryIdInOrderByIdAsc(vocabIds)
                    .forEach(question -> ordered.put(question.getId(), question));
        }
        var grammarIds = items.findGrammarPointIdsIn(pathId, List.of("WEAK_GRAMMAR_POINT"));
        if (!grammarIds.isEmpty()) {
            questionRepository.findByIsActiveTrueAndGrammarPointIdInOrderByIdAsc(grammarIds)
                    .forEach(question -> ordered.put(question.getId(), question));
        }
        questionRepository.findByIsActiveTrueAndKnowledgeTagCodeInOrderByIdAsc(warmUpTagCodes(pathId))
                .forEach(question -> {
                    if (ordered.size() < GATE_QUIZ_SIZE) {
                        ordered.put(question.getId(), question);
                    }
                });
        return ordered.values().stream().limit(GATE_QUIZ_SIZE).toList();
    }

    /** [JLAS-W0108] Số lần gate quiz đã nộp trong ngày (tất cả đều là "chưa đạt" vì đạt rồi thì không thi lại). */
    private long gateFailuresToday(Long userId) {
        return quizAttempts.countSince(userId, QUIZ_TYPE_GATE, null, LocalDate.now().atStartOfDay());
    }

    /** [JLAS-W0108] Câu đọc/nghe ở một cấp độ cho gate quiz (fallback question bank). */
    private List<LessonQuizResponse.LessonQuizQuestion> comprehensionQuestions(String level, String skill) {
        if (level == null || level.isBlank()) return List.of();
        return questionRepository.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc(skill, level)
                .stream().limit(GATE_COMPREHENSION_SIZE).map(this::toQuizQuestion).toList();
    }

    /** [JLAS-W0108] Gộp câu đọc/nghe vào ngân hàng chấm của gate quiz (khớp với {@link #getGateQuiz}). */
    private void addComprehensionToBank(Map<Long, AssessmentQuestion> bank, String level, String skill) {
        if (level == null || level.isBlank()) return;
        questionRepository.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc(skill, level)
                .stream().limit(GATE_COMPREHENSION_SIZE).forEach(q -> bank.put(q.getId(), q));
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
                        text(question.getOption3()), text(question.getOption4())));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private LearningPathResponse loadResponse(UserLearningPath path) {
        List<UserLearningPathItem> pathItems = items.findWithLessonByLearningPathId(path.getId());
        if (pathItems.isEmpty() || pathItems.stream().anyMatch(item -> item.getPhase() == null
                || item.getDayNumber() == null || item.getEstimatedMinutes() == null)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "This learning path predates the two-phase schedule; please regenerate it.");
        }
        Set<Long> completed = progress.findCompletedLessonIds(path.getUser().getId());
        return mapper.toResponse(path, pathItems, completed);
    }

    private void supersedeActive(Long userId) {
        paths.findByUserIdAndStatus(userId, "ACTIVE").ifPresent(active -> {
            active.setStatus("SUPERSEDED");
            paths.save(active);
        });
    }

    private String buildTitle(LearningProfileSnapshot profile) {
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
