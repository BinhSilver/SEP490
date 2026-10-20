package com.jlas.learning;

import com.jlas.dictionary.entity.GrammarPoint;
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
import com.jlas.learning.dto.WeaknessEvidence;
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
        "jlas.learning.selection.wrong-ratio-threshold=0.5"
})
@ContextConfiguration(classes = LearningCatalogSourceTest.CatalogTestConfiguration.class)
class LearningCatalogSourceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.dictionary.repository"})
    @Import({JpaLearningCatalogSource.class, LearningResourceSelectionServiceImpl.class, WeakTagPolicy.class})
    static class CatalogTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LearningCatalogSource catalog;
    @Autowired LearningResourceSelectionService selector;
    @Autowired com.jlas.learning.repository.LearningCatalogRepository catalogRepository;

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
                "N5", "N4", "JLPT", 30,
                List.of(WeaknessEvidence.grammar(weakTag.getId(), weakTag.getCode(), weakTag.getId(), 5, 3)));
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

    @Test void insufficientGrammarEvidenceKeepsSharedLessonInGoalWithoutWeakClassification() {
        var book = book("PUBLISHED");
        var lesson = lesson(book, "PUBLISHED", "KNOWLEDGE");
        em.flush();
        var profile = new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true,
                "N5", "N5", "JLPT", 30,
                List.of(WeaknessEvidence.grammar(lesson.getKnowledgeTag().getId(),
                        lesson.getKnowledgeTag().getCode(), lesson.getKnowledgeTag().getId(), 3, 3)));
        var result = selector.select(7L, profile, new LearningCatalogScope(List.of(book.getId()), List.of(book.getId())));
        assertEquals(List.of(LearningPhase.GOAL_LEARNING),
                result.resources().stream().map(item -> item.phase()).toList());
        assertTrue(result.weakEvidence().isEmpty());
        assertTrue(result.coverageGaps().isEmpty(), "Same-level curriculum does not open a separate warm-up gap");
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

    @Test void readsDraftAndPublishedLessonsOfTheOpenDraftN3LearningBookButNeverArchivedOnes() {
        // Owner 2026-10-19 (§2.28) + OD-44: the J-LAS N3 textbook is studied while it and its lessons stay DRAFT.
        var n3Draft = Textbook.builder().title("Tiếng Nhật N3").status("DRAFT").jlptLevel("N3")
                .isLearningPathSource(true).build();
        em.persist(n3Draft);
        var draftLesson = lesson(n3Draft, "DRAFT", "KNOWLEDGE");
        var publishedLesson = lesson(n3Draft, "PUBLISHED", "KNOWLEDGE");
        lesson(n3Draft, "ARCHIVED", "KNOWLEDGE");
        var n3NotPathSource = Textbook.builder().title("Module N3").status("DRAFT").jlptLevel("N3")
                .isLearningPathSource(false).build();
        em.persist(n3NotPathSource);
        lesson(n3NotPathSource, "DRAFT", "KNOWLEDGE");
        var n4Draft = Textbook.builder().title("Sekai N4").status("DRAFT").jlptLevel("N4")
                .isLearningPathSource(true).build();
        em.persist(n4Draft);
        lesson(n4Draft, "DRAFT", "KNOWLEDGE");
        em.flush();
        em.clear();
        var results = catalog.findPublishedLessons(List.of(n3Draft.getId(), n3NotPathSource.getId(), n4Draft.getId()));
        assertEquals(Set.of(draftLesson.getId(), publishedLesson.getId()),
                results.stream().map(value -> value.lessonId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("N3", results.getFirst().level());
        assertTrue(catalogRepository.findPublishedLessonDetail(draftLesson.getId()).isPresent(),
                "the lesson player opens the same open-draft lesson");
        assertTrue(catalogRepository.findPublishedLessonDetail(
                lesson(n4Draft, "DRAFT", "KNOWLEDGE").getId()).isEmpty());
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

    @Test void findGrammarPointResolvesPublishedPatternToFocusedResource() {
        var book = book("PUBLISHED");
        var lesson = lesson(book, "PUBLISHED", "KNOWLEDGE");
        var point = GrammarPoint.builder().lesson(lesson)
                .pattern("～てください").meaning("vui lòng hãy làm").orderIndex(1).build();
        em.persist(point);
        em.flush();
        Long pointId = point.getId();
        em.clear();
        var result = catalog.findGrammarPoint(pointId);
        assertTrue(result.isPresent());
        assertEquals(lesson.getId(), result.get().lessonId());
        assertEquals("～てください", result.get().title());
        assertEquals(pointId, result.get().grammarPointId());
        assertEquals(10, result.get().estimatedMinutes());
    }

    @Test void findGrammarPointSkipsUnpublishedLessonOrTextbook() {
        var draftBook = book("DRAFT");
        var draftLesson = lesson(draftBook, "PUBLISHED", "KNOWLEDGE");
        var point = GrammarPoint.builder().lesson(draftLesson)
                .pattern("～ても").meaning("dù cho").orderIndex(1).build();
        em.persist(point);
        em.flush();
        Long pointId = point.getId();
        em.clear();
        assertTrue(catalog.findGrammarPoint(pointId).isEmpty());
    }

    @Test void findComprehensionLessonPrefersExactLevel() {
        var n4Book = book("PUBLISHED");
        n4Book.setJlptLevel("N4");
        var n4Reading = lessonWithSkill(n4Book, "PUBLISHED", "READING");
        lessonWithSkill(book("PUBLISHED"), "PUBLISHED", "READING"); // N5 (đúng level N4 là N4)
        em.flush();
        em.clear();
        var result = catalog.findComprehensionLesson("READING", "N4");
        assertTrue(result.isPresent());
        assertEquals(n4Reading.getId(), result.get().lessonId());
    }

    @Test void findComprehensionLessonFallsBackToNearestLevel() {
        // Không có bài N4: N5 (cách 1, dễ hơn) được ưu tiên hơn N3 (cách 1, khó hơn).
        var n5Reading = lessonWithSkill(book("PUBLISHED"), "PUBLISHED", "READING");
        var n3Book = book("PUBLISHED");
        n3Book.setJlptLevel("N3");
        lessonWithSkill(n3Book, "PUBLISHED", "READING");
        em.flush();
        em.clear();
        var result = catalog.findComprehensionLesson("READING", "N4");
        assertTrue(result.isPresent());
        assertEquals(n5Reading.getId(), result.get().lessonId());
    }

    @Test void findComprehensionLessonPrefersComprehensiveLessonAtExactLevelOverForeignSkillLesson() {
        // [JLAS-W0108] N5/N4 không có bài chuyên READING/LISTENING riêng — mỗi bài GRAMMAR là bài
        // tổng hợp chứa sẵn phần đọc + nghe. Khi không có bài READING đúng cấp, phải chọn bài tổng
        // hợp ĐÚNG cấp (N4) thay vì lạc sang bài READING cấp khác (N3) không liên quan như trước.
        var n4Book = book("PUBLISHED");
        n4Book.setJlptLevel("N4");
        var n4Comprehensive = lesson(n4Book, "PUBLISHED", "KNOWLEDGE"); // GRAMMAR tổng hợp N4
        var n3Book = book("PUBLISHED");
        n3Book.setJlptLevel("N3");
        lessonWithSkill(n3Book, "PUBLISHED", "READING"); // READING N3 — không được chọn
        em.flush();
        em.clear();
        var result = catalog.findComprehensionLesson("READING", "N4");
        assertTrue(result.isPresent());
        assertEquals(n4Comprehensive.getId(), result.get().lessonId());
    }

    @Test void findComprehensionLessonEmptyWhenNoPublishedSkillLesson() {
        lesson(book("PUBLISHED"), "PUBLISHED", "KNOWLEDGE"); // chỉ có GRAMMAR
        em.flush();
        em.clear();
        assertTrue(catalog.findComprehensionLesson("READING", "N4").isEmpty());
    }

    @Test void findComprehensionLessonSkipsNullLevelKanaLessons() {
        // [JLAS-W0108] Bài Kana (textbook jlpt_level NULL) không phải passage đọc hiểu -> warm-up
        // READING phải báo thiếu, không chọn nhầm bài Kana (§12.6).
        var kanaBook = book("PUBLISHED");
        kanaBook.setJlptLevel(null);
        lessonWithSkill(kanaBook, "PUBLISHED", "READING");
        em.flush();
        em.clear();
        assertTrue(catalog.findComprehensionLesson("READING", "N4").isEmpty());
    }

    @Test void findComprehensionLessonsReturnsAllExactLevelWithoutFallback() {
        // [JLAS-W0108] P3 — danh sách (số nhiều) trả đủ bài ĐÚNG cấp N4, KHÔNG lạc sang bài N3
        // (khác singular findComprehensionLesson vẫn giữ fallback cấp gần nhất cho replan).
        var n4Book = book("PUBLISHED");
        n4Book.setJlptLevel("N4");
        var n4ReadingA = lessonWithSkill(n4Book, "PUBLISHED", "READING");
        var n4ReadingB = lessonWithSkill(n4Book, "PUBLISHED", "READING");
        var n3Book = book("PUBLISHED");
        n3Book.setJlptLevel("N3");
        lessonWithSkill(n3Book, "PUBLISHED", "READING");
        em.flush();
        em.clear();
        var results = catalog.findComprehensionLessons("READING", "N4");
        assertEquals(Set.of(n4ReadingA.getId(), n4ReadingB.getId()),
                results.stream().map(r -> r.lessonId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, results.size());
    }

    private Textbook book(String status) {
        var book = Textbook.builder().title("Test textbook").status(status).jlptLevel("N5").build();
        em.persist(book);
        return book;
    }

    private Lesson lessonWithSkill(Textbook book, String status, String skill) {
        var unit = Unit.builder().textbook(book).title("Unit").orderIndex(1).build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag("KNOWLEDGE")).title("Test lesson")
                .status(status).estimatedMinutes(15).orderIndex(1).lessonType("CURRICULUM").skill(skill).build();
        em.persist(lesson);
        return lesson;
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
