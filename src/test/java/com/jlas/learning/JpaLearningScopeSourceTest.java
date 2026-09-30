package com.jlas.learning;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.impl.JpaLearningScopeSource;
import jakarta.persistence.EntityManager;
import java.util.List;
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
        "spring.datasource.url=jdbc:h2:mem:learning-scope-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = JpaLearningScopeSourceTest.ScopeTestConfiguration.class)
class JpaLearningScopeSourceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.dictionary.repository")
    @Import(JpaLearningScopeSource.class)
    static class ScopeTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LearningScopeSource scope;

    @Test
    void missingMiddleLevelThrowsBlockedContentInsteadOfDemoFallback() {
        // Real published textbooks only cover N5 and N4; N3 has no real content yet.
        book("N5", "Sekai N5");
        book("N4", "Sekai N4");
        demoBook(9003L, "N3"); // demo seed KHÔNG được dùng thay học liệu thật (P6/R6)
        em.flush();
        em.clear();

        var exception = assertThrows(ApiException.class,
                () -> scope.resolve(7L, snapshot("N5", "N3")));
        assertEquals(ErrorCode.BLOCKED_CONTENT, exception.getErrorCode());
    }

    @Test
    void prefersRealTextbookOverDemoSeedForTheSameLevel() {
        var n5 = book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        var n3 = book("N3", "Sekai N3");
        demoBook(9003L, "N3"); // shadowed by the real N3 textbook
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("N5", "N3"));

        // Goal chỉ gồm cấp TRÊN assessed (N4+N3), không trộn N5; demo bị loại.
        assertEquals(List.of(n4.getId(), n3.getId()), result.goalTextbookIds());
        assertEquals(List.of(n5.getId()), result.warmUpTextbookIds());
        assertEquals(List.of("N4", "N3"), result.stages().stream().map(s -> s.level()).toList());
    }

    @Test
    void fromZeroResolvesKanaFoundationWarmUpAndGoalScope() {
        var kana = kanaBook();
        var n5 = book("N5", "Sekai N5");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("BELOW_N5", "N5"));

        assertEquals(List.of(kana.getId()), result.warmUpTextbookIds());
        assertEquals(List.of(n5.getId()), result.goalTextbookIds());
        assertEquals(List.of("N5"), result.stages().stream().map(s -> s.level()).toList());
    }

    @Test
    void resolveExcludesPublishedModuleBookAndDraftLearningBook() {
        var learningBook = book("N5", "Learning Path N5");
        moduleBook("N5", "Module-owned N5");
        draftLearningBook("N5", "Draft Learning Path N5");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("N5", "N5"));

        assertEquals(List.of(learningBook.getId()), result.goalTextbookIds());
        assertEquals(List.of(learningBook.getId()), result.warmUpTextbookIds());
    }

    @Test
    void n5ToN3ProducesTwoSequentialStages() {
        var n5 = book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        var n3 = book("N3", "Sekai N3");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("N5", "N3"));

        assertEquals(List.of(n5.getId()), result.warmUpTextbookIds());
        assertEquals(List.of(n4.getId(), n3.getId()), result.goalTextbookIds());
        assertEquals(2, result.stages().size());
        assertEquals("N4", result.stages().get(0).level());
        assertEquals(List.of(n4.getId()), result.stages().get(0).goalTextbookIds());
        assertEquals("N3", result.stages().get(1).level());
        assertEquals(List.of(n3.getId()), result.stages().get(1).goalTextbookIds());
    }

    @Test
    void n2ToN1ProducesSingleN1Stage() {
        var n2 = book("N2", "Sekai N2");
        var n1 = book("N1", "Sekai N1");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("N2", "N1"));

        assertEquals(List.of(n2.getId()), result.warmUpTextbookIds());
        assertEquals(List.of(n1.getId()), result.goalTextbookIds());
        assertEquals(List.of("N1"), result.stages().stream().map(s -> s.level()).toList());
    }

    @Test
    void n1ToN1ProducesConsolidationStage() {
        var n1 = book("N1", "Sekai N1");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("N1", "N1"));

        // Củng cố chính cấp N1: warm-up và goal cùng cấp, một chặng N1.
        assertEquals(List.of(n1.getId()), result.warmUpTextbookIds());
        assertEquals(List.of(n1.getId()), result.goalTextbookIds());
        assertEquals(List.of("N1"), result.stages().stream().map(s -> s.level()).toList());
    }

    /** [JLAS-W0108] Sách nền tảng bảng chữ cái: jlpt_level NULL + bài gắn tag {@code KANA-*}. */
    private Textbook kanaBook() {
        var book = Textbook.builder().title("Bảng chữ cái tiếng Nhật").status("PUBLISHED")
                .isLearningPathSource(true).build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Unit 1").orderIndex(1).build();
        em.persist(unit);
        var tag = Tag.builder().code("KANA-H-01").name("Kana Hàng 1").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Bài 1: Hàng あ")
                .status("PUBLISHED").estimatedMinutes(15).orderIndex(1)
                .lessonType("CURRICULUM").skill("VOCAB").build();
        em.persist(lesson);
        return book;
    }

    private LearningProfileSnapshot snapshot(String assessed, String target) {
        return new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true,
                assessed, target, "JLPT", 30, List.of());
    }

    private Textbook book(String level, String title) {
        var b = Textbook.builder().title(title).jlptLevel(level).status("PUBLISHED")
                .isLearningPathSource(true).build();
        em.persist(b);
        return b;
    }

    private Textbook moduleBook(String level, String title) {
        var book = Textbook.builder().title(title).jlptLevel(level).status("PUBLISHED")
                .isLearningPathSource(false).build();
        em.persist(book);
        return book;
    }

    private Textbook draftLearningBook(String level, String title) {
        var book = Textbook.builder().title(title).jlptLevel(level).status("DRAFT")
                .isLearningPathSource(true).build();
        em.persist(book);
        return book;
    }

    /** Simulates a textbook NOT flagged as a Learning Path source (e.g. the demo seed, or any
     * other team's textbook) — must never be picked up by {@link #scope}, hence is_learning_path_source=0. */
    private void demoBook(long id, String level) {
        em.createNativeQuery(
                        "insert into textbook (id, title, jlpt_level, status, version, is_learning_path_source, created_at, updated_at) " +
                        "values (?1, ?2, ?3, 'PUBLISHED', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                .setParameter(1, id)
                .setParameter(2, "demo " + level)
                .setParameter(3, level)
                .executeUpdate();
    }
}
