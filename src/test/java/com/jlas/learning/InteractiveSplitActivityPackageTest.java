package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.impl.InteractiveLearningPathServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * A pace replan may split one skill into ordered parts (part_index 1..n) inside the same package.
 *
 * <p>R1 — a skill split into several activity parts (Vocabulary part 1, part 2 ...) must still count as ONE skill
 * in the package score: correct/total are pooled per skill, then the skills are averaged. Averaging per part
 * would silently give Vocabulary extra weight against Kanji/Grammar after a pace replan.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:split-activity-package-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = InteractiveSplitActivityPackageTest.Cfg.class)
class InteractiveSplitActivityPackageTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg {
        @Bean InteractiveLearningPathServiceImpl service(UserLearningPathRepository paths,
                LearningPathPackageRepository packages, UserLearningPathItemRepository items,
                LearningQuizSessionRepository quizSessions) {
            return new InteractiveLearningPathServiceImpl(paths, packages, items, quizSessions);
        }
    }

    @Autowired EntityManager em;
    @Autowired InteractiveLearningPathServiceImpl service;
    @Autowired LearningPathPackageRepository packages;

    @Test
    void splitVocabularyIsOneSkillNotTwo() {
        // Vocabulary pooled 12/20 = 60; Kanji 80; Grammar 80 -> round(220/3) = 73. Per-part averaging gives 70.
        assertEquals(73, scoreOf("equal", 10, 10, 10, 2));
    }

    @Test
    void splitPartsWithDifferentQuestionCountsArePooledByQuestionNotByPercent() {
        // Vocabulary pooled 5/14 = 36; Kanji 80; Grammar 80 -> round(196/3) = 65. Per-part averaging gives 68.
        assertEquals(65, scoreOf("unequal", 4, 4, 10, 1));
    }

    @Test
    void splitPartsCarryTheirPositionInPackageDetail() {
        var pkgId = seed("labels", 10, 10, 10, 10);
        var detail = service.packageDetail(ownerId, pkgId);
        var vocab = detail.activities().stream()
                .filter(a -> a.type() == LearningActivityType.VOCABULARY).toList();
        assertEquals(java.util.List.of(1, 2), vocab.stream().map(a -> a.partIndex()).toList());
        assertEquals(java.util.List.of(2, 2), vocab.stream().map(a -> a.partCount()).toList());
        var kanji = detail.activities().stream()
                .filter(a -> a.type() == LearningActivityType.KANJI).findFirst().orElseThrow();
        assertEquals(1, kanji.partIndex());
        assertEquals(1, kanji.partCount());
    }

    /** Lowering the pace twice appends new part numbers (5, 6...) right after the part they were cut from, so the
     * stored part_index no longer follows study order. "Phần x / n" must follow study order, not the raw index. */
    @Test
    void partPositionFollowsStudyOrderAfterRepeatedSplits() {
        var pkgId = seed("reorder", 10, 10, 10, 10);
        var vocab = em.createQuery("select i from UserLearningPathItem i where i.learningPackage.id = :pkg "
                        + "and i.activityType = :type order by i.orderIndex", UserLearningPathItem.class)
                .setParameter("pkg", pkgId).setParameter("type", LearningActivityType.VOCABULARY).getResultList();
        vocab.get(1).setPartIndex(3);
        em.flush(); // free part_index 2 before the later part claims it
        var first = vocab.getFirst();
        em.persist(UserLearningPathItem.builder().learningPath(first.getLearningPath())
                .learningPackage(first.getLearningPackage()).orderIndex(6).activityType(LearningActivityType.VOCABULARY)
                .partIndex(2).activityState(LearningActivityState.PASSED).build());
        em.flush();
        em.clear();

        var shown = service.packageDetail(ownerId, pkgId).activities().stream()
                .filter(a -> a.type() == LearningActivityType.VOCABULARY).map(a -> a.partIndex()).toList();

        assertEquals(java.util.List.of(1, 2, 3), shown);
    }

    /** Owner decision 2026-10-06: the roadmap unit is the chapter (source lesson) with ordered parts, not the day —
     * a learner may finish several parts in one sitting. Each package carries its chapter and part position. */
    @Test
    void packagesOfOneLessonReportTheirChapterAndPartPosition() {
        var firstId = seed("chapter", 10, 10, 10, 10);
        var first = packages.findById(firstId).orElseThrow();
        var second = packages.save(LearningPathPackage.builder().learningPath(first.getLearningPath())
                .phase("GOAL_LEARNING").dayNumber(2).orderIndex(2).title("Bài học · Phần 2/2").estimatedMinutes(30)
                .status(LearningPackageStatus.LOCKED).sourceLesson(first.getSourceLesson()).sourceSegmentIndex(2)
                .contentRevision("rev-1").build());
        em.persist(UserLearningPathItem.builder().learningPath(first.getLearningPath()).learningPackage(second)
                .orderIndex(9).activityType(LearningActivityType.KANJI).activityState(LearningActivityState.LOCKED).build());
        em.flush();
        em.clear();

        var detail = service.packageDetail(ownerId, second.getId());

        assertEquals(first.getSourceLesson().getId(), detail.chapterLessonId());
        assertEquals("Bài học", detail.chapterTitle());
        assertEquals(1, detail.chapterNumber());
        assertEquals(2, detail.segmentIndex());
        assertEquals(2, detail.segmentCount());
        assertEquals("Hoàn thành phần trước để mở khóa.", detail.lockReason());
    }

    private Long ownerId;

    private int scoreOf(String tag, int v1Total, int v1Correct, int v2Total, int v2Correct) {
        Long pkgId = seed(tag, v1Total, v1Correct, v2Total, v2Correct);
        service.packageDetail(ownerId, pkgId);
        em.flush();
        em.clear();
        var reloaded = packages.findById(pkgId).orElseThrow();
        assertEquals(LearningPackageStatus.COMPLETED, reloaded.getStatus());
        return reloaded.getScorePercent();
    }

    private Long seed(String tag, int v1Total, int v1Correct, int v2Total, int v2Correct) {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User owner = User.builder().role(role).email("score-weight-" + tag + "@test.local").loginType("LOCAL").build();
        em.persist(owner);
        UserLearningPath path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE")
                .version(1).experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
        em.persist(path);
        Textbook textbook = Textbook.builder().title("Goal book").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(textbook);
        Unit unit = Unit.builder().textbook(textbook).title("Unit").orderIndex(1).build();
        em.persist(unit);
        Tag knowledge = Tag.builder().code("SCORE_WEIGHT_" + tag.toUpperCase()).name("Target").tagType("KNOWLEDGE").build();
        em.persist(knowledge);
        Lesson lesson = Lesson.builder().unit(unit).knowledgeTag(knowledge).title("Bài học").status("PUBLISHED")
                .estimatedMinutes(30).orderIndex(1).lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        LearningPathPackage pkg = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING")
                .dayNumber(1).orderIndex(1).title("Ngày 1").estimatedMinutes(30)
                .status(LearningPackageStatus.IN_PROGRESS).sourceLesson(lesson).contentRevision("rev-1").build();
        em.persist(pkg);
        var vocab1 = activity(path, pkg, LearningActivityType.VOCABULARY, 1, 1, LearningActivityState.PASSED);
        var vocab2 = activity(path, pkg, LearningActivityType.VOCABULARY, 2, 2, LearningActivityState.PASSED);
        var kanji = activity(path, pkg, LearningActivityType.KANJI, 3, 1, LearningActivityState.PASSED);
        var grammar = activity(path, pkg, LearningActivityType.GRAMMAR, 4, 1, LearningActivityState.PASSED);
        var dialogue = activity(path, pkg, LearningActivityType.DIALOGUE, 5, 1, LearningActivityState.COMPLETED);
        for (var a : new UserLearningPathItem[] {vocab1, vocab2, kanji, grammar, dialogue}) em.persist(a);
        em.persist(quiz(owner, "VOCAB_MICRO_CHECK", vocab1.getId(), v1Total, v1Correct));
        em.persist(quiz(owner, "VOCAB_MICRO_CHECK", vocab2.getId(), v2Total, v2Correct));
        em.persist(quiz(owner, "KANJI_MICRO_CHECK", kanji.getId(), 10, 8));
        em.persist(quiz(owner, "GRAMMAR_MICRO_CHECK", grammar.getId(), 10, 8));
        em.flush();
        em.clear();
        ownerId = owner.getId();
        return pkg.getId();
    }

    private UserLearningPathItem activity(UserLearningPath path, LearningPathPackage pkg, LearningActivityType type,
            int order, int part, LearningActivityState state) {
        return UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).orderIndex(order)
                .activityType(type).partIndex(part).activityState(state).build();
    }

    private LearningQuizSession quiz(User owner, String type, Long activityId, int total, int correct) {
        return LearningQuizSession.builder().user(owner).quizType(type).activityId(activityId).batchIndex(0)
                .status("SUBMITTED").passed(true).questionCount(total).correctCount(correct)
                .questionsJson("[]").answerKeyJson("{}").build();
    }
}
