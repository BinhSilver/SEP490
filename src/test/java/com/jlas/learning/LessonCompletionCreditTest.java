package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.common.event.LearningActivityCompleted;
import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.ContentVocabulary;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.learning.entity.LessonProgress;
import com.jlas.learning.repository.LessonProgressRepository;
import com.jlas.learning.service.LearningProgressService;
import com.jlas.learning.service.impl.LearningProgressServiceImpl;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * QA for lesson-completion credit (JLAS-W0127 follow-up): the completed event must carry the actual
 * elapsed minutes capped at the lesson estimate, and the distinct vocabulary covered by the lesson.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:completion-credit-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LessonCompletionCreditTest.CreditTestConfiguration.class)
@RecordApplicationEvents
class LessonCompletionCreditTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.learning.repository")
    @Import(LearningProgressServiceImpl.class)
    static class CreditTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LearningProgressService service;
    @Autowired LessonProgressRepository progressRepository;
    @Autowired ApplicationEvents events;

    private int userSeq = 0;
    private int tagSeq = 0;

    @Test
    void completionCreditsElapsedMinutesCappedAtEstimate() {
        var user = seedUser();
        var lesson = seedLesson(10);
        em.flush();
        service.start(user.getId(), lesson.getId());
        backdateStart(user.getId(), lesson.getId(), LocalDateTime.now().minusHours(2));
        em.flush();

        service.complete(user.getId(), lesson.getId());

        var event = lessonCompletedEvent();
        assertEquals(10, event.minutes());
        assertEquals(0, event.vocabularyCount());
    }

    @Test
    void completionPublishesDistinctVocabularyCount() {
        var user = seedUser();
        var lesson = seedLesson(10);
        seedVocabulary(lesson, 2);
        em.flush();

        service.complete(user.getId(), lesson.getId());

        assertEquals(2, lessonCompletedEvent().vocabularyCount());
    }

    @Test
    void completionDeduplicatesVocabularyAcrossContentItems() {
        var user = seedUser();
        var lesson = seedLesson(10);
        seedVocabulary(lesson, 1, 2);
        em.flush();

        service.complete(user.getId(), lesson.getId());

        assertEquals(1, lessonCompletedEvent().vocabularyCount());
    }

    @Test
    void completionWithoutVocabularyPublishesZero() {
        var user = seedUser();
        var lesson = seedLesson(10);
        em.flush();

        service.complete(user.getId(), lesson.getId());

        assertEquals(0, lessonCompletedEvent().vocabularyCount());
    }

    private LearningActivityCompleted lessonCompletedEvent() {
        return events.stream(LearningActivityCompleted.class)
                .filter(event -> "COMPLETE_LESSON".equals(event.activityType()))
                .findFirst()
                .orElseThrow();
    }

    private void backdateStart(Long userId, Long lessonId, LocalDateTime startedAt) {
        LessonProgress progress = progressRepository.findByUserIdAndLessonId(userId, lessonId).orElseThrow();
        progress.setStartedAt(startedAt);
        progressRepository.save(progress);
    }

    private void seedVocabulary(Lesson lesson, int distinctCount) {
        seedVocabulary(lesson, distinctCount, 1);
    }

    /** Seeds {@code distinctCount} vocab entries, each linked to {@code itemsPerVocab} content items. */
    private void seedVocabulary(Lesson lesson, int distinctCount, int itemsPerVocab) {
        LessonSection section = LessonSection.builder()
                .lesson(lesson).title("Từ vựng").sectionType("EXPLANATION").orderIndex(1).build();
        em.persist(section);
        for (int i = 1; i <= distinctCount; i++) {
            VocabularyEntry vocab = VocabularyEntry.builder()
                    .expression("単語" + i).normalizedExpression("たんご" + i)
                    .reading("たんご").normalizedReading("たんご")
                    .usageScope("TEXTBOOK").dictionaryStatus("PUBLISHED").build();
            em.persist(vocab);
            for (int j = 1; j <= itemsPerVocab; j++) {
                ContentItem item = ContentItem.builder()
                        .section(section).contentType("VOCABULARY").title("Từ " + i + "-" + j)
                        .orderIndex((i - 1) * itemsPerVocab + j).build();
                em.persist(item);
                em.flush();
                em.persist(ContentVocabulary.builder()
                        .id(new ContentVocabulary.ContentVocabularyId(item.getId(), vocab.getId()))
                        .contentItem(item).vocabularyEntry(vocab).build());
            }
        }
    }

    private User seedUser() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User user = User.builder().role(role)
                .email("learner" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        return user;
    }

    private Lesson seedLesson(int estimatedMinutes) {
        Textbook book = Textbook.builder().title("Minna").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(book);
        Unit unit = Unit.builder().textbook(book).title("Bài 1").orderIndex(1).build();
        em.persist(unit);
        Tag tag = Tag.builder().code("N4-C-" + (++tagSeq)).name("Trợ từ").tagType("KNOWLEDGE").build();
        em.persist(tag);
        Lesson lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Trợ từ cơ bản")
                .description("Học về trợ từ.").status("PUBLISHED").estimatedMinutes(estimatedMinutes)
                .orderIndex(1).lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }
}
