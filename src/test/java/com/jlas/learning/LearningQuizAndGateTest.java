package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.LessonQuizSubmitRequest;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.LearningService;
import com.jlas.learning.service.impl.JpaLearningCatalogSource;
import com.jlas.learning.service.impl.LearningResourceSelectionServiceImpl;
import com.jlas.learning.service.impl.LearningScheduleServiceImpl;
import com.jlas.learning.service.impl.LearningServiceImpl;
import com.jlas.learning.service.impl.WeakTagPolicy;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * [JLAS-W0113 / W0116] QA cho lesson quiz và warm-up gate: quiz lấy đúng câu theo knowledge tag,
 * chấm theo answer key, ngưỡng 60%, đạt thì complete lesson / mở khóa GOAL_LEARNING.
 *
 * [JLAS-W0108] Hợp đồng đề thi bất biến (P1): mỗi lần phát đề là một {@code attemptId}; nộp theo snapshot
 * đã phát (mẫu số = toàn bộ câu đã phát, từ chối câu ngoài đề / trùng id, nộp lại chỉ trả kết quả đã lưu).
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:learning-quiz-gate-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "jlas.learning.selection.minimum-assessed-questions=3",
        "jlas.learning.selection.wrong-ratio-threshold=0.5"
})
@ContextConfiguration(classes = LearningQuizAndGateTest.QuizGateConfiguration.class)
class LearningQuizAndGateTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.dictionary.repository", "com.jlas.assessment.repository"})
    @Import({JpaLearningCatalogSource.class, WeakTagPolicy.class, LearningResourceSelectionServiceImpl.class,
            LearningScheduleServiceImpl.class, LearningMapper.class, LearningServiceImpl.class})
    static class QuizGateConfiguration {
        @Bean FakeProfileSource profileSource() {
            return new FakeProfileSource();
        }

        @Bean FakeScopeSource scopeSource() {
            return new FakeScopeSource();
        }
    }

    static class FakeProfileSource implements LearningProfileSource {
        LearningProfileSnapshot snapshot;

        @Override
        public LearningProfileSnapshot loadForUser(Long userId, Long assessmentAttemptId) {
            return snapshot;
        }
    }

    static class FakeScopeSource implements LearningScopeSource {
        LearningCatalogScope scope;

        @Override
        public LearningCatalogScope resolve(Long userId, LearningProfileSnapshot profile) {
            return scope;
        }
    }

    @Autowired EntityManager em;
    @Autowired LearningService service;
    @Autowired FakeProfileSource profileSource;
    @Autowired FakeScopeSource scopeSource;

    @Test
    void lessonQuizReturnsQuestionsWithoutAnswerLeak() {
        var fixture = seed();

        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId());

        assertNotNull(quiz.attemptId());
        assertEquals(fixture.warmLesson.getId(), quiz.lessonId());
        assertEquals(3, quiz.questions().size());
        for (var question : quiz.questions()) {
            assertEquals(4, question.options().size());
            assertFalse(question.options().isEmpty());
        }
    }

    @Test
    void submitLessonQuizAllCorrectPassesAndCompletesLesson() {
        var fixture = seed();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId());

        var result = service.submitLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), correctAnswers(fixture)));

        assertTrue(result.passed());
        assertEquals(3, result.correctAnswers());
        assertEquals(100, result.scorePercent());
        assertTrue(result.lessonCompleted());
        assertEquals(1L, em.createQuery(
                        "select count(p) from LessonProgress p where p.user.id = :uid and p.lesson.id = :lid and p.status = 'COMPLETED'",
                        Long.class)
                .setParameter("uid", fixture.user.getId())
                .setParameter("lid", fixture.warmLesson.getId())
                .getSingleResult());
    }

    @Test
    void submitLessonQuizWrongAnswersFail() {
        var fixture = seed();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId());

        var result = service.submitLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), wrongAnswers(fixture)));

        assertFalse(result.passed());
        assertEquals(0, result.correctAnswers());
        assertFalse(result.lessonCompleted());
    }

    @Test
    void submitLessonQuizSkippedCountsAsWrong() {
        var fixture = seed();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId());

        var answers = List.of(
                new LessonQuizSubmitRequest.LessonQuizAnswerRequest(fixture.questions.get(0).getId(),
                        fixture.questions.get(0).getCorrectOption()),
                new LessonQuizSubmitRequest.LessonQuizAnswerRequest(fixture.questions.get(1).getId(), null),
                new LessonQuizSubmitRequest.LessonQuizAnswerRequest(fixture.questions.get(2).getId(), null));

        var result = service.submitLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers));

        assertEquals(1, result.correctAnswers());
        assertEquals(33, result.scorePercent());
        assertFalse(result.passed());
        assertFalse(result.lessonCompleted());
    }

    @Test
    void gateQuizAggregatesWarmUpTagQuestions() {
        var fixture = seed();

        var quiz = service.getGateQuiz(fixture.user.getId());

        assertNotNull(quiz.attemptId());
        assertEquals(3, quiz.questions().size());
        assertEquals("Kiểm tra tổng kết khởi động", quiz.lessonTitle());
    }

    @Test
    void submitGateQuizPassUnlocksGoalPhase() {
        var fixture = seed();
        assertFalse(service.current(fixture.user.getId()).gatePassed());
        var quiz = service.getGateQuiz(fixture.user.getId());

        var result = service.submitGateQuiz(fixture.user.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), correctAnswers(fixture)));

        assertTrue(result.passed());
        assertEquals(3, result.correctAnswers());
        assertTrue(service.current(fixture.user.getId()).gatePassed());
    }

    @Test
    void submitGateQuizFailKeepsGoalLocked() {
        var fixture = seed();
        var quiz = service.getGateQuiz(fixture.user.getId());

        var result = service.submitGateQuiz(fixture.user.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), wrongAnswers(fixture)));

        assertFalse(result.passed());
        assertFalse(service.current(fixture.user.getId()).gatePassed());
    }

    @Test
    void submitGateQuizFailTwiceRequiresRelearn() {
        var fixture = seed();

        var firstQuiz = service.getGateQuiz(fixture.user.getId());
        var first = service.submitGateQuiz(fixture.user.getId(),
                new LessonQuizSubmitRequest(firstQuiz.attemptId(), wrongAnswers(fixture)));
        assertFalse(first.passed());
        assertFalse(first.relearnRequired());

        var secondQuiz = service.getGateQuiz(fixture.user.getId());
        var second = service.submitGateQuiz(fixture.user.getId(),
                new LessonQuizSubmitRequest(secondQuiz.attemptId(), wrongAnswers(fixture)));
        assertFalse(second.passed());
        assertTrue(second.relearnRequired());
    }

    @Test
    void gateQuizAnchorsGrammarSpecificQuestion() {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var user = User.builder().role(role).email("grammar@test.local").loginType("LOCAL").build();
        em.persist(user);

        var tag = Tag.builder().code("PARTICLE").name("Particle").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var warmBook = Textbook.builder().title("Warm-up").status("PUBLISHED").jlptLevel("N5").build();
        em.persist(warmBook);
        var warmUnit = Unit.builder().textbook(warmBook).title("Unit").orderIndex(1).build();
        em.persist(warmUnit);
        var warmLesson = Lesson.builder().unit(warmUnit).knowledgeTag(tag).title("Particle lesson")
                .status("PUBLISHED").estimatedMinutes(15).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(warmLesson);
        var point = GrammarPoint.builder().lesson(warmLesson).pattern("～てください").meaning("vui lòng hãy làm")
                .orderIndex(1).build();
        em.persist(point);

        var goalBook = Textbook.builder().title("Goal").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(goalBook);
        var goalUnit = Unit.builder().textbook(goalBook).title("Unit").orderIndex(1).build();
        em.persist(goalUnit);
        var goalTag = Tag.builder().code("TARGET").name("Target").tagType("KNOWLEDGE").build();
        em.persist(goalTag);
        var goalLesson = Lesson.builder().unit(goalUnit).knowledgeTag(goalTag).title("Goal lesson")
                .status("PUBLISHED").estimatedMinutes(20).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(goalLesson);

        var grammarQuestion = AssessmentQuestion.builder()
                .knowledgeTag(tag)
                .grammarPoint(point)
                .skill("GRAMMAR")
                .jlptLevel("N5")
                .questionText("Grammar question for the wrong pattern")
                .option1("A")
                .option2("B")
                .option3("C")
                .option4("D")
                .correctOption(1)
                .explanation("grammar")
                .build();
        em.persist(grammarQuestion);

        em.flush();
        em.clear();

        profileSource.snapshot = new LearningProfileSnapshot(user.getId(), 91L, 10L, "rev-1", true, true,
                "N4", "N4", "JLPT", 30,
                List.of(WeaknessEvidence.grammar(point.getId(), "PARTICLE", tag.getId(), 5, 4)));
        scopeSource.scope = new LearningCatalogScope(List.of(warmBook.getId()), List.of(goalBook.getId()));
        service.create(user.getId(), 91L);

        var quiz = service.getGateQuiz(user.getId());

        assertTrue(quiz.questions().stream()
                .anyMatch(question -> question.questionId().equals(grammarQuestion.getId())));
    }

    // === [JLAS-W0108] P1: hợp đồng đề thi bất biến ===

    @Test
    void submitLessonQuizOmittingQuestionsUsesFullIssuedDenominator() {
        var fixture = seed();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId());

        // Chỉ trả lời đúng 1 câu, bỏ hẳn 2 câu còn lại: mẫu số phải là 3 (toàn bộ đề đã phát), không phải 1.
        var answers = List.of(new LessonQuizSubmitRequest.LessonQuizAnswerRequest(
                fixture.questions.get(0).getId(), fixture.questions.get(0).getCorrectOption()));

        var result = service.submitLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers));

        assertEquals(3, result.totalQuestions());
        assertEquals(1, result.correctAnswers());
        assertEquals(33, result.scorePercent());
        assertFalse(result.passed());
    }

    @Test
    void submitLessonQuizDuplicateQuestionIdRejected() {
        var fixture = seed();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId());

        var questionId = fixture.questions.get(0).getId();
        var answers = List.of(
                new LessonQuizSubmitRequest.LessonQuizAnswerRequest(questionId, 1),
                new LessonQuizSubmitRequest.LessonQuizAnswerRequest(questionId, 2));

        var exception = assertThrows(ApiException.class, () -> service.submitLessonQuiz(
                fixture.user.getId(), fixture.warmLesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers)));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void submitLessonQuizOutOfExamQuestionRejected() {
        var fixture = seed();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId());

        var answers = List.of(new LessonQuizSubmitRequest.LessonQuizAnswerRequest(999_999L, 1));

        var exception = assertThrows(ApiException.class, () -> service.submitLessonQuiz(
                fixture.user.getId(), fixture.warmLesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers)));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void submitLessonQuizDoubleSubmitReturnsSameResultAndConsumesSingleQuota() {
        var fixture = seed();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId());

        var first = service.submitLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), correctAnswers(fixture)));
        assertTrue(first.passed());
        assertEquals(100, first.scorePercent());

        // Nộp lại cùng attemptId nhưng với đáp án sai: phải trả kết quả đã lưu, không chấm lại.
        var second = service.submitLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), wrongAnswers(fixture)));
        assertTrue(second.passed());
        assertEquals(100, second.scorePercent());

        // Chỉ tốn đúng 1 lượt quiz.
        assertEquals(1L, em.createQuery(
                        "select count(a) from QuizAttempt a where a.user.id = :uid", Long.class)
                .setParameter("uid", fixture.user.getId())
                .getSingleResult());
    }

    @Test
    void submitLessonQuizGradesAgainstIssueTimeSnapshot() {
        var fixture = seed();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId());

        // Chụp đáp án gốc trước khi đổi ngân hàng.
        var original = correctAnswers(fixture);

        // Đổi đáp án chuẩn trong ngân hàng SAU khi đã phát đề.
        for (var question : fixture.questions) {
            var managed = em.find(AssessmentQuestion.class, question.getId());
            managed.setCorrectOption(managed.getCorrectOption() == 1 ? 2 : 1);
        }
        em.flush();

        // Nộp theo đáp án gốc: vẫn phải đạt 100% vì chấm theo snapshot lúc phát, không theo ngân hàng hiện tại.
        var result = service.submitLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), original));

        assertTrue(result.passed());
        assertEquals(100, result.scorePercent());
    }

    private List<LessonQuizSubmitRequest.LessonQuizAnswerRequest> correctAnswers(Fixture fixture) {
        return fixture.questions.stream()
                .map(question -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(question.getId(),
                        question.getCorrectOption()))
                .toList();
    }

    private List<LessonQuizSubmitRequest.LessonQuizAnswerRequest> wrongAnswers(Fixture fixture) {
        return fixture.questions.stream()
                .map(question -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(question.getId(),
                        question.getCorrectOption() == 1 ? 2 : 1))
                .toList();
    }

    private Fixture seed() {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var user = User.builder().role(role).email("quiz@test.local").loginType("LOCAL").build();
        em.persist(user);

        var tag = Tag.builder().code("PARTICLE").name("Particle").tagType("KNOWLEDGE").build();
        em.persist(tag);

        var warmBook = Textbook.builder().title("Warm-up").status("PUBLISHED").jlptLevel("N5").build();
        em.persist(warmBook);
        var warmUnit = Unit.builder().textbook(warmBook).title("Unit").orderIndex(1).build();
        em.persist(warmUnit);
        var warmLesson = Lesson.builder().unit(warmUnit).knowledgeTag(tag).title("Particle lesson")
                .status("PUBLISHED").estimatedMinutes(15).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(warmLesson);

        var goalBook = Textbook.builder().title("Goal").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(goalBook);
        var goalUnit = Unit.builder().textbook(goalBook).title("Unit").orderIndex(1).build();
        em.persist(goalUnit);
        var goalTag = Tag.builder().code("TARGET").name("Target").tagType("KNOWLEDGE").build();
        em.persist(goalTag);
        var goalLesson = Lesson.builder().unit(goalUnit).knowledgeTag(goalTag).title("Goal lesson")
                .status("PUBLISHED").estimatedMinutes(20).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(goalLesson);

        var questions = List.of(
                seedQuestion(tag, "Q1", 1),
                seedQuestion(tag, "Q2", 2),
                seedQuestion(tag, "Q3", 3));
        em.flush();
        em.clear();

        profileSource.snapshot = profile(user.getId(), 91L, "rev-1");
        scopeSource.scope = new LearningCatalogScope(List.of(warmBook.getId()), List.of(goalBook.getId()));
        service.create(user.getId(), 91L);

        return new Fixture(user, warmLesson, questions);
    }

    private AssessmentQuestion seedQuestion(Tag tag, String text, int correctOption) {
        var question = AssessmentQuestion.builder()
                .knowledgeTag(tag)
                .skill("GRAMMAR")
                .jlptLevel("N5")
                .questionText(text)
                .option1("A")
                .option2("B")
                .option3("C")
                .option4("D")
                .correctOption(correctOption)
                .explanation("Vì đáp án " + correctOption)
                .build();
        em.persist(question);
        return question;
    }

    private LearningProfileSnapshot profile(Long userId, Long attemptId, String revision) {
        return new LearningProfileSnapshot(userId, attemptId, 10L, revision, true, true,
                "N4", "N4", "JLPT", 30, List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 5, 4)));
    }

    private record Fixture(User user, Lesson warmLesson, List<AssessmentQuestion> questions) { }
}
