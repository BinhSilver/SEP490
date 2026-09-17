package com.jlas.learning.service.impl;

import com.jlas.assessment.entity.AssessmentAnswer;
import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.entity.AssessmentQuiz;
import com.jlas.assessment.entity.AssessmentQuizAttempt;
import com.jlas.assessment.entity.AssessmentQuizQuestion;
import com.jlas.auth.entity.User;
import com.jlas.common.event.LearningActivityCompleted;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.QuizAnswerDetailResponse;
import com.jlas.learning.dto.QuizAnswerSubmission;
import com.jlas.learning.dto.QuizQuestionResponse;
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
import com.jlas.learning.service.LessonQuizService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link LessonQuizService} on the shared assessment entities. */
@Service
@RequiredArgsConstructor
public class LessonQuizServiceImpl implements LessonQuizService {

    private static final String ATTEMPT_TYPE_QUIZ = "QUIZ";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String MULTIPLE_CHOICE = "MULTIPLE_CHOICE";
    private static final String FILL_BLANK = "FILL_BLANK";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal PASS_THRESHOLD = BigDecimal.valueOf(60);

    private final LessonQuizRepository lessonQuizRepository;
    private final LessonQuizQuestionRepository questionRepository;
    private final LessonQuizAttemptRepository attemptRepository;
    private final LessonQuizAttemptDetailRepository attemptDetailRepository;
    private final LessonQuizAnswerRepository answerRepository;
    private final LearningProgressService learningProgress;
    private final EntityManager em;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public QuizStartResponse start(Long userId, Long lessonId) {
        AssessmentQuiz quiz = lessonQuizRepository.findPublishedLessonQuiz(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Quiz not found for this lesson."));
        List<AssessmentQuizQuestion> links = questionRepository.findWithQuestionByQuizId(quiz.getId());
        List<AssessmentQuizQuestion> supported = supportedQuestions(links);
        if (supported.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Quiz has no supported questions.");
        }
        learningProgress.start(userId, lessonId);
        Optional<AssessmentQuizAttempt> resumed = attemptDetailRepository
                .findInProgressByUserIdAndQuizId(userId, quiz.getId());
        if (resumed.isPresent()) {
            return new QuizStartResponse(
                    resumed.get().getAttemptId(),
                    quiz.getId(),
                    quiz.getTitle(),
                    lessonId,
                    supported.stream().map(this::toQuestion).toList());
        }
        AssessmentAttempt attempt = attemptRepository.save(AssessmentAttempt.builder()
                .user(em.getReference(User.class, userId))
                .attemptType(ATTEMPT_TYPE_QUIZ)
                .status(STATUS_IN_PROGRESS)
                .build());
        attemptDetailRepository.save(AssessmentQuizAttempt.builder()
                .attempt(attempt)
                .quiz(quiz)
                .build());
        return new QuizStartResponse(
                attempt.getId(),
                quiz.getId(),
                quiz.getTitle(),
                lessonId,
                supported.stream().map(this::toQuestion).toList());
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
        List<AssessmentQuizQuestion> supported = supportedQuestions(links);
        if (supported.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Quiz has no questions to submit.");
        }

        Map<Long, QuizAnswerSubmission> submissions = indexSubmissions(request);
        List<AssessmentAnswer> answers = new ArrayList<>(supported.size());
        BigDecimal totalScore = BigDecimal.ZERO;
        BigDecimal earnedScore = BigDecimal.ZERO;
        int correctCount = 0;
        for (AssessmentQuizQuestion link : supported) {
            AssessmentQuestion question = link.getQuestion();
            QuizAnswerSubmission submission = submissions.remove(question.getId());
            boolean correct = grade(question, submission);
            BigDecimal rawScore = link.getRawScore() != null ? link.getRawScore() : BigDecimal.ONE;
            totalScore = totalScore.add(rawScore);
            if (correct) {
                earnedScore = earnedScore.add(rawScore);
                correctCount++;
            }
            answers.add(AssessmentAnswer.builder()
                    .attempt(attempt)
                    .question(question)
                    .selectedOption(submission.selectedOption())
                    .answerText(submission.answerText() == null ? null : submission.answerText().trim())
                    .isCorrect(correct)
                    .answeredAt(LocalDateTime.now())
                    .build());
        }
        if (!submissions.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Answer references a question outside this quiz.");
        }

        BigDecimal scorePercent = totalScore.signum() > 0
                ? earnedScore.multiply(ONE_HUNDRED).divide(totalScore, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        answerRepository.saveAll(answers);
        attempt.setStatus(STATUS_SUBMITTED);
        attempt.setSubmittedAt(LocalDateTime.now());
        attempt.setScorePercent(scorePercent);
        attemptRepository.save(attempt);
        quizAttempt.setScorePercent(scorePercent);
        attemptDetailRepository.save(quizAttempt);
        events.publishEvent(LearningActivityCompleted.quizCompleted(userId, quizAttempt.getQuiz().getId()));
        if (scorePercent.compareTo(PASS_THRESHOLD) >= 0) {
            Long lessonId = lessonIdOf(quizAttempt);
            if (lessonId != null) {
                learningProgress.complete(userId, lessonId);
            }
        }
        return new QuizSubmitResponse(attemptId, scorePercent, correctCount, supported.size());
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

    /** Resolves the owning lesson of a LESSON quiz attempt, when one exists. */
    private Long lessonIdOf(AssessmentQuizAttempt quizAttempt) {
        AssessmentQuiz quiz = quizAttempt.getQuiz();
        return quiz.getLesson() == null ? null : quiz.getLesson().getId();
    }

    /** Keeps only question types the lesson quiz can render and grade. */
    private List<AssessmentQuizQuestion> supportedQuestions(List<AssessmentQuizQuestion> links) {
        return links.stream()
                .filter(link -> link.getQuestion() != null
                        && (MULTIPLE_CHOICE.equals(link.getQuestion().getQuestionType())
                            || FILL_BLANK.equals(link.getQuestion().getQuestionType())))
                .toList();
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
            return question.getAnswerText() != null
                    && submission.answerText().trim().equalsIgnoreCase(question.getAnswerText().trim());
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
                optionsOf(question));
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
