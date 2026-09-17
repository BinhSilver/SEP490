package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/**
 * QA acceptance checks for the lesson-quiz flow: repeated submission, cross-user
 * access control, and incomplete answer sets.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:quiz-qa-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LessonQuizQaTest.QaTestConfiguration.class)
class LessonQuizQaTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.learning.repository")
    @Import({LessonQuizServiceImpl.class, LearningProgressServiceImpl.class})
    static class QaTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LessonQuizService service;

    private int userSeq = 0;
    private int tagSeq = 0;

    @Test
    void repeatSubmitIsRejected() {
        var user = seedUser();
        var lesson = seedLesson();
        var quiz = seedQuiz(lesson);
        var question = seedMultipleChoice("Câu 1", 1);
        link(quiz, question, 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());
        var request = new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), 1, null)));

        service.submit(user.getId(), started.attemptId(), request);

        assertApiError(ErrorCode.CONFLICT, () -> service.submit(user.getId(), started.attemptId(), request));
    }

    @Test
    void anotherUserCannotSubmitTheAttempt() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var owner = seedUser(role);
        var other = seedUser(role);
        var lesson = seedLesson();
        var quiz = seedQuiz(lesson);
        var question = seedMultipleChoice("Câu 1", 1);
        link(quiz, question, 1);
        em.flush();
        var started = service.start(owner.getId(), lesson.getId());

        assertApiError(ErrorCode.NOT_FOUND, () -> service.submit(other.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), 1, null)))));
    }

    @Test
    void anotherUserCannotViewTheResult() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var owner = seedUser(role);
        var other = seedUser(role);
        var lesson = seedLesson();
        var quiz = seedQuiz(lesson);
        var question = seedMultipleChoice("Câu 1", 1);
        link(quiz, question, 1);
        em.flush();
        var started = service.start(owner.getId(), lesson.getId());
        service.submit(owner.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), 1, null))));

        assertApiError(ErrorCode.NOT_FOUND, () -> service.result(other.getId(), started.attemptId()));
    }

    @Test
    void missingAnswersAreRejected() {
        var user = seedUser();
        var lesson = seedLesson();
        var quiz = seedQuiz(lesson);
        var first = seedMultipleChoice("Câu 1", 1);
        var second = seedMultipleChoice("Câu 2", 2);
        link(quiz, first, 1);
        link(quiz, second, 2);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());

        assertApiError(ErrorCode.VALIDATION_FAILED, () -> service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of())));
        assertApiError(ErrorCode.VALIDATION_FAILED, () -> service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(first.getId(), 1, null)))));
    }

    private void assertApiError(ErrorCode code, Executable action) {
        ApiException ex = assertThrows(ApiException.class, action);
        assertEquals(code, ex.getErrorCode());
    }

    private User seedUser() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        return seedUser(role);
    }

    private User seedUser(Role role) {
        User user = User.builder().role(role)
                .email("learner" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        return user;
    }

    private Lesson seedLesson() {
        Textbook book = Textbook.builder().title("Minna").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(book);
        Unit unit = Unit.builder().textbook(book).title("Bài 1").orderIndex(1).build();
        em.persist(unit);
        Tag tag = Tag.builder().code("N4-Q-" + (++tagSeq)).name("Trợ từ").tagType("KNOWLEDGE").build();
        em.persist(tag);
        Lesson lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Bài học")
                .status("PUBLISHED").estimatedMinutes(10).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }

    private AssessmentQuiz seedQuiz(Lesson lesson) {
        AssessmentQuiz quiz = AssessmentQuiz.builder()
                .lesson(lesson).title("Quiz bài học").quizType("LESSON").status("PUBLISHED").build();
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
