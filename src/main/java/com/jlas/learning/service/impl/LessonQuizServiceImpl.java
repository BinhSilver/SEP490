package com.jlas.learning.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.entity.AssessmentAnswer;
import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.entity.AssessmentQuiz;
import com.jlas.assessment.entity.AssessmentQuizAttempt;
import com.jlas.assessment.entity.AssessmentQuizQuestion;
import com.jlas.auth.entity.User;
import com.jlas.common.event.LearningEvent;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.QuizAnswerDetailResponse;
import com.jlas.learning.dto.QuizAnswerFeedbackResponse;
import com.jlas.learning.dto.QuizAnswerSubmission;
import com.jlas.learning.dto.QuizQuestionResponse;
import com.jlas.learning.dto.QuizIntegrityRequest;
import com.jlas.learning.dto.QuizIntegrityResponse;
import com.jlas.learning.dto.QuizResultResponse;
import com.jlas.learning.dto.QuizStartResponse;
import com.jlas.learning.dto.QuizSubmitRequest;
import com.jlas.learning.dto.QuizSubmitResponse;
import com.jlas.learning.repository.LessonQuizAnswerRepository;
import com.jlas.learning.repository.LessonQuizAttemptDetailRepository;
import com.jlas.learning.repository.LessonQuizAttemptRepository;
import com.jlas.learning.repository.LessonQuizQuestionRepository;
import com.jlas.learning.repository.LessonQuizRepository;
import com.jlas.learning.service.LearningProgressService;
import com.jlas.learning.service.LessonAccessPolicy;
import com.jlas.learning.service.LessonQuizService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link LessonQuizService} on the shared assessment entities. */
@Service
@RequiredArgsConstructor
public class LessonQuizServiceImpl implements LessonQuizService {

    private static final String ATTEMPT_TYPE_QUIZ = "QUIZ";
    private static final String QUIZ_TYPE_LESSON = "LESSON";
    private static final String QUIZ_TYPE_UNIT_FINAL = "UNIT_FINAL";
    /** Answers sent this long after the deadline still count (network delay, the page auto-submitting). */
    private static final long DEADLINE_GRACE_SECONDS = 5;
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_ABANDONED = "ABANDONED";
    private static final String MULTIPLE_CHOICE = "MULTIPLE_CHOICE";
    private static final String FILL_BLANK = "FILL_BLANK";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal PASS_THRESHOLD = BigDecimal.valueOf(PASS_PERCENT);

    private final LessonQuizRepository lessonQuizRepository;
    private final LessonQuizQuestionRepository questionRepository;
    private final LessonQuizAttemptRepository attemptRepository;
    private final LessonQuizAttemptDetailRepository attemptDetailRepository;
    private final LessonQuizAnswerRepository answerRepository;
    private final LearningProgressService learningProgress;
    private final List<LessonAccessPolicy> accessPolicies;
    private final ObjectMapper objectMapper;
    private final EntityManager em;
    private final ApplicationEventPublisher events;
    private final com.jlas.learning.repository.QuizIntegrityRepository integrity;

    @Override
    @Transactional
    public QuizStartResponse start(Long userId, Long lessonId) {
        accessPolicies.forEach(policy -> policy.checkLesson(userId, lessonId));
        AssessmentQuiz quiz = lessonQuizRepository.findPublishedLessonQuiz(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Quiz not found for this lesson."));
        List<AssessmentQuizQuestion> supported = supportedQuestionsOf(quiz);
        learningProgress.start(userId, lessonId);
        return open(userId, quiz, lessonId, supported);
    }

    @Override
    @Transactional
    public QuizStartResponse startUnitFinal(Long userId, Long unitId) {
        accessPolicies.forEach(policy -> policy.checkUnitFinal(userId, unitId));
        AssessmentQuiz quiz = lessonQuizRepository.findPublishedUnitFinals(unitId).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Final test not found for this unit."));
        return open(userId, quiz, null, supportedQuestionsOf(quiz));
    }

    @Override
    @Transactional
    public QuizAnswerFeedbackResponse answer(Long userId, Long attemptId, QuizAnswerSubmission submission) {
        if (submission == null || submission.questionId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The answer must reference a question.");
        }
        AssessmentAttempt attempt = ownedAttemptForUpdate(userId, attemptId);
        if (!STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Quiz has already been submitted.");
        }
        AssessmentQuiz answeredQuiz = attemptDetailRepository.findWithQuizByAttemptId(attemptId)
                .map(AssessmentQuizAttempt::getQuiz).orElse(null);
        if (isTimed(answeredQuiz)) {
            if (secondsLeft(attempt) + DEADLINE_GRACE_SECONDS <= 0) {
                throw new ApiException(ErrorCode.CONFLICT, "Đã hết giờ làm bài, bài được nộp với các câu đã trả lời.");
            }
            if (integrity.leavingCount(attemptId) >= LEAVE_LIMIT) {
                throw new ApiException(ErrorCode.CONFLICT, "Bạn đã rời khỏi bài quá " + LEAVE_LIMIT
                        + " lần, bài được nộp với các câu đã trả lời.");
            }
        }
        Optional<AssessmentAnswer> recorded = answerRepository
                .findWithQuestionByAttemptIdAndQuestionId(attemptId, submission.questionId());
        if (isTimed(answeredQuiz)) {
            // Timed test: the choice is saved (and may be changed until submitting) but not revealed.
            if (recorded.isPresent()) {
                AssessmentAnswer answer = recorded.get();
                AssessmentAnswer regraded = gradedAnswer(attempt, answer.getQuestion(), submission);
                answer.setSelectedOption(regraded.getSelectedOption());
                answer.setAnswerText(regraded.getAnswerText());
                answer.setIsCorrect(regraded.getIsCorrect());
                answer.setAnsweredAt(LocalDateTime.now());
                return toHiddenFeedback(answerRepository.save(answer));
            }
        } else if (recorded.isPresent()) {
            return toFeedback(recorded.get());
        }
        AssessmentQuestion question = questionsOfAttempt(attemptId).stream()
                .map(AssessmentQuizQuestion::getQuestion)
                .filter(candidate -> candidate.getId().equals(submission.questionId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Answer references a question outside this quiz."));
        AssessmentAnswer saved = answerRepository.save(gradedAnswer(attempt, question, submission));
        return isTimed(answeredQuiz) ? toHiddenFeedback(saved) : toFeedback(saved);
    }

    @Override
    @Transactional
    public QuizSubmitResponse submit(Long userId, Long attemptId, QuizSubmitRequest request) {
        AssessmentAttempt attempt = ownedAttemptForUpdate(userId, attemptId);
        if (!STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Quiz has already been submitted.");
        }
        AssessmentQuizAttempt quizAttempt = attemptDetailRepository.findWithQuizByAttemptId(attemptId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Quiz attempt not found."));
        List<AssessmentQuizQuestion> links = questionRepository.findWithQuestionByQuizId(quizAttempt.getQuiz().getId());
        List<AssessmentQuizQuestion> supported = forAttempt(attemptId, supportedQuestions(links));
        if (supported.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Quiz has no questions to submit.");
        }

        Map<Long, QuizAnswerSubmission> submissions = indexSubmissions(request);
        // A timed test may be submitted (or closed by the clock) with questions left blank: they count as wrong.
        boolean blanksAllowed = isTimed(quizAttempt.getQuiz());
        // A timed test closed by the clock or by leaving it keeps only the answers recorded while it was open.
        if (isTimed(quizAttempt.getQuiz()) && (secondsLeft(attempt) + DEADLINE_GRACE_SECONDS <= 0
                || integrity.leavingCount(attemptId) >= LEAVE_LIMIT)) {
            submissions.clear();
        }
        Map<Long, AssessmentAnswer> recorded = new HashMap<>();
        answerRepository.findWithQuestionByAttemptId(attemptId)
                .forEach(answer -> recorded.put(answer.getQuestion().getId(), answer));
        List<AssessmentAnswer> newAnswers = new ArrayList<>(supported.size());
        BigDecimal totalScore = BigDecimal.ZERO;
        BigDecimal earnedScore = BigDecimal.ZERO;
        int correctCount = 0;
        for (AssessmentQuizQuestion link : supported) {
            AssessmentQuestion question = link.getQuestion();
            QuizAnswerSubmission submission = submissions.remove(question.getId());
            // an answer already checked during the attempt keeps its result
            AssessmentAnswer answer = recorded.get(question.getId());
            if (answer == null) {
                answer = submission == null && blanksAllowed ? blankAnswer(attempt, question)
                        : gradedAnswer(attempt, question, submission);
                newAnswers.add(answer);
            }
            BigDecimal rawScore = link.getRawScore() != null ? link.getRawScore() : BigDecimal.ONE;
            totalScore = totalScore.add(rawScore);
            if (Boolean.TRUE.equals(answer.getIsCorrect())) {
                earnedScore = earnedScore.add(rawScore);
                correctCount++;
            }
        }
        if (!submissions.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Answer references a question outside this quiz.");
        }

        BigDecimal scorePercent = totalScore.signum() > 0
                ? earnedScore.multiply(ONE_HUNDRED).divide(totalScore, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        answerRepository.saveAll(newAnswers);
        attempt.setStatus(STATUS_SUBMITTED);
        attempt.setSubmittedAt(LocalDateTime.now());
        attempt.setScorePercent(scorePercent);
        attemptRepository.save(attempt);
        quizAttempt.setScorePercent(scorePercent);
        attemptDetailRepository.save(quizAttempt);
        events.publishEvent(LearningEvent.lessonQuizCompleted(userId, attemptId));
        boolean passed = scorePercent.compareTo(PASS_THRESHOLD) >= 0;
        if (passed) {
            Long lessonId = completedLessonOf(quizAttempt);
            if (lessonId != null) {
                learningProgress.complete(userId, lessonId);
            }
        }
        return new QuizSubmitResponse(attemptId, scorePercent, correctCount, supported.size(), passed);
    }

    @Override
    @Transactional
    public QuizSubmitResponse finishForDemo(Long userId, Long attemptId, boolean correct) {
        AssessmentAttempt attempt = ownedAttemptForUpdate(userId, attemptId);
        if (!STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Quiz has already been submitted.");
        }
        Map<Long, AssessmentAnswer> recorded = new HashMap<>();
        answerRepository.findWithQuestionByAttemptId(attemptId)
                .forEach(answer -> recorded.put(answer.getQuestion().getId(), answer));
        for (AssessmentQuizQuestion link : questionsOfAttempt(attemptId)) {
            AssessmentQuestion question = link.getQuestion();
            AssessmentAnswer answer = recorded.get(question.getId());
            if (answer == null) {
                answer = AssessmentAnswer.builder().attempt(attempt).question(question).build();
            }
            boolean choice = MULTIPLE_CHOICE.equals(question.getQuestionType());
            int right = correctOptionIndex(question);
            int options = optionsOf(question).size();
            // a wrong choice is any other option the learner can see
            Integer picked = !choice ? null : correct ? (right > 0 ? right : null) : (options < 2 ? null : right == 1 ? 2 : 1);
            answer.setSelectedOption(picked);
            answer.setAnswerText(!FILL_BLANK.equals(question.getQuestionType()) ? null
                    : correct ? question.getAnswerText() : "demo-sai");
            answer.setIsCorrect(correct);
            answer.setAnsweredAt(LocalDateTime.now());
            answerRepository.save(answer);
        }
        return submit(userId, attemptId, new QuizSubmitRequest(List.of()));
    }

    @Override
    @Transactional
    public long secondsLeftForDemo(Long userId, Long attemptId, int seconds) {
        AssessmentAttempt attempt = ownedAttemptForUpdate(userId, attemptId);
        AssessmentQuiz quiz = attemptDetailRepository.findWithQuizByAttemptId(attemptId)
                .map(AssessmentQuizAttempt::getQuiz).orElse(null);
        if (!isTimed(quiz) || !STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only an open timed test has a clock.");
        }
        int left = Math.max(1, Math.min(UNIT_FINAL_TIME_LIMIT_SECONDS, seconds));
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(UNIT_FINAL_TIME_LIMIT_SECONDS - left);
        // started_at is not updatable through the entity mapping, so the column is set directly
        em.createNativeQuery("UPDATE assessment_attempt SET started_at = ?1 WHERE id = ?2")
                .setParameter(1, startedAt)
                .setParameter(2, attemptId)
                .executeUpdate();
        em.refresh(attempt);
        return secondsLeft(attempt);
    }

    @Override
    @Transactional
    public void abandon(Long userId, Long attemptId) {
        AssessmentAttempt attempt = ownedAttemptForUpdate(userId, attemptId);
        if (STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            attempt.setStatus(STATUS_ABANDONED);
            attemptRepository.save(attempt);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public QuizResultResponse result(Long userId, Long attemptId) {
        AssessmentAttempt attempt = ownedAttempt(userId, attemptId);
        if (!STATUS_SUBMITTED.equals(attempt.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Quiz has not been submitted yet.");
        }
        AssessmentQuizAttempt quizAttempt = attemptDetailRepository.findWithQuizByAttemptId(attemptId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Quiz attempt not found."));
        List<AssessmentAnswer> answers = answerRepository.findWithQuestionByAttemptId(attemptId);
        int correctCount = (int) answers.stream()
                .filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect()))
                .count();
        BigDecimal scorePercent = attempt.getScorePercent() != null ? attempt.getScorePercent() : BigDecimal.ZERO;
        return new QuizResultResponse(
                attemptId,
                quizAttempt.getQuiz().getId(),
                quizAttempt.getQuiz().getTitle(),
                scorePercent,
                correctCount,
                answers.size(),
                answers.stream().map(this::toDetail).toList());
    }

    /** The supported questions of a quiz; a quiz with none cannot be opened. */
    private List<AssessmentQuizQuestion> supportedQuestionsOf(AssessmentQuiz quiz) {
        List<AssessmentQuizQuestion> supported = supportedQuestions(questionRepository.findWithQuestionByQuizId(quiz.getId()));
        if (supported.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Quiz has no supported questions.");
        }
        return supported;
    }

    /** Resumes the user's open attempt on the quiz, or creates one, and returns its questions. */
    private QuizStartResponse open(Long userId, AssessmentQuiz quiz, Long lessonId,
                                   List<AssessmentQuizQuestion> supported) {
        Optional<AssessmentQuizAttempt> resumed = attemptDetailRepository
                .findInProgressByUserIdAndQuizId(userId, quiz.getId());
        Long attemptId;
        List<QuizAnswerFeedbackResponse> answered;
        if (resumed.isPresent() && isTimed(quiz) && secondsLeft(resumed.get().getAttempt()) + DEADLINE_GRACE_SECONDS <= 0) {
            // The clock ran out while the page was closed: grade what was answered, then open a fresh attempt.
            integrity.record(resumed.get().getAttemptId(), "TIME_UP", "expired while away");
            submit(userId, resumed.get().getAttemptId(), new QuizSubmitRequest(List.of()));
            resumed = Optional.empty();
        }
        if (resumed.isPresent()) {
            attemptId = resumed.get().getAttemptId();
            answered = answerRepository.findWithQuestionByAttemptId(attemptId).stream()
                    .map(isTimed(quiz) ? LessonQuizServiceImpl::toHiddenFeedback : this::toFeedback).toList();
            // Reopening a running timed test (reload, new tab, back from elsewhere) counts as leaving it.
            if (isTimed(quiz)) integrity.record(attemptId, "RESUME", null);
        } else {
            AssessmentAttempt attempt = attemptRepository.save(AssessmentAttempt.builder()
                    .user(em.getReference(User.class, userId))
                    .attemptType(ATTEMPT_TYPE_QUIZ)
                    .status(STATUS_IN_PROGRESS)
                    .build());
            attemptDetailRepository.save(AssessmentQuizAttempt.builder()
                    .attempt(attempt)
                    .quiz(quiz)
                    .build());
            attemptId = attempt.getId();
            answered = List.of();
        }
        boolean timed = isTimed(quiz);
        AssessmentAttempt opened = timed ? attemptRepository.findById(attemptId).orElse(null) : null;
        return new QuizStartResponse(
                attemptId,
                quiz.getId(),
                quiz.getTitle(),
                lessonId,
                forAttempt(attemptId, supported).stream().map(this::toQuestion).toList(),
                answered,
                timed ? UNIT_FINAL_TIME_LIMIT_SECONDS : null,
                opened != null ? Math.max(0, secondsLeft(opened)) : null,
                timed ? integrity.leavingCount(attemptId) : null,
                timed ? LEAVE_LIMIT : null);
    }

    @Override
    @Transactional
    public QuizIntegrityResponse recordIntegrity(Long userId, Long attemptId, QuizIntegrityRequest event) {
        String type = event == null || event.type() == null ? "" : event.type().trim().toUpperCase(java.util.Locale.ROOT);
        if (!com.jlas.learning.repository.QuizIntegrityRepository.TYPES.contains(type) || "RESUME".equals(type)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown integrity event.");
        }
        AssessmentAttempt attempt = ownedAttemptForUpdate(userId, attemptId);
        AssessmentQuiz quiz = attemptDetailRepository.findWithQuizByAttemptId(attemptId)
                .map(AssessmentQuizAttempt::getQuiz).orElse(null);
        if (!isTimed(quiz) || !STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            return new QuizIntegrityResponse(0, LEAVE_LIMIT, false);
        }
        // A page that floods events cannot grow the log without bound.
        if (integrity.count(attemptId) < 500) {
            String detail = event.detail() == null ? null : event.detail().strip();
            integrity.record(attemptId, type, detail == null || detail.isEmpty() ? null
                    : detail.substring(0, Math.min(detail.length(), 255)));
        }
        int left = integrity.leavingCount(attemptId);
        return new QuizIntegrityResponse(left, LEAVE_LIMIT, left >= LEAVE_LIMIT);
    }

    /** Only a unit's final test is timed and watched. */
    private static boolean isTimed(AssessmentQuiz quiz) {
        return quiz != null && QUIZ_TYPE_UNIT_FINAL.equals(quiz.getQuizType());
    }

    /** Seconds left on a timed attempt, by the server clock; negative once the deadline has passed. */
    private static long secondsLeft(AssessmentAttempt attempt) {
        if (attempt == null || attempt.getStartedAt() == null) return UNIT_FINAL_TIME_LIMIT_SECONDS;
        long elapsed = java.time.Duration.between(attempt.getStartedAt(), LocalDateTime.now()).getSeconds();
        return UNIT_FINAL_TIME_LIMIT_SECONDS - elapsed;
    }

    /** The questions an attempt shows, in the same order as when it was opened. */
    private List<AssessmentQuizQuestion> questionsOfAttempt(Long attemptId) {
        AssessmentQuizAttempt quizAttempt = attemptDetailRepository.findWithQuizByAttemptId(attemptId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Quiz attempt not found."));
        return forAttempt(attemptId, supportedQuestions(
                questionRepository.findWithQuestionByQuizId(quizAttempt.getQuiz().getId())));
    }

    private AssessmentAttempt ownedAttempt(Long userId, Long attemptId) {
        if (attemptId == null || attemptId <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Attempt not found.");
        }
        return attemptRepository.findOwnedAttempt(attemptId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Attempt not found."));
    }

    /** Like {@link #ownedAttempt}, but takes a pessimistic-write lock so concurrent submits serialize. */
    private AssessmentAttempt ownedAttemptForUpdate(Long userId, Long attemptId) {
        if (attemptId == null || attemptId <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Attempt not found.");
        }
        return attemptRepository.findOwnedAttemptForUpdate(attemptId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Attempt not found."));
    }

    /** The lesson a passed attempt completes: only a lesson's own LESSON quiz completes it. */
    private Long completedLessonOf(AssessmentQuizAttempt quizAttempt) {
        AssessmentQuiz quiz = quizAttempt.getQuiz();
        if (!QUIZ_TYPE_LESSON.equals(quiz.getQuizType()) || quiz.getLesson() == null) {
            return null;
        }
        return quiz.getLesson().getId();
    }

    /** Keeps only question types the lesson quiz can render and grade. */
    private List<AssessmentQuizQuestion> supportedQuestions(List<AssessmentQuizQuestion> links) {
        return links.stream()
                .filter(link -> link.getQuestion() != null
                        && (MULTIPLE_CHOICE.equals(link.getQuestion().getQuestionType())
                            || FILL_BLANK.equals(link.getQuestion().getQuestionType())))
                .toList();
    }

    /**
     * The questions of one attempt. A quiz with a large question bank (the Onyomi practice quizzes hold
     * about a hundred) shows {@link #QUESTIONS_PER_ATTEMPT} of them, drawn in turn from each conversion rule
     * so every rule of the lesson is practised. The draw is seeded by the attempt id, so resuming and
     * submitting an attempt see the same questions while a new attempt gets a different set.
     */
    private List<AssessmentQuizQuestion> forAttempt(Long attemptId, List<AssessmentQuizQuestion> supported) {
        if (supported.size() <= QUESTIONS_PER_ATTEMPT) {
            return supported;
        }
        Random random = new Random(attemptId);
        Map<Long, List<AssessmentQuizQuestion>> byRule = new LinkedHashMap<>();
        for (AssessmentQuizQuestion link : supported) {
            Long ruleId = link.getQuestion().getConversionRule() == null
                    ? 0L : link.getQuestion().getConversionRule().getId();
            byRule.computeIfAbsent(ruleId, key -> new ArrayList<>()).add(link);
        }
        List<List<AssessmentQuizQuestion>> groups = new ArrayList<>(byRule.values());
        groups.forEach(group -> Collections.shuffle(group, random));
        List<AssessmentQuizQuestion> picked = new ArrayList<>(QUESTIONS_PER_ATTEMPT);
        for (int round = 0; picked.size() < QUESTIONS_PER_ATTEMPT; round++) {
            boolean added = false;
            for (List<AssessmentQuizQuestion> group : groups) {
                if (round < group.size() && picked.size() < QUESTIONS_PER_ATTEMPT) {
                    picked.add(group.get(round));
                    added = true;
                }
            }
            if (!added) {
                break;
            }
        }
        Collections.shuffle(picked, random);
        return picked;
    }

    /** 1-based index of the correct option within the compacted options list, or 0 when none exists. */
    private int correctOptionIndex(AssessmentQuestion question) {
        Integer correct = question.getCorrectOption();
        if (correct == null || correct < 1 || correct > 4 || isBlank(rawOption(question, correct))) {
            return 0;
        }
        int index = 1;
        for (int position = 1; position < correct; position++) {
            if (!isBlank(rawOption(question, position))) {
                index++;
            }
        }
        return index;
    }

    private String rawOption(AssessmentQuestion question, int position) {
        return switch (position) {
            case 1 -> question.getOption1();
            case 2 -> question.getOption2();
            case 3 -> question.getOption3();
            case 4 -> question.getOption4();
            default -> null;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<Long, QuizAnswerSubmission> indexSubmissions(QuizSubmitRequest request) {
        Map<Long, QuizAnswerSubmission> map = new LinkedHashMap<>();
        if (request == null || request.answers() == null) {
            return map;
        }
        for (QuizAnswerSubmission answer : request.answers()) {
            if (answer == null || answer.questionId() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each answer must reference a question.");
            }
            map.put(answer.questionId(), answer);
        }
        return map;
    }

    /** A question of a timed test left unanswered when it was submitted: recorded as wrong. */
    private static AssessmentAnswer blankAnswer(AssessmentAttempt attempt, AssessmentQuestion question) {
        return AssessmentAnswer.builder()
                .attempt(attempt)
                .question(question)
                .isCorrect(false)
                .answeredAt(LocalDateTime.now())
                .build();
    }

    private AssessmentAnswer gradedAnswer(AssessmentAttempt attempt, AssessmentQuestion question,
                                          QuizAnswerSubmission submission) {
        boolean correct = grade(question, submission);
        return AssessmentAnswer.builder()
                .attempt(attempt)
                .question(question)
                .selectedOption(submission.selectedOption())
                .answerText(submission.answerText() == null ? null : submission.answerText().trim())
                .isCorrect(correct)
                .answeredAt(LocalDateTime.now())
                .build();
    }

    private boolean grade(AssessmentQuestion question, QuizAnswerSubmission submission) {
        if (submission == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Missing answer for question " + question.getId() + ".");
        }
        if (MULTIPLE_CHOICE.equals(question.getQuestionType())) {
            if (submission.selectedOption() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Missing choice for question " + question.getId() + ".");
            }
            return submission.selectedOption().equals(correctOptionIndex(question));
        }
        if (FILL_BLANK.equals(question.getQuestionType())) {
            if (submission.answerText() == null || submission.answerText().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Missing text for question " + question.getId() + ".");
            }
            // kana answer keys also accept hiragana and romaji (コウ = こう = kou)
            return KanaAnswerMatcher.matches(question.getAnswerText(), submission.answerText());
        }
        throw new ApiException(ErrorCode.BAD_REQUEST,
                "Unsupported question type " + question.getQuestionType() + ".");
    }

    private QuizQuestionResponse toQuestion(AssessmentQuizQuestion link) {
        AssessmentQuestion question = link.getQuestion();
        return new QuizQuestionResponse(
                question.getId(),
                question.getQuestionType(),
                question.getQuestionText(),
                question.getPassageHtml(),
                question.getImageUrl(),
                optionsOf(question),
                focusOf(question));
    }

    /** The {@code focus} of the question's context: the kanji or word shown large, never the answer. */
    private String focusOf(AssessmentQuestion question) {
        String context = question.getContextJson();
        if (context == null || context.isBlank()) {
            return null;
        }
        try {
            JsonNode focus = objectMapper.readTree(context).get("focus");
            return focus != null && focus.isTextual() && !focus.asText().isBlank() ? focus.asText() : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** The learner's own choice only: nothing about whether it is right, for a timed test in progress. */
    private static QuizAnswerFeedbackResponse toHiddenFeedback(AssessmentAnswer answer) {
        return new QuizAnswerFeedbackResponse(answer.getQuestion().getId(), null, answer.getSelectedOption(),
                answer.getAnswerText(), null, null, null);
    }

    private QuizAnswerFeedbackResponse toFeedback(AssessmentAnswer answer) {
        AssessmentQuestion question = answer.getQuestion();
        int correctIndex = correctOptionIndex(question);
        return new QuizAnswerFeedbackResponse(
                question.getId(),
                Boolean.TRUE.equals(answer.getIsCorrect()),
                answer.getSelectedOption(),
                answer.getAnswerText(),
                MULTIPLE_CHOICE.equals(question.getQuestionType()) && correctIndex > 0 ? correctIndex : null,
                correctAnswerText(question, optionsOf(question)),
                question.getExplanation());
    }

    private QuizAnswerDetailResponse toDetail(AssessmentAnswer answer) {
        AssessmentQuestion question = answer.getQuestion();
        List<String> options = optionsOf(question);
        return new QuizAnswerDetailResponse(
                question.getId(),
                question.getQuestionType(),
                question.getQuestionText(),
                options,
                answer.getSelectedOption(),
                answer.getAnswerText(),
                question.getCorrectOption(),
                correctAnswerText(question, options),
                answer.getIsCorrect(),
                question.getExplanation());
    }

    private List<String> optionsOf(AssessmentQuestion question) {
        List<String> options = new ArrayList<>(4);
        addOption(options, question.getOption1());
        addOption(options, question.getOption2());
        addOption(options, question.getOption3());
        addOption(options, question.getOption4());
        return options;
    }

    private void addOption(List<String> options, String value) {
        if (value != null && !value.isBlank()) {
            options.add(value);
        }
    }

    private String correctAnswerText(AssessmentQuestion question, List<String> options) {
        if (FILL_BLANK.equals(question.getQuestionType())) {
            return question.getAnswerText();
        }
        int index = correctOptionIndex(question);
        return index >= 1 && index <= options.size() ? options.get(index - 1) : null;
    }
}
