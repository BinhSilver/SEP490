package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.jlas.learning.dto.QuizAnswerDetailResponse;
import com.jlas.learning.dto.QuizAnswerSubmission;
import com.jlas.learning.dto.QuizSubmitRequest;
import com.jlas.learning.service.LessonQuizService;
import com.jlas.learning.service.impl.LessonQuizServiceImpl;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:quiz-result-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LessonQuizResultTest.ResultTestConfiguration.class)
class LessonQuizResultTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.learning.repository")
    @Import(LessonQuizServiceImpl.class)
    static class ResultTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LessonQuizService service;

    private int userSeq = 0;
    private int tagSeq = 0;

    @Test
    void resultExposesCorrectAnswerAndExplanationForMultipleChoice() {
        var user = seedUser();
        var lesson = seedLesson();
        var quiz = seedQuiz(lesson);
        var question = seedMultipleChoice("Chọn trợ từ", 3);
        link(quiz, question, 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());
        service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), 3, null))));

        var result = service.result(user.getId(), started.attemptId());
        var detail = answerFor(result.answers(), question.getId());

        assertEquals(quiz.getId(), result.quizId());
        assertEquals(quiz.getTitle(), result.quizTitle());
        assertEquals(1, result.correctCount());
        assertEquals(1, result.totalCount());
        assertEquals("MULTIPLE_CHOICE", detail.questionType());
        assertEquals(Integer.valueOf(3), detail.correctOption());
        assertEquals("Đáp án 3", detail.correctAnswerText());
        assertEquals(Integer.valueOf(3), detail.selectedOption());
        assertTrue(detail.isCorrect());
        assertEquals("Giải thích.", detail.explanation());
        assertEquals(4, detail.options().size());
    }

    @Test
    void fillBlankGradingIsCaseInsensitiveAndTrims() {
        var user = seedUser();
        var lesson = seedLesson();
        var quiz = seedQuiz(lesson);
        var question = seedFillBlank("Điền từ", "こんにちは");
        link(quiz, question, 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());
        service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), null, "  こんにちは "))));

        var result = service.result(user.getId(), started.attemptId());
        var detail = answerFor(result.answers(), question.getId());

        assertTrue(detail.isCorrect());
        assertEquals("こんにちは", detail.correctAnswerText());
        assertEquals("こんにちは", detail.answerText());
    }

    @Test
    void wrongAnswerIsMarkedIncorrectWithExplanation() {
        var user = seedUser();
        var lesson = seedLesson();
        var quiz = seedQuiz(lesson);
        var question = seedMultipleChoice("Chọn trợ từ", 2);
        link(quiz, question, 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());
        service.submit(user.getId(), started.attemptId(),
                new QuizSubmitRequest(List.of(new QuizAnswerSubmission(question.getId(), 4, null))));

        var result = service.result(user.getId(), started.attemptId());
        var detail = answerFor(result.answers(), question.getId());

        assertEquals(Boolean.FALSE, detail.isCorrect());
        assertEquals(0, result.correctCount());
        assertEquals("Đáp án 2", detail.correctAnswerText());
        assertEquals("Giải thích.", detail.explanation());
    }

    @Test
    void scorePercentIsRoundedToTwoDecimals() {
        var user = seedUser();
        var lesson = seedLesson();
        var quiz = seedQuiz(lesson);
        var q1 = seedMultipleChoice("Câu 1", 1);
        var q2 = seedMultipleChoice("Câu 2", 2);
        var q3 = seedMultipleChoice("Câu 3", 3);
        link(quiz, q1, 1);
        link(quiz, q2, 2);
        link(quiz, q3, 3);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());
        service.submit(user.getId(), started.attemptId(), new QuizSubmitRequest(List.of(
                new QuizAnswerSubmission(q1.getId(), 1, null),
                new QuizAnswerSubmission(q2.getId(), 1, null),
                new QuizAnswerSubmission(q3.getId(), 1, null))));

        var result = service.result(user.getId(), started.attemptId());

        assertEquals(1, result.correctCount());
        assertEquals(3, result.totalCount());
        assertEquals(0, result.scorePercent().compareTo(new BigDecimal("33.33")));
    }

    @Test
    void resultBeforeSubmitThrowsConflict() {
        var user = seedUser();
        var lesson = seedLesson();
        var quiz = seedQuiz(lesson);
        link(quiz, seedMultipleChoice("Câu 1", 1), 1);
        em.flush();
        var started = service.start(user.getId(), lesson.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.result(user.getId(), started.attemptId()));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    private QuizAnswerDetailResponse answerFor(List<QuizAnswerDetailResponse> answers, Long questionId) {
        return answers.stream()
                .filter(answer -> answer.questionId().equals(questionId))
                .findFirst()
                .orElseThrow();
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

    private Lesson seedLesson() {
        Textbook book = Textbook.builder().title("Minna").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(book);
        Unit unit = Unit.builder().textbook(book).title("Bài 1").orderIndex(1).build();
        em.persist(unit);
        Tag tag = Tag.builder().code("N4-T-" + (++tagSeq)).name("Trợ từ").tagType("KNOWLEDGE").build();
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
