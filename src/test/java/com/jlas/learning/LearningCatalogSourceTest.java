package com.jlas.learning;

import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonTag;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.service.LearningCatalogSource;
import com.jlas.learning.service.LearningResourceSelectionService;
import com.jlas.learning.service.impl.LearningResourceSelectionServiceImpl;
import com.jlas.learning.service.impl.WeakTagPolicy;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.TagAssessmentResult;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.service.impl.JpaLearningCatalogSource;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:learning-catalog-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "jlas.learning.selection.minimum-assessed-questions=4",
        "jlas.learning.selection.wrong-ratio-threshold=0.75"
})
@ContextConfiguration(classes = LearningCatalogSourceTest.CatalogTestConfiguration.class)
class LearningCatalogSourceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.learning.repository")
    @Import({JpaLearningCatalogSource.class, LearningResourceSelectionServiceImpl.class, WeakTagPolicy.class})
    static class CatalogTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LearningCatalogSource catalog;
    @Autowired LearningResourceSelectionService selector;

    @Test void selectsBothPhasesFromPersistedCatalogAndSecondaryTagEvidence() {
        var foundationBook = book("PUBLISHED");
        var foundation = lesson(foundationBook, "PUBLISHED", "KNOWLEDGE");
        var weakTag = tag("KNOWLEDGE");
        addTag(foundation, weakTag);
        var goalBook = book("PUBLISHED");
        goalBook.setJlptLevel("N4");
        var goal = lesson(goalBook, "PUBLISHED", "KNOWLEDGE");
        em.flush();
        em.clear();
        var profile = new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true,
                "N4", "N4", "JLPT", 30, List.of(new TagAssessmentResult(weakTag.getCode(), 5, 4)));
        var result = selector.select(7L, profile,
                new LearningCatalogScope(List.of(foundationBook.getId()), List.of(goalBook.getId())));
        assertEquals(List.of(foundation.getId(), goal.getId()),
                result.resources().stream().map(item -> item.resource().lessonId()).toList());
        assertEquals(List.of(LearningPhase.WARM_UP, LearningPhase.GOAL_LEARNING),
                result.resources().stream().map(item -> item.phase()).toList());
        assertEquals(Set.of(weakTag.getCode()), result.resources().getFirst().matchedTagCodes());
        assertTrue(result.coverageGaps().isEmpty());
        System.out.println("JLAS2-123 demo: WARM_UP lesson=" + foundation.getId()
                + " (N5, weak secondary tag 4/5); GOAL_LEARNING lesson=" + goal.getId() + " (N4).");
    }

    @Test void springConfigurationOverridesWeakTagDefaults() {
        var book = book("PUBLISHED");
        var lesson = lesson(book, "PUBLISHED", "KNOWLEDGE");
        em.flush();
        var profile = new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true,
                "N5", "N5", "JLPT", 30,
                List.of(new TagAssessmentResult(lesson.getKnowledgeTag().getCode(), 3, 3)));
        var result = selector.select(7L, profile, new LearningCatalogScope(List.of(book.getId()), List.of(book.getId())));
        assertEquals(LearningPhase.GOAL_LEARNING, result.resources().getFirst().phase());
        assertEquals("INSUFFICIENT_ASSESSMENT", result.coverageGaps().getFirst().reasonCode());
    }

    @Test void readsOnlyPublishedLessonsInsideTheRequestedPublishedTextbooks() {
        var book = book("PUBLISHED");
        var published = lesson(book, "PUBLISHED", "KNOWLEDGE");
        lesson(book, "DRAFT", "KNOWLEDGE");
        lesson(book, "PUBLISHED", "SITUATION");
        var draftBook = book("DRAFT");
        lesson(draftBook, "PUBLISHED", "KNOWLEDGE");
        lesson(book("PUBLISHED"), "PUBLISHED", "KNOWLEDGE");
        em.flush();
        em.clear();
        var results = catalog.findPublishedLessons(List.of(book.getId(), draftBook.getId()));
        assertEquals(List.of(published.getId()), results.stream().map(value -> value.lessonId()).toList());
        assertEquals("N5", results.getFirst().level());
        assertEquals(15, results.getFirst().estimatedMinutes());
    }

    @Test void includesSecondaryKnowledgeTagsButNotSituationTagsOrDuplicates() {
        var book = book("PUBLISHED");
        var lesson = lesson(book, "PUBLISHED", "KNOWLEDGE");
        var knowledge = tag("KNOWLEDGE");
        addTag(lesson, knowledge);
        addTag(lesson, lesson.getKnowledgeTag());
        addTag(lesson, tag("SITUATION"));
        em.flush();
        em.clear();
        var results = catalog.findPublishedLessons(List.of(book.getId()));
        assertEquals(1, results.size());
        assertEquals(Set.of(lesson.getKnowledgeTag().getCode(), knowledge.getCode()), results.getFirst().knowledgeTagCodes());
    }

    @Test void emptyScopeReturnsNoCatalog() {
        lesson(book("PUBLISHED"), "PUBLISHED", "KNOWLEDGE");
        assertTrue(catalog.findPublishedLessons(List.of()).isEmpty());
    }

    private Textbook book(String status) {
        var book = Textbook.builder().title("Test textbook").status(status).jlptLevel("N5").build();
        em.persist(book);
        return book;
    }

    private Lesson lesson(Textbook book, String status, String tagType) {
        var unit = Unit.builder().textbook(book).title("Unit").orderIndex(1).build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag(tagType)).title("Test lesson")
                .status(status).estimatedMinutes(15).orderIndex(1).lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }

    private Tag tag(String type) {
        var tag = Tag.builder().code(java.util.UUID.randomUUID().toString()).name("Test tag").tagType(type).build();
        em.persist(tag);
        return tag;
    }

    private void addTag(Lesson lesson, Tag tag) {
        em.persist(LessonTag.builder().id(new LessonTag.LessonTagId(lesson.getId(), tag.getId()))
                .lesson(lesson).tag(tag).build());
    }
}
