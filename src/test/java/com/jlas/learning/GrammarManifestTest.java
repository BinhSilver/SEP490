package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.GrammarManifest;
import com.jlas.learning.service.impl.GrammarManifestService;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * [JLAS-W0108] P2b — grammar manifest chỉ trả đúng mẫu đã chọn (pattern/meaning/ví dụ) cùng nguồn
 * sách/bài/content_item; mẫu không ánh xạ được trả BLOCKED_CONTENT, không lấy cả bài thay thế.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:grammar-manifest-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = GrammarManifestTest.ManifestTestConfiguration.class)
class GrammarManifestTest {

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.dictionary.repository"})
    @Import(GrammarManifestService.class)
    static class ManifestTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired GrammarManifestService service;

    @Test
    void resolvesOnlySelectedPatternAndItsOwnExamples() {
        var fixture = seedAbcLesson();

        var manifest = service.resolve(fixture.b().getId());

        assertTrue(manifest.ready(), "Mẫu B ánh xạ đủ phải READY");
        assertEquals(fixture.b().getPattern(), manifest.pattern());
        assertEquals(fixture.b().getMeaning(), manifest.meaning());
        assertEquals(fixture.lessonX().getId(), manifest.sourceLessonId());
        assertEquals(fixture.book().getId(), manifest.textbookId());
        assertEquals("N5", manifest.level());

        var exampleIds = manifest.examples().stream().map(GrammarManifest.ExampleBlock::contentItemId).toList();
        assertEquals(List.of(fixture.b1().getId(), fixture.b2().getId()), exampleIds,
                "Chỉ ví dụ gắn đúng mẫu B trong đúng bài nguồn được trả, đúng thứ tự");
        assertTrue(manifest.examples().stream().noneMatch(e -> e.contentItemId().equals(fixture.aExample().getId())),
                "Ví dụ của mẫu A không được lẫn vào manifest của B");
        assertTrue(manifest.examples().stream().noneMatch(e -> e.contentItemId().equals(fixture.unmapped().getId())),
                "Content item chưa gắn mẫu không được lấy");
        assertTrue(manifest.examples().stream().noneMatch(e -> e.contentItemId().equals(fixture.wrongLesson().getId())),
                "Ví dụ cùng mẫu B nhưng sai bài không được lấy");

        assertTrue(manifest.curriculumVersion().contains("gp:" + fixture.b().getId()),
                "manifest phải mang version ổn định theo identity mẫu");
    }

    @Test
    void missingMappingReturnsBlockedContent() {
        var manifest = service.resolve(999_999L);
        assertFalse(manifest.ready());
        assertEquals(GrammarManifest.BLOCKED_CONTENT, manifest.readiness());
        assertNotNull(manifest.blockReason());
        assertTrue(manifest.examples().isEmpty());
    }

    @Test
    void draftSourceReturnsBlockedContent() {
        var fixture = seedAbcLesson();
        em.merge(fixture.lessonX()).setStatus("DRAFT");
        em.flush();

        var manifest = service.resolve(fixture.b().getId());
        assertFalse(manifest.ready());
        assertEquals(GrammarManifest.BLOCKED_CONTENT, manifest.readiness());
    }

    private Fixture seedAbcLesson() {
        var tag = Tag.builder().code("PARTICLE").name("Particle").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var book = Textbook.builder().title("Grammar book").status("PUBLISHED").jlptLevel("N5").build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Unit 1").orderIndex(1).build();
        em.persist(unit);

        var lessonX = lesson(unit, tag, "Lesson X");
        em.persist(lessonX);
        var lessonY = lesson(unit, tag, "Lesson Y");
        em.persist(lessonY);

        var gpA = grammarPoint(lessonX, "～たことがある", "Đã từng làm gì", 1);
        var gpB = grammarPoint(lessonX, "～ことができる", "Có thể làm gì", 2);
        var gpC = grammarPoint(lessonX, "～なければならない", "Phải làm gì", 3);
        em.persist(gpA);
        em.persist(gpB);
        em.persist(gpC);

        var sectionX = section(lessonX, "Ví dụ", 1);
        em.persist(sectionX);
        var sectionY = section(lessonY, "Ví dụ", 1);
        em.persist(sectionY);

        // Ví dụ đúng mẫu B, đúng bài X (order 1, 2).
        var b1 = example(sectionX, gpB, "B1", 1);
        var b2 = example(sectionX, gpB, "B2", 2);
        em.persist(b1);
        em.persist(b2);
        // Ví dụ của mẫu A (cùng bài) — phải bị loại khỏi manifest của B.
        var aExample = example(sectionX, gpA, "A1", 3);
        em.persist(aExample);
        // Chưa gắn mẫu — phải bị loại.
        var unmapped = example(sectionX, null, "unmapped", 4);
        em.persist(unmapped);
        // Cùng mẫu B nhưng sai bài (lesson Y) — phải bị loại.
        var wrongLesson = example(sectionY, gpB, "B-in-Y", 1);
        em.persist(wrongLesson);

        em.flush();
        em.clear();
        return new Fixture(book, lessonX, gpB, b1, b2, aExample, unmapped, wrongLesson);
    }

    private Lesson lesson(Unit unit, Tag tag, String title) {
        return Lesson.builder().unit(unit).knowledgeTag(tag).title(title)
                .status("PUBLISHED").estimatedMinutes(15).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
    }

    private GrammarPoint grammarPoint(Lesson lesson, String pattern, String meaning, int order) {
        return GrammarPoint.builder().lesson(lesson).pattern(pattern).meaning(meaning)
                .example("Ví dụ " + pattern).orderIndex(order).build();
    }

    private LessonSection section(Lesson lesson, String title, int order) {
        return LessonSection.builder().lesson(lesson).title(title).sectionType("EXAMPLE")
                .orderIndex(order).build();
    }

    private ContentItem example(LessonSection section, GrammarPoint point, String title, int order) {
        return ContentItem.builder().section(section).contentType("GRAMMAR")
                .grammarPointId(point == null ? null : point.getId())
                .title(title).content("Nội dung " + title).orderIndex(order).build();
    }

    private record Fixture(Textbook book, Lesson lessonX, GrammarPoint b,
            ContentItem b1, ContentItem b2, ContentItem aExample,
            ContentItem unmapped, ContentItem wrongLesson) { }
}
