package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.entity.AssessmentQuiz;
import com.jlas.assessment.entity.AssessmentQuizQuestion;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.QuizAnswerSubmission;
import com.jlas.learning.dto.QuizSubmitRequest;
import com.jlas.learning.repository.LessonQuizAttemptRepository;
import com.jlas.learning.service.LearningProgressService;
import com.jlas.learning.service.LessonQuizService;
import com.jlas.learning.service.impl.LearningProgressServiceImpl;
import com.jlas.learning.service.impl.LessonQuizServiceImpl;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:quiz-service-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LessonQuizServiceTest.QuizTestConfiguration.class)
class LessonQuizServiceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.learning.repository")
    @Import({LessonQuizServiceImpl.class, LearningProgressServiceImpl.class})
    static class QuizTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LessonQuizService service;
    @Autowired LessonQuizAttemptRepository attemptRepository;
    @Autowired LearningProgressService learningProgress;

    private int userSeq = 0;
    private int tagSeq = 0;

    @Test
    void startCreatesAttemptAndReturnsQuestionsInOrder() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        var first = seedMultipleChoice("Câu 1", 2);
        var second = seedFillBlank("Câu 2", "こんにちは");
        link(quiz, first, 1);
        link(quiz, second, 2);
        em.flush();

        var result = service.start(user.getId(), lesson.getId());

        assertNotNull(result.attemptId());
        assertEquals(quiz.getId(), result.quizId());
        assertEquals(lesson.getId(), result.lessonId());
        assertEquals(2, result.questions().size());
        assertEquals(first.getId(), result.questions().get(0).questionId());
        assertEquals("MULTIPLE_CHOICE", result.questions().get(0).questionType());
        assertEquals(4, result.questions().get(0).options().size());
        assertEquals(second.getId(), result.questions().get(1).questionId());
        assertEquals("FILL_BLANK", result.questions().get(1).questionType());
        assertTrue(result.questions().get(1).options().isEmpty());
    }

    @Test
    void startRejectsMissingOrUnpublishedQuiz() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var draftQuizLesson = seedLesson("PUBLISHED");
        seedQuiz(draftQuizLesson, "LESSON", "DRAFT");
        var unitQuizLesson = seedLesson("PUBLISHED");
        seedQuiz(unitQuizLesson, "UNIT_FINAL", "PUBLISHED");
        em.flush();

        assertApiError(ErrorCode.NOT_FOUND, () -> service.start(user.getId(), lesson.getId()));
        assertApiError(ErrorCode.NOT_FOUND, () -> service.start(user.getId(), draftQuizLesson.getId()));
        assertApiError(ErrorCode.NOT_FOUND, () -> service.start(user.getId(), unitQuizLesson.getId()));
    }

    @Test
    void submitGradesAnswersAndMarksSubmitted() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        var multiple = seedMultipleChoice("Chọn trợ từ", 2);
        var blank = seedFillBlank("Điền từ", "こんにちは");
        link(quiz, multiple, 1);
        link(quiz, blank, 2);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());

        var submit = service.submit(user.getId(), started.attemptId(), new QuizSubmitRequest(List.of(
                new QuizAnswerSubmission(multiple.getId(), 2, null),
                new QuizAnswerSubmission(blank.getId(), null, "こんにちは"))));

        assertEquals(started.attemptId(), submit.attemptId());
        assertEquals(2, submit.correctCount());
        assertEquals(2, submit.totalCount());
        assertEquals(0, submit.scorePercent().compareTo(new java.math.BigDecimal("100.00")));
        assertEquals("SUBMITTED", attemptRepository.findById(started.attemptId()).orElseThrow().getStatus());
    }

    @Test
    void submitRejectsMissingAnswer() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        var first = seedMultipleChoice("Câu 1", 1);
        var second = seedMultipleChoice("Câu 2", 2);
        link(quiz, first, 1);
        link(quiz, second, 2);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());

        assertApiError(ErrorCode.VALIDATION_FAILED, () -> service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(first.getId(), 1, null)))));
    }

    @Test
    void submitRejectsUnknownQuestion() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        var question = seedMultipleChoice("Câu 1", 1);
        link(quiz, question, 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());

        assertApiError(ErrorCode.VALIDATION_FAILED, () -> service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(
                        new QuizAnswerSubmission(question.getId(), 1, null),
                        new QuizAnswerSubmission(999999L, 2, null)))));
    }

    @Test
    void submitRejectsMissingChoice() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        var question = seedMultipleChoice("Câu 1", 1);
        link(quiz, question, 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());

        assertApiError(ErrorCode.VALIDATION_FAILED, () -> service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), null, null)))));
    }

    @Test
    void submitRejectsMissingText() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        var question = seedFillBlank("Điền từ", "こんにちは");
        link(quiz, question, 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());

        assertApiError(ErrorCode.VALIDATION_FAILED, () -> service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), null, "   ")))));
    }

    @Test
    void startingQuizMarksLessonInProgress() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        link(quiz, seedMultipleChoice("Câu 1", 1), 1);
        em.flush();

        service.start(user.getId(), lesson.getId());

        assertEquals("IN_PROGRESS", learningProgress.status(user.getId(), lesson.getId()).status());
    }

    @Test
    void passingQuizCompletesLesson() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        var question = seedMultipleChoice("Chọn trợ từ", 1);
        link(quiz, question, 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());

        service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), 1, null))));

        assertEquals("COMPLETED", learningProgress.status(user.getId(), lesson.getId()).status());
    }

    @Test
    void failingQuizDoesNotCompleteLesson() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        var question = seedMultipleChoice("Chọn trợ từ", 1);
        link(quiz, question, 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());

        service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), 4, null))));

        assertEquals("IN_PROGRESS", learningProgress.status(user.getId(), lesson.getId()).status());
    }

    @Test
    void multipleChoiceGradesByCompactedOptionWhenLeadingOptionIsBlank() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        var question = seedMultipleChoiceWithGap("Chọn trợ từ", 3);
        link(quiz, question, 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());

        // "Đáp án 3" becomes the 2nd visible option once the blank option1 is dropped.
        var submit = service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), 2, null))));

        assertEquals(1, submit.correctCount());
        assertEquals(0, submit.scorePercent().compareTo(new java.math.BigDecimal("100.00")));
    }

    @Test
    void startExcludesUnsupportedQuestionTypes() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        var multiple = seedMultipleChoice("Câu 1", 1);
        var matching = seedMatching("Nối cặp");
        link(quiz, multiple, 1);
        link(quiz, matching, 2);
        em.flush();

        var result = service.start(user.getId(), lesson.getId());

        assertEquals(1, result.questions().size());
        assertEquals(multiple.getId(), result.questions().get(0).questionId());
    }

    @Test
    void startRejectsQuizWithNoSupportedQuestions() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        link(quiz, seedMatching("Nối cặp"), 1);
        em.flush();

        assertApiError(ErrorCode.BAD_REQUEST, () -> service.start(user.getId(), lesson.getId()));
    }

    @Test
    void startResumesInProgressAttemptInsteadOfStacking() {
        var user = seedUser();
        var lesson = seedLesson("PUBLISHED");
        var quiz = seedQuiz(lesson, "LESSON", "PUBLISHED");
        link(quiz, seedMultipleChoice("Câu 1", 1), 1);
        em.flush();

        var first = service.start(user.getId(), lesson.getId());
        var second = service.start(user.getId(), lesson.getId());

        assertEquals(first.attemptId(), second.attemptId());
        long attempts = em.createQuery(
                        "select count(a) from AssessmentAttempt a where a.user.id = :userId", Long.class)
                .setParameter("userId", user.getId())
                .getSingleResult();
        assertEquals(1L, attempts);
    }

    private void assertApiError(ErrorCode code, Executable action) {
        ApiException ex = assertThrows(ApiException.class, action);
        assertEquals(code, ex.getErrorCode());
    }

    private User seedUser() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User user = User.builder().role(role)
                .email("learner" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        return user;
    }

    private Lesson seedLesson(String status) {
        Textbook book = Textbook.builder().title("Minna").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(book);
        Unit unit = Unit.builder().textbook(book).title("Bài 1").orderIndex(1).build();
        em.persist(unit);
        Tag tag = Tag.builder().code("N4-TAG-" + (++tagSeq)).name("Trợ từ").tagType("KNOWLEDGE").build();
        em.persist(tag);
        Lesson lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Bài học")
                .status(status).estimatedMinutes(10).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }

    private AssessmentQuiz seedQuiz(Lesson lesson, String quizType, String status) {
        AssessmentQuiz quiz = AssessmentQuiz.builder()
                .lesson(lesson)
                .title("Quiz bài học")
                .quizType(quizType)
                .status(status).build();
        em.persist(quiz);
        return quiz;
    }

    private AssessmentQuestion seedMultipleChoice(String text, int correctOption) {
        AssessmentQuestion question = AssessmentQuestion.builder()
                .knowledgeTag(knowledgeTag())
                .questionType("MULTIPLE_CHOICE")
                .questionText(text)
                .option1("Đáp án 1")
                .option2("Đáp án 2")
                .option3("Đáp án 3")
                .option4("Đáp án 4")
                .correctOption(correctOption)
                .explanation("Giải thích.").build();
        em.persist(question);
        return question;
    }

    private AssessmentQuestion seedFillBlank(String text, String answer) {
        AssessmentQuestion question = AssessmentQuestion.builder()
                .knowledgeTag(knowledgeTag())
                .questionType("FILL_BLANK")
                .questionText(text)
                .answerText(answer)
                .explanation("Giải thích.").build();
        em.persist(question);
        return question;
    }

    private AssessmentQuestion seedMultipleChoiceWithGap(String text, int correctOption) {
        AssessmentQuestion question = AssessmentQuestion.builder()
                .knowledgeTag(knowledgeTag())
                .questionType("MULTIPLE_CHOICE")
                .questionText(text)
                .option1(null)
                .option2("Đáp án 2")
                .option3("Đáp án 3")
                .option4("Đáp án 4")
                .correctOption(correctOption)
                .explanation("Giải thích.").build();
        em.persist(question);
        return question;
    }

    private AssessmentQuestion seedMatching(String text) {
        AssessmentQuestion question = AssessmentQuestion.builder()
                .knowledgeTag(knowledgeTag())
                .questionType("MATCHING")
                .questionText(text)
                .explanation("Giải thích.").build();
        em.persist(question);
        return question;
    }

    private Tag knowledgeTag() {
        Tag tag = Tag.builder().code("N4-K-" + (++tagSeq)).name("Điểm kiến thức").tagType("KNOWLEDGE").build();
        em.persist(tag);
        return tag;
    }

    private void link(AssessmentQuiz quiz, AssessmentQuestion question, int order) {
        AssessmentQuizQuestion link = AssessmentQuizQuestion.builder()
                .quiz(quiz)
                .question(question)
                .orderIndex(order).build();
        em.persist(link);
    }
}
