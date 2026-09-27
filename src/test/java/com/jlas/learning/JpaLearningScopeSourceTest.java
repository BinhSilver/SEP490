package com.jlas.learning;

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
    void fallsBackToDemoSeedWhenNoRealTextbookCoversALevel() {
        // Real published textbooks only cover N5 and N4; N3/N2/N1 are still being digitized.
        var n5 = book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        demoBook(9003L, "N3");
        demoBook(9004L, "N2");
        demoBook(9005L, "N1");
        em.flush();
        em.clear();

        var profile = snapshot("N5", "N1");
        var result = scope.resolve(7L, profile);

        assertEquals(List.of(n5.getId(), n4.getId(), 9003L, 9004L, 9005L),
                result.goalTextbookIds());
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

        assertEquals(List.of(n5.getId(), n4.getId(), n3.getId()), result.goalTextbookIds());
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
    }

    /** [JLAS-W0108] Sách nền tảng bảng chữ cái: jlpt_level NULL + bài gắn tag {@code KANA-*}. */
    private Textbook kanaBook() {
        var book = Textbook.builder().title("Bảng chữ cái tiếng Nhật").status("PUBLISHED").build();
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
        var b = Textbook.builder().title(title).jlptLevel(level).status("PUBLISHED").build();
        em.persist(b);
        return b;
    }

    private void demoBook(long id, String level) {
        em.createNativeQuery(
                        "insert into textbook (id, title, jlpt_level, status, version, created_at, updated_at) " +
                        "values (?1, ?2, ?3, 'PUBLISHED', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                .setParameter(1, id)
                .setParameter(2, "demo " + level)
                .setParameter(3, level)
                .executeUpdate();
    }
}
