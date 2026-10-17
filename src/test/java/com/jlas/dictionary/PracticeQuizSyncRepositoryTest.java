package com.jlas.dictionary;

import com.jlas.dictionary.repository.LessonRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [JLAS2-17] Câu lệnh native đồng bộ quiz luyện tập theo trạng thái bài chạy thật trên H2 (MODE=MySQL): chỉ quiz LESSON
 * trong dải seed nguồn ngoài của đúng bài được đổi; quiz lộ trình (id &lt; 1.000.000) và bài khác giữ nguyên.
 */
@DataJpaTest(properties = {"spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:practice-quiz-sync;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = PracticeQuizSyncRepositoryTest.Cfg.class)
class PracticeQuizSyncRepositoryTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.dictionary.repository")
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired LessonRepository lessons;

    private void sql(String statement) {
        em.createNativeQuery(statement).executeUpdate();
    }

    private void quiz(long id, long lessonId, String type, String status) {
        sql("insert into assessment_quiz (id, lesson_id, title, quiz_type, status, created_at, updated_at) values ("
                + id + ", " + lessonId + ", 'Q" + id + "', '" + type + "', '" + status + "', now(), now())");
    }

    private void question(long id, long quizId, boolean active) {
        sql("insert into assessment_question (id, knowledge_tag_id, skill, jlpt_level, difficulty, question_type, question_text,"
                + " is_active, created_at, updated_at) values (" + id + ", 1, 'READING', 'N5', 'EASY', 'MCQ', 'Câu " + id + "', "
                + active + ", now(), now())");
        sql("insert into assessment_quiz_question (quiz_id, question_id, order_index, raw_score) values (" + quizId + ", " + id + ", 1, 1)");
    }

    private String status(long quizId) {
        return (String) em.createNativeQuery("select status from assessment_quiz where id = " + quizId).getSingleResult();
    }

    private long activeCount(long quizId) {
        return ((Number) em.createNativeQuery("select count(*) from assessment_question q join assessment_quiz_question qq"
                + " on qq.question_id = q.id where qq.quiz_id = " + quizId + " and q.is_active = true").getSingleResult()).longValue();
    }

    @BeforeEach
    void seed() {
        sql("SET REFERENTIAL_INTEGRITY FALSE");
        quiz(1000041, 1000110, "LESSON", "DRAFT");   // luyện tập vnjpclub của bài cần công bố
        quiz(3000007, 3000020, "LESSON", "DRAFT");   // luyện tập Minna của bài khác
        quiz(55, 1000110, "LESSON", "DRAFT");        // quiz lộ trình (ngoài dải seed) — không được đụng
        question(1, 1000041, false);
        question(2, 1000041, false);
        question(3, 3000007, false);
        question(4, 55, false);
    }

    @Test
    void publishActivatesOnlyPracticeQuizOfThoseLessons() {
        assertEquals(2, lessons.activatePracticeQuestions(List.of(1000110L)));
        assertEquals(1, lessons.updatePracticeQuizStatus(List.of(1000110L), "PUBLISHED"));
        em.clear();
        assertEquals("PUBLISHED", status(1000041));
        assertEquals(2, activeCount(1000041));
        assertEquals("DRAFT", status(3000007));
        assertEquals(0, activeCount(3000007));
        assertEquals("DRAFT", status(55));
        assertEquals(0, activeCount(55));
        // Chạy lại không đổi gì
        assertEquals(0, lessons.activatePracticeQuestions(List.of(1000110L)));
        assertEquals(0, lessons.updatePracticeQuizStatus(List.of(1000110L), "PUBLISHED"));
    }

    @Test
    void hideMovesPracticeQuizBackToDraft() {
        lessons.updatePracticeQuizStatus(List.of(1000110L), "PUBLISHED");
        assertEquals(1, lessons.updatePracticeQuizStatus(List.of(1000110L), "DRAFT"));
        em.clear();
        assertEquals("DRAFT", status(1000041));
    }
}
