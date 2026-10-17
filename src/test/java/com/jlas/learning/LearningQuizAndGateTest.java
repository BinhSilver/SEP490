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
import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.ContentVocabulary;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.LessonQuizSubmitRequest;
import com.jlas.learning.dto.QuizAnswerKey;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.LearningService;
import com.jlas.learning.entity.QuizAttempt;
import com.jlas.learning.service.impl.JpaLearningCatalogSource;
import com.jlas.learning.service.impl.LearningClock;
import com.jlas.learning.service.impl.LearningResourceSelectionServiceImpl;
import com.jlas.learning.service.impl.LearningScheduleServiceImpl;
import com.jlas.learning.service.impl.LearningServiceImpl;
import com.jlas.learning.service.impl.WeakTagPolicy;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            LearningScheduleServiceImpl.class, LearningMapper.class, LearningServiceImpl.class, LearningClock.class})
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
    @Autowired LearningMapper mapper;
    @Autowired FakeProfileSource profileSource;
    @Autowired FakeScopeSource scopeSource;
    @Autowired LearningClock clock;

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
    void submitLessonQuizAllCorrectPassesAndMarksAssessmentNotStudy() {
        var fixture = seed();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId());

        var result = service.submitLessonQuiz(fixture.user.getId(), fixture.warmLesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), correctAnswers(fixture)));

        assertTrue(result.passed());
        assertEquals(3, result.correctAnswers());
        assertEquals(100, result.scorePercent());
        // [JLAS-W0108] P4 — đạt quiz ghi assessment PASSED trên item, KHÔNG hoàn thành study
        // (study ≠ PASSED): không tạo LessonProgress COMPLETED, không đổi studyStatus.
        assertEquals(0L, em.createQuery(
                        "select count(p) from LessonProgress p where p.user.id = :uid and p.lesson.id = :lid and p.status = 'COMPLETED'",
                        Long.class)
                .setParameter("uid", fixture.user.getId())
                .setParameter("lid", fixture.warmLesson.getId())
                .getSingleResult());
        assertEquals(1L, em.createQuery(
                        "select count(i) from UserLearningPathItem i where i.lesson.id = :lid and i.learningPath.user.id = :uid and i.assessmentStatus = 'PASSED'",
                        Long.class)
                .setParameter("uid", fixture.user.getId())
                .setParameter("lid", fixture.warmLesson.getId())
                .getSingleResult());
        assertEquals(0L, em.createQuery(
                        "select count(i) from UserLearningPathItem i where i.lesson.id = :lid and i.learningPath.user.id = :uid and i.studyStatus = 'STUDIED'",
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
    }

    @Test
    void gateQuizAggregatesWarmUpTagQuestions() {
        var fixture = seed();
        markWarmUpPassed(fixture.user.getId());

        var quiz = service.getGateQuiz(fixture.user.getId());

        assertNotNull(quiz.attemptId());
        assertEquals(3, quiz.questions().size());
        assertEquals("Kiểm tra tổng kết khởi động", quiz.lessonTitle());
    }

    @Test
    void getGateQuizBlockedWhenWarmUpIncomplete() {
        var fixture = seed(); // chưa đánh dấu warm-up đạt

        var exception = assertThrows(ApiException.class, () ->
                service.getGateQuiz(fixture.user.getId()));

        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, exception.getErrorCode());

        // Lần chặn không tiêu tốn lượt: sau khi hoàn thành warm-up, vẫn lấy được gate bình thường.
        markWarmUpPassed(fixture.user.getId());
        assertNotNull(service.getGateQuiz(fixture.user.getId()).attemptId());
    }

    @Test
    void submitGateQuizPassUnlocksGoalPhase() {
        var fixture = seed();
        markWarmUpPassed(fixture.user.getId());
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
        markWarmUpPassed(fixture.user.getId());
        var quiz = service.getGateQuiz(fixture.user.getId());

        var result = service.submitGateQuiz(fixture.user.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), wrongAnswers(fixture)));

        assertFalse(result.passed());
        assertFalse(service.current(fixture.user.getId()).gatePassed());
    }

    @Test
    void submitGateQuizFailTwiceRequiresRelearn() {
        var fixture = seed();
        markWarmUpPassed(fixture.user.getId());

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

    /** [JLAS-W0108][MP-gate] Đã fail đủ 2 lần hôm nay: không phát đề gate mới nữa (chặn thật, không
     * chỉ trả cờ relearnRequired cho FE tự xử lý). */
    @Test
    void gateQuizBlocksIssuingAfterTwoFailuresToday() {
        var fixture = seed();
        markWarmUpPassed(fixture.user.getId());
        failGateTwice(fixture);

        var ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.getGateQuiz(fixture.user.getId()));

        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, ex.getErrorCode());
    }

    /** [JLAS-W0108][MP-gate] Đã fail đủ 2 lần: nộp một đề gate ĐANG CẦM (phát trước khi bị khoá — vd
     * mở 2 tab) cũng phải bị chặn, không chỉ chặn phát đề mới. */
    @Test
    void gateQuizBlocksSubmittingAnAlreadyIssuedSessionAfterTwoFailuresToday() {
        var fixture = seed();
        markWarmUpPassed(fixture.user.getId());
        var spareQuiz = service.getGateQuiz(fixture.user.getId()); // phát trước, giữ lại chưa nộp
        failGateTwice(fixture);

        var ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.submitGateQuiz(fixture.user.getId(),
                        new LessonQuizSubmitRequest(spareQuiz.attemptId(), wrongAnswers(fixture))));

        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, ex.getErrorCode());
    }

    /** [JLAS-W0108][MP-gate] Khoá chỉ áp dụng trong ngày (giờ VN) — sang ngày mới thì phát/nộp lại
     * bình thường, khớp đúng cách gateFailuresToday() đã tính theo LearningClock (MP08). */
    @Test
    void gateQuizUnlocksOnTheNextVietnamDay() {
        var fixture = seed();
        markWarmUpPassed(fixture.user.getId());
        failGateTwice(fixture);

        clock.setClock(Clock.fixed(
                clock.now().plusDays(1).atZone(LearningClock.VN_ZONE).toInstant(), LearningClock.VN_ZONE));
        try {
            var quiz = service.getGateQuiz(fixture.user.getId());
            assertNotNull(quiz, "Sang ngày mới giờ VN phải phát đề gate lại bình thường");
        } finally {
            clock.setClock(Clock.system(LearningClock.VN_ZONE));
        }
    }

    private void failGateTwice(Fixture fixture) {
        for (int i = 0; i < 2; i++) {
            var quiz = service.getGateQuiz(fixture.user.getId());
            service.submitGateQuiz(fixture.user.getId(),
                    new LessonQuizSubmitRequest(quiz.attemptId(), wrongAnswers(fixture)));
        }
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
                "N5", "N4", "JLPT", 30,
                List.of(WeaknessEvidence.grammar(point.getId(), "PARTICLE", tag.getId(), 5, 4)));
        scopeSource.scope = new LearningCatalogScope(List.of(warmBook.getId()), List.of(goalBook.getId()));
        service.create(user.getId(), 91L);
        markWarmUpPassed(user.getId());

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

    // === [JLAS-W0108] P4/D2: blueprint quiz target theo từng nhóm mục tiêu bắt buộc ===

    @Test
    void targetLessonQuizCoversEveryGrammarPointAndVocabEntryWithoutFixedFiveCap() {
        var fixture = seedTargetLesson();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.lesson.getId());
        // 4 ngữ pháp + 3 từ vựng = 7 câu, không bị fixed-5 bỏ sót mục tiêu.
        assertEquals(7, quiz.questions().size());
    }

    @Test
    void targetLessonQuizTotalHighButFailedGroupDoesNotPass() {
        var fixture = seedTargetLesson();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.lesson.getId());
        var key = answerKeyFor(quiz.attemptId());
        assertEquals(7, key.size());

        // Đúng 6/7 (tổng 86% >= 80%) nhưng sai đúng 1 nhóm -> nhóm đó 0/1, không được đạt.
        var answers = quiz.questions().stream()
                .map(q -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(q.questionId(),
                        key.get(q.questionId()).correctOption()))
                .collect(java.util.stream.Collectors.toList());
        var firstId = quiz.questions().get(0).questionId();
        int wrong = key.get(firstId).correctOption() == 1 ? 2 : 1;
        answers.set(0, new LessonQuizSubmitRequest.LessonQuizAnswerRequest(firstId, wrong));

        var result = service.submitLessonQuiz(fixture.user.getId(), fixture.lesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers));

        assertEquals(6, result.correctAnswers());
        assertEquals(86, result.scorePercent());
        assertFalse(result.passed());
        assertTrue(result.groupResults().stream().anyMatch(group -> !group.passed()));
    }

    @Test
    void targetLessonQuizAllGroupsPass() {
        var fixture = seedTargetLesson();
        var quiz = service.getLessonQuiz(fixture.user.getId(), fixture.lesson.getId());
        var key = answerKeyFor(quiz.attemptId());
        var answers = quiz.questions().stream()
                .map(q -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(q.questionId(),
                        key.get(q.questionId()).correctOption()))
                .collect(java.util.stream.Collectors.toList());

        var result = service.submitLessonQuiz(fixture.user.getId(), fixture.lesson.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers));

        assertEquals(7, result.correctAnswers());
        assertEquals(100, result.scorePercent());
        assertTrue(result.passed());
        assertTrue(result.groupResults().stream().allMatch(LessonQuizResult.GroupResult::passed));
    }

    // === [JLAS-W0108] P4: warm-up grammar quiz (5 câu khác câu đầu vào, 4/5 đạt) ===

    @Test
    void grammarQuizReturnsUpToFiveQuestionsForThePattern() {
        var fixture = seedGrammarQuiz(6); // ngân hàng 6 câu nhưng chỉ phát 5

        var quiz = service.getGrammarQuiz(fixture.user.getId(), fixture.point.getId());

        assertNotNull(quiz.attemptId());
        assertEquals(fixture.point.getId(), quiz.lessonId());
        assertEquals(5, quiz.questions().size());
        for (var question : quiz.questions()) {
            assertEquals(4, question.options().size());
        }
    }

    @Test
    void submitGrammarQuizFourOfFivePassesAndMarksItemPassed() {
        var fixture = seedGrammarQuiz(5);
        var quiz = service.getGrammarQuiz(fixture.user.getId(), fixture.point.getId());
        var key = answerKeyFor(quiz.attemptId());

        // Đúng 4/5 (80%) -> đạt; sai đúng câu đầu.
        var answers = quiz.questions().stream()
                .map(q -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(q.questionId(),
                        key.get(q.questionId()).correctOption()))
                .collect(java.util.stream.Collectors.toList());
        var firstId = quiz.questions().get(0).questionId();
        int wrong = key.get(firstId).correctOption() == 1 ? 2 : 1;
        answers.set(0, new LessonQuizSubmitRequest.LessonQuizAnswerRequest(firstId, wrong));

        var result = service.submitGrammarQuiz(fixture.user.getId(), fixture.point.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers));

        assertTrue(result.passed());
        assertEquals(4, result.correctAnswers());
        assertEquals(80, result.scorePercent());
        assertEquals(1L, em.createQuery(
                        "select count(i) from UserLearningPathItem i where i.grammarPoint.id = :gid "
                                + "and i.learningPath.user.id = :uid and i.assessmentStatus = 'PASSED'",
                        Long.class)
                .setParameter("gid", fixture.point.getId())
                .setParameter("uid", fixture.user.getId())
                .getSingleResult());
    }

    @Test
    void submitGrammarQuizThreeOfFiveFails() {
        var fixture = seedGrammarQuiz(5);
        var quiz = service.getGrammarQuiz(fixture.user.getId(), fixture.point.getId());
        var key = answerKeyFor(quiz.attemptId());

        var answers = quiz.questions().stream()
                .map(q -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(q.questionId(),
                        key.get(q.questionId()).correctOption()))
                .collect(java.util.stream.Collectors.toList());
        // Sai 2 câu -> đúng 3/5 (60%) < 80%.
        for (int i = 0; i < 2; i++) {
            var questionId = quiz.questions().get(i).questionId();
            int wrong = key.get(questionId).correctOption() == 1 ? 2 : 1;
            answers.set(i, new LessonQuizSubmitRequest.LessonQuizAnswerRequest(questionId, wrong));
        }

        var result = service.submitGrammarQuiz(fixture.user.getId(), fixture.point.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers));

        assertFalse(result.passed());
        assertEquals(3, result.correctAnswers());
        assertEquals(60, result.scorePercent());
    }

    @Test
    void grammarQuizWithoutBankQuestionsIsNotReady() {
        var fixture = seedGrammarQuiz(0);

        var exception = assertThrows(ApiException.class, () ->
                service.getGrammarQuiz(fixture.user.getId(), fixture.point.getId()));

        assertEquals(ErrorCode.QUIZ_NOT_READY, exception.getErrorCode());
    }

    // === [JLAS-W0108] P4/D3: flashcard quiz (hỏi hết membership, đúng hết mới đạt) ===

    @Test
    void flashcardQuizTestsEveryMembershipEntryBeyondTen() {
        var fixture = seedFlashcardQuiz(12); // > 10 mục: không bỏ mục cuối

        var quiz = service.getFlashcardQuiz(fixture.user.getId());

        assertNotNull(quiz.attemptId());
        assertEquals(12, quiz.questions().size());
    }

    @Test
    void submitFlashcardQuizAllCorrectPassesAndMarksItemsPassed() {
        var fixture = seedFlashcardQuiz(3);
        var quiz = service.getFlashcardQuiz(fixture.user.getId());
        var key = answerKeyFor(quiz.attemptId());

        var answers = quiz.questions().stream()
                .map(q -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(q.questionId(),
                        key.get(q.questionId()).correctOption()))
                .collect(java.util.stream.Collectors.toList());

        var result = service.submitFlashcardQuiz(fixture.user.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers));

        assertTrue(result.passed());
        assertEquals(3, result.correctAnswers());
        assertEquals(100, result.scorePercent());
        assertEquals(3L, em.createQuery(
                        "select count(i) from UserLearningPathItem i where i.learningPath.user.id = :uid "
                                + "and i.assessmentStatus = 'PASSED' and i.vocabularyEntry is not null",
                        Long.class)
                .setParameter("uid", fixture.user.getId())
                .getSingleResult());
    }

    @Test
    void submitFlashcardQuizOneWrongFails() {
        var fixture = seedFlashcardQuiz(3);
        var quiz = service.getFlashcardQuiz(fixture.user.getId());
        var key = answerKeyFor(quiz.attemptId());

        var answers = quiz.questions().stream()
                .map(q -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(q.questionId(),
                        key.get(q.questionId()).correctOption()))
                .collect(java.util.stream.Collectors.toList());
        var firstId = quiz.questions().get(0).questionId();
        int wrong = key.get(firstId).correctOption() == 1 ? 2 : 1;
        answers.set(0, new LessonQuizSubmitRequest.LessonQuizAnswerRequest(firstId, wrong));

        var result = service.submitFlashcardQuiz(fixture.user.getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers));

        assertFalse(result.passed());
        assertEquals(2, result.correctAnswers());
        assertEquals(67, result.scorePercent());
    }

    private Map<Long, QuizAnswerKey> answerKeyFor(Long attemptId) {
        String json = em.createQuery(
                        "select s.answerKeyJson from LearningQuizSession s where s.id = :id", String.class)
                .setParameter("id", attemptId).getSingleResult();
        return mapper.readQuizAnswerKey(json);
    }

    private TargetFixture seedTargetLesson() {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var user = User.builder().role(role).email("target@test.local").loginType("LOCAL").build();
        em.persist(user);

        var tag = Tag.builder().code("TARGET").name("Target").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var book = Textbook.builder().title("Target book").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Unit").orderIndex(1).build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Target lesson")
                .status("PUBLISHED").estimatedMinutes(20).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);

        for (int i = 0; i < 4; i++) {
            em.persist(GrammarPoint.builder().lesson(lesson)
                    .pattern("～てお" + i).meaning("nghĩa ngữ pháp " + i).orderIndex(i + 1).build());
        }
        for (int i = 0; i < 3; i++) {
            var section = LessonSection.builder().lesson(lesson).title("Section " + i)
                    .sectionType("VOCAB").orderIndex(i).build();
            em.persist(section);
            var item = ContentItem.builder().section(section).contentType("VOCAB").title("Item " + i).build();
            em.persist(item);
            var entry = VocabularyEntry.builder()
                    .expression("語" + i).normalizedExpression("語" + i)
                    .reading("ご" + i).normalizedReading("ご" + i)
                    .meaning("nghĩa từ " + i).jlptLevel("N4").build();
            em.persist(entry);
            em.persist(ContentVocabulary.builder().contentItem(item).vocabularyEntry(entry).build());
        }
        em.flush();
        em.clear();
        return new TargetFixture(user, lesson);
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

    /** [JLAS-W0108] P5 — đánh dấu mọi item WARM_UP đã đạt (mô phỏng người học đã qua hết quiz warm-up) trước gate. */
    private void markWarmUpPassed(Long userId) {
        em.createQuery("update UserLearningPathItem i set i.assessmentStatus = 'PASSED' "
                        + "where i.learningPath.user.id = :uid and i.phase = 'WARM_UP'")
                .setParameter("uid", userId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    /** [JLAS-W0114][MP08] Chưa có test nào cho cơ chế quota — xác nhận 5 lượt NỘP/ngày thật sự chặn
     * lượt nộp thứ 6 (quota tính ở submit, không phải lúc phát đề). */
    @Test
    void sixthLessonQuizSubmitSameVietnamDayIsBlocked() {
        var fixture = seed();
        seedQuizAttemptsAt(fixture.user(), fixture.warmLesson().getId(), clock.now(), 5);
        var quiz = service.getLessonQuiz(fixture.user().getId(), fixture.warmLesson().getId());

        var ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.submitLessonQuiz(fixture.user().getId(), fixture.warmLesson().getId(),
                        new LessonQuizSubmitRequest(quiz.attemptId(), correctAnswers(fixture))));

        assertEquals(ErrorCode.QUIZ_ATTEMPT_LIMIT, ex.getErrorCode());
    }

    /** [JLAS-W0114][MP08][MP10] 5 lượt ghi lúc 23:30 giờ VN "hôm qua" — nộp lúc 00:05 giờ VN "hôm nay"
     * (Clock.fixed đúng khoảnh khắc, không đổi timezone JVM) phải KHÔNG bị chặn vì đã sang ngày mới
     * theo giờ VN, dù mốc UTC tương ứng của cả hai thời điểm vẫn cùng một "ngày UTC". */
    @Test
    void quizQuotaResetsAtVietnamMidnightRegardlessOfUtcDayBoundary() {
        var fixture = seed();
        var yesterdayLateNightVn = LocalDateTime.of(2026, 1, 1, 23, 30);
        seedQuizAttemptsAt(fixture.user(), fixture.warmLesson().getId(), yesterdayLateNightVn, 5);
        var quiz = service.getLessonQuiz(fixture.user().getId(), fixture.warmLesson().getId());

        var justAfterMidnightVn = LocalDateTime.of(2026, 1, 2, 0, 5);
        clock.setClock(Clock.fixed(
                justAfterMidnightVn.atZone(LearningClock.VN_ZONE).toInstant(), LearningClock.VN_ZONE));
        try {
            var result = service.submitLessonQuiz(fixture.user().getId(), fixture.warmLesson().getId(),
                    new LessonQuizSubmitRequest(quiz.attemptId(), correctAnswers(fixture)));
            assertTrue(result.passed(), "Quota phải reset ngay sau 00:00 giờ VN dù chỉ mới 00:05");
        } finally {
            clock.setClock(Clock.system(LearningClock.VN_ZONE));
        }
    }

    private void seedQuizAttemptsAt(User user, Long lessonId, LocalDateTime attemptedAt, int count) {
        for (int i = 0; i < count; i++) {
            em.persist(QuizAttempt.builder().user(user).quizType("LESSON").lessonId(lessonId)
                    .attemptedAt(attemptedAt).build());
        }
        em.flush();
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

    private GrammarFixture seedGrammarQuiz(int questionCount) {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var user = User.builder().role(role).email("grammar-quiz@test.local").loginType("LOCAL").build();
        em.persist(user);

        var tag = Tag.builder().code("PARTICLE").name("Particle").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var book = Textbook.builder().title("Grammar book").status("PUBLISHED").jlptLevel("N5").build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Unit").orderIndex(1).build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Grammar lesson")
                .status("PUBLISHED").estimatedMinutes(15).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        var point = GrammarPoint.builder().lesson(lesson).pattern("～てください").meaning("vui lòng hãy làm")
                .orderIndex(1).build();
        em.persist(point);

        List<AssessmentQuestion> questions = new ArrayList<>();
        for (int i = 0; i < questionCount; i++) {
            questions.add(seedGrammarQuestion(tag, point, "Câu ngữ pháp " + i, i % 4 + 1));
        }

        var path = UserLearningPath.builder()
                .user(user)
                .title("Grammar warm-up path")
                .goal("JLPT")
                .targetLevel("N4")
                .currentLevel("N5")
                .minutesPerDay(30)
                .build();
        em.persist(path);
        em.persist(UserLearningPathItem.builder()
                .learningPath(path)
                .grammarPoint(point)
                .phase("WARM_UP")
                .reasonCode("WEAK_GRAMMAR_POINT")
                .reason("Sai mẫu ngữ pháp")
                .orderIndex(1)
                .dayNumber(1)
                .estimatedMinutes(10)
                .build());
        em.flush();
        em.clear();
        return new GrammarFixture(user, point, questions);
    }

    private AssessmentQuestion seedGrammarQuestion(Tag tag, GrammarPoint point, String text, int correctOption) {
        var question = AssessmentQuestion.builder()
                .knowledgeTag(tag)
                .grammarPoint(point)
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

    private FlashcardFixture seedFlashcardQuiz(int entryCount) {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var user = User.builder().role(role).email("flashcard-quiz@test.local").loginType("LOCAL").build();
        em.persist(user);

        var path = UserLearningPath.builder()
                .user(user)
                .title("Flashcard warm-up path")
                .goal("JLPT")
                .targetLevel("N4")
                .currentLevel("N5")
                .minutesPerDay(30)
                .build();
        em.persist(path);

        List<VocabularyEntry> entries = new ArrayList<>();
        for (int i = 0; i < entryCount; i++) {
            var entry = VocabularyEntry.builder()
                    .expression("語" + i).normalizedExpression("語" + i)
                    .reading("ご" + i).normalizedReading("ご" + i)
                    .meaning("nghĩa từ " + i).jlptLevel("N5").build();
            em.persist(entry);
            entries.add(entry);
            em.persist(UserLearningPathItem.builder()
                    .learningPath(path)
                    .vocabularyEntry(entry)
                    .phase("WARM_UP")
                    .reasonCode("WEAK_VOCAB_ITEM")
                    .reason("Sai từ vựng")
                    .orderIndex(i + 1)
                    .dayNumber(1)
                    .estimatedMinutes(5)
                    .build());
        }
        em.flush();
        em.clear();
        return new FlashcardFixture(user, path, entries);
    }

    private LearningProfileSnapshot profile(Long userId, Long attemptId, String revision) {
        return new LearningProfileSnapshot(userId, attemptId, 10L, revision, true, true,
                "N5", "N4", "JLPT", 30, List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 5, 4)));
    }

    private record Fixture(User user, Lesson warmLesson, List<AssessmentQuestion> questions) { }

    private record TargetFixture(User user, Lesson lesson) { }

    private record GrammarFixture(User user, GrammarPoint point, List<AssessmentQuestion> questions) { }

    private record FlashcardFixture(User user, UserLearningPath path, List<VocabularyEntry> entries) { }
}
