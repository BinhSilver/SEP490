package com.jlas.learning;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.TextbookRepository;
import com.jlas.dictionary.repository.UnitRepository;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:curriculum-import-schema;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = CurriculumImportSchemaTest.Config.class)
class CurriculumImportSchemaTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {
            "com.jlas.dictionary.repository",
            "com.jlas.assessment.repository"
    })
    static class Config { }

    @Autowired EntityManager em;
    @Autowired TextbookRepository textbooks;
    @Autowired UnitRepository units;
    @Autowired LessonRepository lessons;
    @Autowired AssessmentQuestionRepository questions;

    @Test
    void sourceCodesArePersistedAndResolvedAtEveryImportBoundary() {
        Tag tag = tag("LP-N5-LESSON-01");
        Textbook book = textbooks.saveAndFlush(Textbook.builder()
                .sourceCode("LP-N5-CORE").title("N5").status("DRAFT")
                .version(1).isLearningPathSource(true).build());
        Unit unit = units.saveAndFlush(Unit.builder().textbook(book)
                .sourceCode("LP-N5-U01").title("Unit 1").orderIndex(1).build());
        Lesson lesson = lessons.saveAndFlush(Lesson.builder().unit(unit).knowledgeTag(tag)
                .sourceCode("LP-N5-L01").title("Lesson 1").orderIndex(1)
                .lessonType("CURRICULUM").skill("VOCABULARY").status("DRAFT").build());
        AssessmentQuestion question = questions.saveAndFlush(AssessmentQuestion.builder()
                .sourceCode("LP-N5-Q01").knowledgeTag(tag).questionText("Question?").build());

        assertEquals(book.getId(), textbooks.findBySourceCode("LP-N5-CORE").orElseThrow().getId());
        assertEquals(unit.getId(), units.findByTextbookIdAndSourceCode(book.getId(), "LP-N5-U01").orElseThrow().getId());
        assertEquals(lesson.getId(), lessons.findByUnitIdAndSourceCode(unit.getId(), "LP-N5-L01").orElseThrow().getId());
        assertEquals(question.getId(), questions.findBySourceCode("LP-N5-Q01").orElseThrow().getId());
    }

    @Test
    void textbookSourceCodeIsGloballyUnique() {
        textbooks.saveAndFlush(book("LP-N5-CORE", "First"));
        assertThrows(DataIntegrityViolationException.class,
                () -> textbooks.saveAndFlush(book("LP-N5-CORE", "Duplicate")));
    }

    @Test
    void unitSourceCodeIsUniqueWithinTextbook() {
        Textbook book = textbooks.saveAndFlush(book("LP-N5-CORE", "N5"));
        units.saveAndFlush(Unit.builder().textbook(book).sourceCode("LP-N5-U01")
                .title("First").orderIndex(1).build());
        assertThrows(DataIntegrityViolationException.class, () -> units.saveAndFlush(
                Unit.builder().textbook(book).sourceCode("LP-N5-U01")
                        .title("Duplicate").orderIndex(2).build()));
    }

    @Test
    void lessonSourceCodeIsUniqueWithinUnit() {
        Tag tag = tag("LP-N5-LESSON-01");
        Textbook book = textbooks.saveAndFlush(book("LP-N5-CORE", "N5"));
        Unit unit = units.saveAndFlush(Unit.builder().textbook(book).sourceCode("LP-N5-U01")
                .title("Unit").orderIndex(1).build());
        lessons.saveAndFlush(lesson(unit, tag, "LP-N5-L01", "First", 1));
        assertThrows(DataIntegrityViolationException.class,
                () -> lessons.saveAndFlush(lesson(unit, tag, "LP-N5-L01", "Duplicate", 2)));
    }

    @Test
    void assessmentQuestionSourceCodeIsGloballyUnique() {
        Tag tag = tag("LP-N5-QUESTION");
        questions.saveAndFlush(question(tag, "LP-N5-Q01", "First?"));
        assertThrows(DataIntegrityViolationException.class,
                () -> questions.saveAndFlush(question(tag, "LP-N5-Q01", "Duplicate?")));
    }

    @Test
    void nullableSourceCodesRemainCompatibleWithModuleOwnedRows() {
        Textbook first = textbooks.saveAndFlush(book(null, "Legacy 1"));
        Textbook second = textbooks.saveAndFlush(book(null, "Legacy 2"));
        assertTrue(first.getId() > 0);
        assertTrue(second.getId() > first.getId());
    }

    @Test
    void schemaAndMigrationsDeclareFoundationAndStableImportKeys() throws Exception {
        String schema = Files.readString(Path.of("jlas_schema.sql"));
        String foundation = Files.readString(Path.of("sql", "migrations",
                "007_learning_stage_foundation.sql"));
        String importCodes = Files.readString(Path.of("sql", "migrations",
                "008_curriculum_import_codes.sql"));

        assertTrue(schema.contains("'FOUNDATION','N5','N4','N3','N2','N1'"));
        assertTrue(foundation.contains("chk_learning_stage_level"));
        assertTrue(foundation.contains("'FOUNDATION'"));
        assertTrue(importCodes.contains("uk_textbook_source_code"));
        assertTrue(importCodes.contains("uk_unit_source_code (textbook_id, source_code)"));
        assertTrue(importCodes.contains("uk_lesson_source_code (unit_id, source_code)"));
        assertTrue(importCodes.contains("uk_assessment_question_source_code"));
    }

    @Test
    void schemaAndMigrationDeclareGoalSpecificFoundationWithoutReplacingCanonicalContent() throws Exception {
        String schema = Files.readString(Path.of("jlas_schema.sql"));
        String migration = Files.readString(Path.of("sql", "migrations",
                "012_goal_specific_learning_foundation.sql"));

        for (String table : java.util.List.of(
                "learning_goal", "learning_topic", "learning_competency", "competency_level",
                "learning_topic_level", "lesson_learning_scope", "lesson_prerequisite",
                "learning_survey_question", "learning_survey_option",
                "user_goal_survey_submission", "user_goal_survey_selection",
                "assessment_question_usage")) {
            assertTrue(schema.contains("CREATE TABLE " + table), table + " missing from canonical schema");
            assertTrue(migration.contains("CREATE TABLE " + table), table + " missing from migration 012");
        }
        assertTrue(schema.contains("'JLPT','TRAVEL','DAILY_LIFE','BA','FROM_ZERO'"));
        assertTrue(schema.contains("learning_mode VARCHAR(30) NULL"));
        assertTrue(migration.contains("ADD COLUMN learning_mode VARCHAR(30) NULL"));
        assertTrue(migration.contains("JLPT_PLACEMENT','JLPT_MOCK_TEST','BRSE_FOUNDATION"));
        assertTrue(migration.contains("HARD_SEQUENCE','RECOMMENDED_FOUNDATION','JIT_WARMUP','OPTIONAL_ENRICHMENT"));
        assertTrue(migration.contains("'NOT_REVIEWED','NEEDS_REVIEW','APPROVED','REJECTED'"));
        assertFalse(migration.contains("DEFAULT 'DRAFT'"));
    }

    private Textbook book(String sourceCode, String title) {
        return Textbook.builder().sourceCode(sourceCode).title(title).status("DRAFT")
                .version(1).isLearningPathSource(false).build();
    }

    private Lesson lesson(Unit unit, Tag tag, String sourceCode, String title, int order) {
        return Lesson.builder().unit(unit).knowledgeTag(tag).sourceCode(sourceCode)
                .title(title).orderIndex(order).lessonType("CURRICULUM")
                .skill("VOCABULARY").status("DRAFT").build();
    }

    private AssessmentQuestion question(Tag tag, String sourceCode, String text) {
        return AssessmentQuestion.builder().sourceCode(sourceCode).knowledgeTag(tag)
                .questionText(text).build();
    }

    private Tag tag(String code) {
        Tag tag = Tag.builder().code(code).name(code).tagType("KNOWLEDGE").build();
        em.persist(tag);
        em.flush();
        return tag;
    }
}
