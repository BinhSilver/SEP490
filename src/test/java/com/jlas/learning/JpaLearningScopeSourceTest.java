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
        "spring.datasource.url=jdbc:h2:mem:learning-scope-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
    void targetBeyondPolicyIsRejectedBeforeCurriculumLookup() {
        book("N5", "Sekai N5");
        book("N4", "Sekai N4");
        em.flush();
        em.clear();

        var exception = assertThrows(ApiException.class,
                () -> scope.resolve(7L, snapshot("N5", "N3")));
        assertEquals(ErrorCode.TARGET_LEVEL_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void confirmedHigherAssessmentReviewUsesEveryPublishedStageInOrder() {
        var n5 = book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        var n3 = book("N3", "Sekai N3");
        em.flush();
        em.clear();

        var profile = snapshot("N1", "N3", "N3", "N1 Thành thạo").withReviewStartLevel("N5");
        var result = scope.resolve(7L, profile);

        assertTrue(result.warmUpTextbookIds().isEmpty());
        assertEquals(List.of(n5.getId(), n4.getId(), n3.getId()), result.goalTextbookIds());
        assertEquals(List.of("N5", "N4", "N3"), result.stages().stream().map(s -> s.level()).toList());
    }

    @Test
    void confirmedReviewAlsoWorksWhenTheSurveyLevelWasUnknownOrAnUpgrade() {
        var n5 = book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        em.flush();
        em.clear();

        var unknown = scope.resolve(7L, snapshot("N2", "N4", "UNKNOWN", "N2 Căn bản").withReviewStartLevel("N5"));
        assertEquals(List.of(n5.getId(), n4.getId()), unknown.goalTextbookIds());
        assertEquals(List.of("N5", "N4"), unknown.stages().stream().map(s -> s.level()).toList());

        var upgrade = scope.resolve(7L, snapshot("N3", "N4", "N5", "N3 Thành thạo").withReviewStartLevel("N4"));
        assertEquals(List.of(n4.getId()), upgrade.goalTextbookIds());
        assertTrue(upgrade.warmUpTextbookIds().isEmpty());
    }

    @Test
    void lowerResultChoiceBuildsTheAcceptedChainOrTheOrdinarySurveyRoute() {
        var n5 = book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        var n3 = book("N3", "Sekai N3");
        em.flush();
        em.clear();

        var accepted = scope.resolve(7L, snapshot("N5", "N3", "N4", "N5 Nhập môn").withDiscrepancyChoice("ACCEPT_RECOMMENDATION"));
        assertEquals(List.of("N5", "N4", "N3"), accepted.stages().stream().map(s -> s.level()).toList());
        assertEquals(List.of(n5.getId(), n4.getId(), n3.getId()), accepted.goalTextbookIds());

        var declined = scope.resolve(7L, snapshot("N5", "N3", "N4", "N5 Căn bản").withDiscrepancyChoice("DECLINE_RECOMMENDATION"));
        assertEquals(List.of("N3"), declined.stages().stream().map(s -> s.level()).toList());
        assertTrue(declined.warmUpTextbookIds().isEmpty());

        var notLower = assertThrows(ApiException.class, () -> scope.resolve(7L,
                snapshot("N4", "N4", "N4", "N4 Căn bản").withDiscrepancyChoice("ACCEPT_RECOMMENDATION")));
        assertEquals(ErrorCode.TARGET_LEVEL_NOT_ALLOWED, notLower.getErrorCode());
    }

    /** Owner 2026-10-21 (OD-35): UNKNOWN, target N3, Smart Test N5 accepts the N5 → N4 → N3 chain. */
    @Test
    void unknownSurveyFarBelowTheTargetBuildsTheAcceptedChain() {
        var n5 = book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        var n3 = book("N3", "Sekai N3");
        em.flush();
        em.clear();

        var accepted = scope.resolve(7L, snapshot("N5", "N3", "UNKNOWN", "N5 Nhập môn").withDiscrepancyChoice("ACCEPT_RECOMMENDATION"));
        assertEquals(List.of("N5", "N4", "N3"), accepted.stages().stream().map(s -> s.level()).toList());
        assertEquals(List.of(n5.getId(), n4.getId(), n3.getId()), accepted.goalTextbookIds());

        var noChoice = assertThrows(ApiException.class, () -> scope.resolve(7L, snapshot("N5", "N3", "UNKNOWN", "N5 Nhập môn")));
        assertEquals(ErrorCode.TARGET_LEVEL_NOT_ALLOWED, noChoice.getErrorCode());
    }

    @Test
    void excludesDemoSeedForTheSameLevel() {
        var n5 = book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        demoBook(9004L, "N4");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("N5", "N4"));

        assertEquals(List.of(n4.getId()), result.goalTextbookIds());
        assertEquals(List.of(n5.getId()), result.warmUpTextbookIds());
        assertEquals(List.of("N4"), result.stages().stream().map(s -> s.level()).toList());
    }

    @Test
    void noviceIncludesSurveyLevelAsFirstStage() {
        var n5 = book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("N4", "N4", "N5", "N4 Nhập môn"));

        assertTrue(result.warmUpTextbookIds().isEmpty());
        assertEquals(List.of(n5.getId(), n4.getId()), result.goalTextbookIds());
        assertEquals(List.of("N5", "N4"), result.stages().stream().map(s -> s.level()).toList());
    }

    @Test
    void proficientHasOnlyTargetStageAndNoWarmUp() {
        book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("N5", "N4", "N5", "N5 Thành thạo"));

        assertTrue(result.warmUpTextbookIds().isEmpty());
        assertEquals(List.of(n4.getId()), result.goalTextbookIds());
        assertEquals(List.of("N4"), result.stages().stream().map(s -> s.level()).toList());
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
        assertEquals(List.of("FOUNDATION", "N5"), result.stages().stream().map(s -> s.level()).toList());
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
        assertTrue(result.warmUpTextbookIds().isEmpty());
    }

    @Test
    void fromZeroToN4ProducesFoundationN5N4Stages() {
        var kana = kanaBook();
        var n5 = book("N5", "Sekai N5");
        var n4 = book("N4", "Sekai N4");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("BELOW_N5", "N4"));

        assertEquals(List.of(kana.getId()), result.warmUpTextbookIds());
        assertEquals(List.of(n5.getId(), n4.getId()), result.goalTextbookIds());
        assertEquals(List.of("FOUNDATION", "N5", "N4"),
                result.stages().stream().map(s -> s.level()).toList());
        assertEquals(List.of(kana.getId()), result.stages().getFirst().goalTextbookIds());
    }

    @Test
    void n2ToN1IsOutsideSupportedDemoRoutes() {
        book("N2", "Sekai N2");
        book("N1", "Sekai N1");
        em.flush();
        em.clear();

        var error = assertThrows(ApiException.class, () -> scope.resolve(7L, snapshot("N2", "N1")));
        assertEquals(ErrorCode.TARGET_LEVEL_NOT_ALLOWED, error.getErrorCode());
    }

    @Test
    void n4ToN3ProducesSingleN3Stage() {
        var n4 = book("N4", "Sekai N4");
        var n3 = book("N3", "Sekai N3");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("N4", "N3"));

        assertEquals(List.of(n4.getId()), result.warmUpTextbookIds());
        assertEquals(List.of(n3.getId()), result.goalTextbookIds());
        assertEquals(List.of("N3"), result.stages().stream().map(s -> s.level()).toList());
    }

    @Test
    void n3StageAlsoStudiesTheDraftJlasN3TextbookWithoutPublishingIt() {
        // Owner 2026-10-19 (§2.28) + OD-44: the J-LAS N3 textbook feeds N3 paths while it stays DRAFT.
        var n4 = book("N4", "Sekai N4");
        var draftN3 = draftLearningBook("N3", "Tiếng Nhật N3");
        var reviewedN3 = book("N3", "Học liệu bổ sung N3");
        moduleBook("N3", "Module-owned N3");
        var draftN4 = draftLearningBook("N4", "Draft Learning Path N4");
        em.flush();
        em.clear();

        var result = scope.resolve(7L, snapshot("N4", "N3"));

        assertEquals(List.of(draftN3.getId(), reviewedN3.getId()), result.goalTextbookIds(), "book id order, draft kept");
        assertEquals(List.of(n4.getId()), result.warmUpTextbookIds(), "N4 warm-up stays published-only");
        assertFalse(result.warmUpTextbookIds().contains(draftN4.getId()));
        assertEquals("DRAFT", em.find(Textbook.class, draftN3.getId()).getStatus());
    }

    @Test
    void n3ToN2IsOutsideSupportedDemoRoutes() {
        book("N3", "Sekai N3");
        book("N2", "Sekai N2");
        em.flush();
        em.clear();

        var error = assertThrows(ApiException.class, () -> scope.resolve(7L, snapshot("N3", "N2")));
        assertEquals(ErrorCode.TARGET_LEVEL_NOT_ALLOWED, error.getErrorCode());
    }

    @Test
    void n1ToN1IsOutsideSupportedDemoRoutes() {
        book("N1", "Sekai N1");
        em.flush();
        em.clear();

        var error = assertThrows(ApiException.class, () -> scope.resolve(7L, snapshot("N1", "N1")));
        assertEquals(ErrorCode.TARGET_LEVEL_NOT_ALLOWED, error.getErrorCode());
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

    private LearningProfileSnapshot snapshot(String assessed, String target, String survey, String band) {
        return new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true,
                assessed, target, "JLPT", 30, List.of(), List.of(), survey, band);
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
