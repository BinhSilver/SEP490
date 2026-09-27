package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.flashcard.dto.FlashcardDtos.SetDetailDto;
import com.jlas.flashcard.dto.FlashcardDtos.SetRequest;
import com.jlas.flashcard.service.FlashcardService;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.service.WarmUpFlashcardService;
import jakarta.persistence.EntityManager;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * [JLAS-W0108] B1 — warm-up gộp KANJI+VOCAB thành 1 bộ flashcard: get-or-create 1 bộ,
 * gom cả item kanji + từ vựng (bỏ grammar), map card từ expression/reading/meaning, idempotent.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:warm-up-flashcard-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = WarmUpFlashcardServiceTest.WarmUpConfiguration.class)
class WarmUpFlashcardServiceTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.dictionary.repository"})
    @Import(WarmUpFlashcardService.class)
    static class WarmUpConfiguration {
        @Bean FlashcardService flashcardService() {
            return Mockito.mock(FlashcardService.class);
        }
    }

    @Autowired EntityManager em;
    @Autowired WarmUpFlashcardService service;
    @Autowired FlashcardService flashcardService;

    @org.junit.jupiter.api.BeforeEach
    void resetFlashcardMock() {
        Mockito.reset(flashcardService);
    }

    @Test
    void ensureSetMergesKanjiAndVocabIntoSingleSet() {
        var fixture = seed();
        stubCreate(500L);

        Long setId = service.ensureSet(fixture.user.getId());

        assertEquals(500L, setId);
        var request = capturedRequest(fixture.user.getId());
        assertEquals("Kanji & Từ vựng sai Smart Test", request.getTitle());
        assertEquals(2, request.getCards().size());
        Set<String> fronts = request.getCards().stream()
                .map(c -> c.getFront()).collect(Collectors.toSet());
        assertEquals(Set.of("学校", "時間"), fronts);
        assertPersistedSetId(fixture.path.getId(), 500L);
    }

    @Test
    void ensureSetIsIdempotent() {
        var fixture = seed();
        stubCreate(500L);

        assertEquals(500L, service.ensureSet(fixture.user.getId()));
        assertEquals(500L, service.ensureSet(fixture.user.getId()));

        Mockito.verify(flashcardService, Mockito.times(1))
                .create(Mockito.any(SetRequest.class), Mockito.eq(fixture.user.getId()));
    }

    @Test
    void duplicateVocabularyAcrossLessonsProducesOneCard() {
        var fixture = seed();
        var duplicate = vocabulary("学校", "がっこう", "trường học");
        em.persist(duplicate);
        var sourceLesson = em.createQuery("select l from Lesson l", Lesson.class).getResultList().get(0);
        em.persist(item(em.find(UserLearningPath.class, fixture.path.getId()), sourceLesson,
                "WEAK_VOCAB_ITEM", duplicate, 4));
        em.flush();
        stubCreate(500L);
        service.ensureSet(fixture.user.getId());
        assertEquals(2, capturedRequest(fixture.user.getId()).getCards().size());
    }

    @Test
    void ensureSetReturnsNullWhenNoWeakItems() {
        var fixture = seedEmptyPath();

        assertNull(service.ensureSet(fixture.user.getId()));
        Mockito.verify(flashcardService, Mockito.never())
                .create(Mockito.any(SetRequest.class), Mockito.anyLong());
    }

    private void stubCreate(long setId) {
        var dto = new SetDetailDto();
        dto.setId(setId);
        Mockito.when(flashcardService.create(Mockito.any(SetRequest.class), Mockito.anyLong()))
                .thenReturn(dto);
    }

    @Test
    void progressIsSavedOnlyForCardsInTheActivePathsSet() {
        var fixture = seed();
        stubCreate(500L);
        service.ensureSet(fixture.user.getId());
        var card = com.jlas.flashcard.dto.FlashcardDtos.CardDto.builder().id(81L).build();
        var set = new SetDetailDto();
        set.setCards(java.util.List.of(card));
        Mockito.when(flashcardService.set(500L, fixture.user.getId())).thenReturn(set);
        service.remember(fixture.user.getId(), 500L, 81L, "REMEMBERED");
        Mockito.verify(flashcardService).updateProgress(Mockito.eq(81L),
                Mockito.argThat(request -> "REMEMBERED".equals(request.getMemoryStatus())),
                Mockito.eq(fixture.user.getId()));
        org.junit.jupiter.api.Assertions.assertThrows(com.jlas.common.exception.ApiException.class,
                () -> service.remember(fixture.user.getId(), 501L, 81L, "REMEMBERED"));
        org.junit.jupiter.api.Assertions.assertThrows(com.jlas.common.exception.ApiException.class,
                () -> service.remember(fixture.user.getId(), 500L, 82L, "REMEMBERED"));
    }

    @Test
    void completionRequiresAllPersistedCardsRememberedAndANonemptySet() {
        var fixture = seed();
        stubCreate(500L);
        service.ensureSet(fixture.user.getId());
        var path = em.find(UserLearningPath.class, fixture.path.getId());
        for (int[] counts : new int[][] {{0, 0}, {2, 1}, {2, 2}}) {
            Mockito.when(flashcardService.summary(500L, fixture.user.getId())).thenReturn(
                    com.jlas.flashcard.dto.FlashcardDtos.StudySummaryDto.builder()
                            .total(counts[0]).remembered(counts[1]).build());
            assertEquals(counts[0] > 0 && counts[0] == counts[1], service.isCompleted(path));
        }
    }

    private SetRequest capturedRequest(Long userId) {
        var captor = ArgumentCaptor.forClass(SetRequest.class);
        Mockito.verify(flashcardService).create(captor.capture(), Mockito.eq(userId));
        return captor.getValue();
    }

    private void assertPersistedSetId(Long pathId, long expected) {
        UserLearningPath path = em.find(UserLearningPath.class, pathId);
        Long actual = path.getWarmUpFlashcardSetId();
        assertNotNull(actual);
        assertEquals(expected, actual);
        assertTrue(actual > 0);
    }

    private Fixture seed() {
        var base = seedEmptyPath();

        var kanji = vocabulary("学校", "がっこう", "trường học");
        var vocab = vocabulary("時間", "じかん", "thời gian");
        em.persist(kanji);
        em.persist(vocab);

        var lesson = lesson();
        em.persist(item(base.path, lesson, "WEAK_KANJI_ITEM", kanji, 1));
        em.persist(item(base.path, lesson, "WEAK_VOCAB_ITEM", vocab, 2));
        em.persist(item(base.path, lesson, "WEAK_GRAMMAR_POINT", null, 3));
        em.flush();
        em.clear();
        return new Fixture(base.user, base.path);
    }

    private Fixture seedEmptyPath() {
        var role = Role.builder().name("LEARNER").build();
        em.persist(role);
        var user = User.builder().role(role).email("flashcard@test.local").loginType("LOCAL").build();
        em.persist(user);
        var path = UserLearningPath.builder().user(user).title("Path").status("ACTIVE").version(1).build();
        em.persist(path);
        em.flush();
        em.clear();
        return new Fixture(user, path);
    }

    private VocabularyEntry vocabulary(String expression, String reading, String meaning) {
        return VocabularyEntry.builder()
                .expression(expression).normalizedExpression(expression)
                .reading(reading).normalizedReading(reading)
                .meaning(meaning).jlptLevel("N5").build();
    }

    private Lesson lesson() {
        var tag = Tag.builder().code("PARTICLE").name("Particle").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var book = Textbook.builder().title("Warm-up").status("PUBLISHED").jlptLevel("N5").build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Unit").orderIndex(1).build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Lesson")
                .status("PUBLISHED").estimatedMinutes(15).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }

    private UserLearningPathItem item(UserLearningPath path, Lesson lesson, String reasonCode,
            VocabularyEntry vocabularyEntry, int orderIndex) {
        return UserLearningPathItem.builder()
                .learningPath(path).lesson(lesson).vocabularyEntry(vocabularyEntry)
                .reasonCode(reasonCode).phase("WARM_UP").orderIndex(orderIndex)
                .estimatedMinutes(5).build();
    }

    private record Fixture(User user, UserLearningPath path) { }
}
