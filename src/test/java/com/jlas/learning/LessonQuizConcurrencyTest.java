package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.jlas.learning.dto.QuizAnswerSubmission;
import com.jlas.learning.dto.QuizSubmitRequest;
import com.jlas.learning.dto.QuizSubmitResponse;
import com.jlas.learning.service.LessonQuizService;
import com.jlas.learning.service.impl.LearningProgressServiceImpl;
import com.jlas.learning.service.impl.LessonQuizServiceImpl;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two concurrent submits of the same attempt must serialize on the attempt row lock: exactly one
 * grades and saves answers, the other is rejected with a conflict, and no answers are duplicated.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:quiz-concurrency-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LessonQuizConcurrencyTest.ConcurrencyConfiguration.class)
class LessonQuizConcurrencyTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.learning.repository")
    @Import({LessonQuizServiceImpl.class, LearningProgressServiceImpl.class})
    static class ConcurrencyConfiguration { }

    @Autowired EntityManager em;
    @Autowired LessonQuizService service;
    @Autowired PlatformTransactionManager txManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSubmitGradesAndPersistsAnswersOnce() throws Exception {
        Fixture fixture = seedCommitted();
        QuizSubmitRequest request = new QuizSubmitRequest(List.of(
                new QuizAnswerSubmission(fixture.questionId(), 1, null)));

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<QuizSubmitResponse> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        Runnable task = () -> {
            try {
                ready.countDown();
                go.await();
                results.add(service.submit(fixture.userId(), fixture.attemptId(), request));
            } catch (Throwable throwable) {
                errors.add(throwable);
            }
        };
        Thread first = new Thread(task);
        Thread second = new Thread(task);
        first.start();
        second.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS), "threads should be ready");
        go.countDown();
        first.join(30_000);
        second.join(30_000);

        assertEquals(1, results.size(), "exactly one submit wins");
        assertEquals(1, errors.size(), "exactly one submit is rejected");
        assertTrue(errors.get(0) instanceof ApiException, "rejected submit is a business conflict");
        assertEquals(ErrorCode.CONFLICT, ((ApiException) errors.get(0)).getErrorCode());

        Long answerCount = new TransactionTemplate(txManager).execute(status -> em.createQuery(
                        "select count(ans) from AssessmentAnswer ans where ans.attempt.id = :attemptId", Long.class)
                .setParameter("attemptId", fixture.attemptId())
                .getSingleResult());
        assertEquals(1L, answerCount, "answers are persisted exactly once");
    }

    private Fixture seedCommitted() {
        return new TransactionTemplate(txManager).execute(status -> {
            Role role = Role.builder().name("LEARNER").build();
            em.persist(role);
            User user = User.builder().role(role).email("concurrent-quiz@test.local").loginType("LOCAL").build();
            em.persist(user);

            Textbook book = Textbook.builder().title("Minna").status("PUBLISHED").jlptLevel("N4").build();
            em.persist(book);
            Unit unit = Unit.builder().textbook(book).title("Bài 1").orderIndex(1).build();
            em.persist(unit);
            Tag tag = Tag.builder().code("N4-CQ").name("Trợ từ").tagType("KNOWLEDGE").build();
            em.persist(tag);
            Lesson lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Bài học")
                    .status("PUBLISHED").estimatedMinutes(10).orderIndex(1)
                    .lessonType("CURRICULUM").skill("GRAMMAR").build();
            em.persist(lesson);
            AssessmentQuiz quiz = AssessmentQuiz.builder()
                    .lesson(lesson).title("Quiz bài học").quizType("LESSON").status("PUBLISHED").build();
            em.persist(quiz);
            AssessmentQuestion question = AssessmentQuestion.builder()
                    .knowledgeTag(tag).questionType("MULTIPLE_CHOICE").questionText("Câu 1")
                    .option1("Đáp án 1").option2("Đáp án 2").option3("Đáp án 3").option4("Đáp án 4")
                    .correctOption(1).explanation("Giải thích.").build();
            em.persist(question);
            em.persist(AssessmentQuizQuestion.builder()
                    .quiz(quiz).question(question).orderIndex(1).build());

            var started = service.start(user.getId(), lesson.getId());
            return new Fixture(user.getId(), started.attemptId(), question.getId());
        });
    }

    private record Fixture(Long userId, Long attemptId, Long questionId) { }
}
