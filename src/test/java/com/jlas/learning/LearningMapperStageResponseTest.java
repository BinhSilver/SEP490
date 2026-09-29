package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.mapper.LearningMapper;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
        "spring.datasource.url=jdbc:h2:mem:mapper-stage-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LearningMapperStageResponseTest.Cfg.class)
class LearningMapperStageResponseTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.dictionary.repository"})
    @Import(LearningMapper.class)
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired LearningMapper mapper;

    @Test
    void exposesStageIdFinalAvailabilityAndPathCompletedAt() {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var user = User.builder().role(role).email("u@b.com").loginType("LOCAL").build();
        em.persist(user);
        var path = UserLearningPath.builder().user(user).title("P").goal("JLPT")
                .targetLevel("N4").currentLevel("N5").status("COMPLETED")
                .completedAt(LocalDateTime.now().withNano(0)).version(1).build();
        em.persist(path);
        var doneStage = UserLearningStage.builder().learningPath(path).level("N4").stageIndex(0)
                .status("COMPLETED").build();
        em.persist(doneStage);
        var tag = Tag.builder().code("T").name("T").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var textbook = Textbook.builder().title("B").jlptLevel("N4").status("PUBLISHED").build();
        em.persist(textbook);
        var unit = Unit.builder().textbook(textbook).title("U").orderIndex(1).build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).title("L").knowledgeTag(tag)
                .status("PUBLISHED").orderIndex(1).estimatedMinutes(10)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        var item = UserLearningPathItem.builder().learningPath(path).lesson(lesson)
                .stageId(doneStage.getId()).orderIndex(1).phase("GOAL_LEARNING").dayNumber(1)
                .estimatedMinutes(10).studyStatus("STUDIED").assessmentStatus("NOT_READY")
                .matchedTagCodes("[]").build();
        em.persist(item);
        em.flush();
        em.clear();

        var reloadedPath = em.find(UserLearningPath.class, path.getId());
        var reloadedStage = em.find(UserLearningStage.class, doneStage.getId());
        var reloadedItem = em.find(UserLearningPathItem.class, item.getId());

        var response = mapper.toResponse(reloadedPath, List.of(reloadedItem),
                List.of(reloadedStage), false);

        assertEquals(1, response.stages().size());
        var stageDto = response.stages().get(0);
        assertEquals(doneStage.getId(), stageDto.id());
        // Stage is COMPLETED, not ACTIVE — the final quiz can no longer be issued for it
        // (getStageFinalQuiz rejects any non-ACTIVE stage with CONFLICT), so finalAvailable must
        // be false here even though the stage's items are all STUDIED.
        assertFalse(stageDto.finalAvailable());
        assertEquals("COMPLETED", response.status());
        assertEquals(reloadedPath.getCompletedAt(), response.completedAt());
    }

    @Test
    void finalNotAvailableWhileStageItemsAreUnstudied() {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var user = User.builder().role(role).email("v@b.com").loginType("LOCAL").build();
        em.persist(user);
        var path = UserLearningPath.builder().user(user).title("P").goal("JLPT")
                .targetLevel("N4").currentLevel("N5").status("ACTIVE").version(1).build();
        em.persist(path);
        var stage = UserLearningStage.builder().learningPath(path).level("N4").stageIndex(0)
                .status("ACTIVE").build();
        em.persist(stage);
        var tag = Tag.builder().code("T2").name("T2").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var textbook = Textbook.builder().title("B2").jlptLevel("N4").status("PUBLISHED").build();
        em.persist(textbook);
        var unit = Unit.builder().textbook(textbook).title("U2").orderIndex(1).build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).title("L2").knowledgeTag(tag)
                .status("PUBLISHED").orderIndex(1).estimatedMinutes(10)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        var item = UserLearningPathItem.builder().learningPath(path).lesson(lesson)
                .stageId(stage.getId()).orderIndex(1).phase("GOAL_LEARNING").dayNumber(1)
                .estimatedMinutes(10).studyStatus("NOT_STARTED").assessmentStatus("NOT_READY")
                .matchedTagCodes("[]").build();
        em.persist(item);
        em.flush();
        em.clear();

        var response = mapper.toResponse(em.find(UserLearningPath.class, path.getId()),
                List.of(em.find(UserLearningPathItem.class, item.getId())),
                List.of(em.find(UserLearningStage.class, stage.getId())), false);

        assertFalse(response.stages().get(0).finalAvailable());
    }
}
