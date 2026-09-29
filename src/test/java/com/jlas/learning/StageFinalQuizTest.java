package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.LessonQuizSubmitRequest;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.LearningService;
import com.jlas.learning.service.impl.LearningClock;
import com.jlas.learning.service.impl.LearningServiceImpl;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:stage-final-quiz-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = StageFinalQuizTest.Cfg.class)
class StageFinalQuizTest {

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.dictionary.repository", "com.jlas.assessment.repository"})
    @Import({com.jlas.learning.service.impl.JpaLearningCatalogSource.class,
            com.jlas.learning.service.impl.WeakTagPolicy.class,
            com.jlas.learning.service.impl.LearningResourceSelectionServiceImpl.class,
            com.jlas.learning.service.impl.LearningScheduleServiceImpl.class,
            com.jlas.learning.mapper.LearningMapper.class, LearningServiceImpl.class, LearningClock.class})
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired LearningService service;
    @Autowired UserRepository users;
    @Autowired UserLearningPathRepository paths;
    @Autowired UserLearningStageRepository stages;
    @Autowired UserLearningPathItemRepository items;

    private record Fixture(User user, UserLearningPath path, UserLearningStage stage, Lesson lesson) { }

    /** One stage (N4), one goal-learning item, ten active skill questions per skill at N4. */
    private Fixture seed(boolean itemStudied) {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var user = User.builder().role(role).email("u@b.com").loginType("LOCAL").build();
        em.persist(user);
        var path = UserLearningPath.builder().user(user).title("P").goal("JLPT")
                .targetLevel("N4").currentLevel("N5").status("ACTIVE").version(1).build();
        em.persist(path);
        var stage = UserLearningStage.builder().learningPath(path).level("N4").stageIndex(0)
                .status("ACTIVE").build();
        em.persist(stage);

        var tag = Tag.builder().code("N4-GRAMMAR-1").name("N4 grammar 1").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var textbook = Textbook.builder().title("N4 book").jlptLevel("N4").status("PUBLISHED").build();
        em.persist(textbook);
        var unit = Unit.builder().textbook(textbook).title("Unit").orderIndex(1).build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).title("Bai 1").knowledgeTag(tag)
                .status("PUBLISHED").orderIndex(1).estimatedMinutes(20)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        var item = UserLearningPathItem.builder().learningPath(path).lesson(lesson).stageId(stage.getId())
                .orderIndex(1).phase("GOAL_LEARNING").dayNumber(1).estimatedMinutes(20)
                .studyStatus(itemStudied ? "STUDIED" : "NOT_STARTED").assessmentStatus("NOT_READY")
                .matchedTagCodes("[]").build();
        em.persist(item);

        for (String skill : List.of("KANJI", "VOCABULARY", "GRAMMAR", "READING", "LISTENING")) {
            for (int i = 1; i <= 12; i++) {
                em.persist(AssessmentQuestion.builder().skill(skill).jlptLevel("N4")
                        .difficulty("MEDIUM").questionType("MULTIPLE_CHOICE")
                        .questionText(skill + " Q" + i)
                        .option1("A").option2("B").option3("C").option4("D")
                        .correctOption(1).isActive(true).knowledgeTag(tag).build());
            }
        }
        em.flush();
        em.clear();
        return new Fixture(em.find(User.class, user.getId()), em.find(UserLearningPath.class, path.getId()),
                em.find(UserLearningStage.class, stage.getId()), em.find(Lesson.class, lesson.getId()));
    }

    @Test
    void finalQuizBlockedUntilAllStageItemsStudied() {
        var fixture = seed(false);

        var ex = assertThrows(ApiException.class,
                () -> service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId()));

        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, ex.getErrorCode());
    }

    @Test
    void finalQuizAvailableOnceAllStageItemsStudied() {
        var fixture = seed(true);

        var quiz = service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId());

        assertTrue(quiz.attemptId() > 0);
        // 5 skills, enough questions per skill for the ≥10-valid floor.
        long distinctSkills = quiz.questions().stream().map(q -> q.skill()).distinct().count();
        assertEquals(5, distinctSkills);
        for (String skill : List.of("KANJI", "VOCABULARY", "GRAMMAR", "READING", "LISTENING")) {
            long count = quiz.questions().stream().filter(q -> skill.equals(q.skill())).count();
            assertTrue(count >= 10, skill + " should have at least 10 questions, got " + count);
        }
    }

    @Test
    void finalQuizRejectedWhenStageAlreadyCompleted() {
        var fixture = seed(true);
        fixture.stage().setStatus("COMPLETED");
        stages.save(fixture.stage());

        var ex = assertThrows(ApiException.class,
                () -> service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId()));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    void finalQuizRejectedForAnotherUsersStage() {
        var fixture = seed(true);
        var role = em.createQuery("select r from Role r where r.name = 'LEARNER'", Role.class)
                .getSingleResult();
        var stranger = User.builder().role(role).email("s@b.com").loginType("LOCAL").build();
        users.save(stranger);

        var ex = assertThrows(ApiException.class,
                () -> service.getStageFinalQuiz(stranger.getId(), fixture.stage().getId()));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void finalQuizNotReadyWhenOneSkillHasFewerThanTenBankQuestions() {
        var fixture = seed(true);
        // Delete all but 5 of the 12 seeded LISTENING/N4 questions — below the 10-per-skill floor.
        em.createQuery("select q from AssessmentQuestion q where q.skill = 'LISTENING' and q.jlptLevel = 'N4'"
                        + " order by q.id", AssessmentQuestion.class)
                .getResultList().stream().skip(5).forEach(em::remove);
        em.flush();
        em.clear();
        var reloadedStage = stages.findById(fixture.stage().getId()).orElseThrow();

        var ex = assertThrows(ApiException.class,
                () -> service.getStageFinalQuiz(fixture.user().getId(), reloadedStage.getId()));

        assertEquals(ErrorCode.QUIZ_NOT_READY, ex.getErrorCode());
    }

    private LessonQuizSubmitRequest allCorrectAnswers(LessonQuizResponse quiz) {
        var answers = quiz.questions().stream()
                .map(q -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(q.questionId(), 1))
                .toList();
        return new LessonQuizSubmitRequest(quiz.attemptId(), answers);
    }

    @Test
    void passingFinalOnTheOnlyStageCompletesTheWholePath() {
        var fixture = seed(true);
        var quiz = service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId());

        var result = service.submitStageFinalQuiz(fixture.user().getId(), fixture.stage().getId(),
                allCorrectAnswers(quiz));

        assertTrue(result.passed());
        assertEquals(5, result.groupResults().size());
        for (var group : result.groupResults()) assertTrue(group.passed());

        var reloadedStage = stages.findById(fixture.stage().getId()).orElseThrow();
        assertEquals("COMPLETED", reloadedStage.getStatus());
        var reloadedPath = paths.findById(fixture.path().getId()).orElseThrow();
        assertEquals("COMPLETED", reloadedPath.getStatus());
        assertTrue(reloadedPath.getCompletedAt() != null);
    }

    @Test
    void passingFinalWithANextStageActivatesItInsteadOfCompletingThePath() {
        var fixture = seed(true);
        var nextStage = UserLearningStage.builder().learningPath(fixture.path()).level("N3")
                .stageIndex(1).status("PENDING").build();
        stages.save(nextStage);
        var quiz = service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId());

        service.submitStageFinalQuiz(fixture.user().getId(), fixture.stage().getId(),
                allCorrectAnswers(quiz));

        var reloadedNext = stages.findById(nextStage.getId()).orElseThrow();
        assertEquals("ACTIVE", reloadedNext.getStatus());
        var reloadedPath = paths.findById(fixture.path().getId()).orElseThrow();
        assertEquals("ACTIVE", reloadedPath.getStatus());
    }

    @Test
    void oneWeakSkillFailsDespiteAHighAverage() {
        var fixture = seed(true);
        var quiz = service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId());
        var answers = quiz.questions().stream()
                .map(q -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(
                        q.questionId(), "LISTENING".equals(q.skill()) ? 2 : 1))
                .toList();

        var result = service.submitStageFinalQuiz(fixture.user().getId(), fixture.stage().getId(),
                new LessonQuizSubmitRequest(quiz.attemptId(), answers));

        assertTrue(result.scorePercent() >= STAGE_FINAL_AVERAGE_WOULD_PASS_ALONE());
        assertFalse(result.passed());
        var listening = result.groupResults().stream()
                .filter(g -> "LISTENING".equals(g.groupKey())).findFirst().orElseThrow();
        assertFalse(listening.passed());
    }

    private static int STAGE_FINAL_AVERAGE_WOULD_PASS_ALONE() {
        // 4/5 skills fully correct, 1 skill fully wrong -> 80% overall average, which alone would
        // clear the 80% total threshold; the test's real assertion is that it still fails overall.
        return 80;
    }

    @Test
    void submittingAfterTheStageAlreadyCompletedIsRejectedAndDoesNotConsumeQuota() {
        var fixture = seed(true);
        var quiz = service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId());
        fixture.stage().setStatus("COMPLETED");
        stages.save(fixture.stage());

        var ex = assertThrows(ApiException.class, () -> service.submitStageFinalQuiz(
                fixture.user().getId(), fixture.stage().getId(), allCorrectAnswers(quiz)));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());

        // Proves quota was NOT consumed by the rejected submit: flip the stage back to ACTIVE and
        // confirm all MAX_QUIZ_ATTEMPTS_PER_DAY (5) attempts are still available, not 4.
        fixture.stage().setStatus("ACTIVE");
        stages.save(fixture.stage());
        for (int i = 0; i < 5; i++) {
            var retryQuiz = service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId());
            service.submitStageFinalQuiz(fixture.user().getId(), fixture.stage().getId(),
                    lowScoreAnswers(retryQuiz));
        }
        // The 6th would exceed the 5/day cap; if the earlier rejected submit HAD consumed one,
        // this 5-loop above would already have thrown before completing.
    }

    /** All-wrong answers except one skill, so score stays identical and non-passing across retries. */
    private LessonQuizSubmitRequest lowScoreAnswers(LessonQuizResponse quiz) {
        var answers = quiz.questions().stream()
                .map(q -> new LessonQuizSubmitRequest.LessonQuizAnswerRequest(q.questionId(), 2))
                .toList();
        return new LessonQuizSubmitRequest(quiz.attemptId(), answers);
    }

    @Test
    void twoNonImprovingRetestsSurfaceAPrerequisiteHintWithoutLockingFurtherAttempts() {
        var fixture = seed(true);

        var firstQuiz = service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId());
        var firstResult = service.submitStageFinalQuiz(fixture.user().getId(), fixture.stage().getId(),
                lowScoreAnswers(firstQuiz));
        assertFalse(firstResult.passed());

        var secondQuiz = service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId());
        var secondResult = service.submitStageFinalQuiz(fixture.user().getId(), fixture.stage().getId(),
                lowScoreAnswers(secondQuiz));
        assertFalse(secondResult.passed());
        assertFalse(secondResult.relearnRequired()); // only 1 prior attempt so far — not yet 2 retests

        var thirdQuiz = service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId());
        var thirdResult = service.submitStageFinalQuiz(fixture.user().getId(), fixture.stage().getId(),
                lowScoreAnswers(thirdQuiz));

        assertFalse(thirdResult.passed());
        assertTrue(thirdResult.relearnRequired());
        // Hint only — does not lock the stage. A 4th attempt must still be issuable.
        var fourthQuiz = service.getStageFinalQuiz(fixture.user().getId(), fixture.stage().getId());
        assertTrue(fourthQuiz.attemptId() > 0);
    }
}
