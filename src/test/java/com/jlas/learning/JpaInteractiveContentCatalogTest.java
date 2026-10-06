package com.jlas.learning;

import com.jlas.dictionary.entity.*;
import com.jlas.dictionary.repository.*;
import com.jlas.learning.service.impl.JpaInteractiveContentCatalog;
import com.jlas.learning.service.impl.JpaInteractiveContentCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaInteractiveContentCatalogTest {
    @Mock LessonRepository lessons; @Mock ContentVocabularyRepository vocabRepo; @Mock VocabularyKanjiRepository links;
    @Mock KanjiReadingRepository readings; @Mock GrammarPointRepository grammar; @Mock ContentItemRepository items;
    @Mock DialogueLineDetailRepository dialogue; @Mock ContentAudioRepository audio;

    @Test void reportsUnpublishedWrongLevelDuplicateAndMissingComponentsWithoutFallback(){
        var book=Textbook.builder().id(1L).jlptLevel("N5").status("DRAFT").version(1).build();
        var unit=Unit.builder().id(2L).textbook(book).title("U").build();
        var lesson=Lesson.builder().id(10L).unit(unit).title("L").status("DRAFT").build();
        var a=VocabularyEntry.builder().id(1L).normalizedExpression("学校").normalizedReading("がっこう")
                .expression("学校").reading("がっこう").meaning("trường").dictionaryStatus("PUBLISHED").build();
        var b=VocabularyEntry.builder().id(2L).normalizedExpression("学校").normalizedReading("がっこう")
                .expression("學校").reading("がっこう").meaning("trường").dictionaryStatus("PUBLISHED").build();
        when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(lesson)); when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(List.of(a,b));
        when(links.findByVocabularyIdIn(List.of(1L,2L))).thenReturn(List.of()); when(grammar.findByLessonIdOrderByOrderIndexAsc(10L)).thenReturn(List.of());
        when(dialogue.findByLessonId(10L)).thenReturn(List.of());
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio);

        var reasons=catalog.inspect(10L,"N4").gaps().stream().map(g->g.reasonCode()).toList();

        assertTrue(reasons.containsAll(List.of("SOURCE_NOT_PUBLISHED", "SOURCE_NOT_LEARNING_PATH", "LEVEL_MISMATCH",
                "DUPLICATE_VOCABULARY_IDENTITY", "KANJI_AND_GRAMMAR_NOT_READY")));
        verifyNoInteractions(readings,audio);
    }

    /** Owner decision 2026-10-06: a textbook lesson that is only vocabulary (>= 5 words, no Kanji/grammar) or only a
     * listening dialogue with audio is a valid target lesson; it used to block every N3–N1 path. */
    @Test void vocabularyOnlyAndDialogueWithAudioOnlyLessonsAreTargetReady(){
        var book=Textbook.builder().id(1L).jlptLevel("N3").status("PUBLISHED").isLearningPathSource(true).version(1).build();
        var vocabLesson=Lesson.builder().id(10L).unit(Unit.builder().id(2L).textbook(book).build()).title("Từ vựng").status("PUBLISHED").build();
        var words=java.util.stream.IntStream.rangeClosed(1,6).mapToObj(i->VocabularyEntry.builder().id((long)i)
                .normalizedExpression("かな"+i).normalizedReading("かな"+i).expression("かな"+i).reading("かな"+i)
                .meaning("nghĩa "+i).dictionaryStatus("PUBLISHED").build()).toList();
        when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(vocabLesson));
        when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(words);
        when(links.findByVocabularyIdIn(any())).thenReturn(List.of());
        when(grammar.findByLessonIdOrderByOrderIndexAsc(10L)).thenReturn(List.of());
        when(dialogue.findByLessonId(10L)).thenReturn(List.of());
        var listeningLesson=Lesson.builder().id(20L).unit(Unit.builder().id(3L).textbook(book).build()).title("Nghe").status("PUBLISHED").build();
        var line=com.jlas.dictionary.entity.DialogueLineDetail.builder().contentItemId(70L)
                .contentItem(com.jlas.dictionary.entity.ContentItem.builder().id(70L).content("すみません。").build()).build();
        when(lessons.findWithUnitById(20L)).thenReturn(Optional.of(listeningLesson));
        when(vocabRepo.findVocabularyByLessonId(20L)).thenReturn(List.of());
        when(grammar.findByLessonIdOrderByOrderIndexAsc(20L)).thenReturn(List.of());
        when(dialogue.findByLessonId(20L)).thenReturn(List.of(line));
        when(audio.findByOwnerTypeAndOwnerIdIn("CONTENT_ITEM",List.of(70L))).thenReturn(List.of(
                com.jlas.dictionary.entity.ContentAudio.builder().ownerType("CONTENT_ITEM").ownerId(70L).audioUrl("a.mp3").build()));
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio);

        assertEquals(List.of(), catalog.inspect(10L,"N3").gaps());
        assertEquals(List.of(), catalog.inspect(20L,"N3").gaps());
    }

    @Test void emptyLessonHasAnExplicitReadinessDiagnostic(){
        var book=Textbook.builder().id(1L).jlptLevel("N4").status("PUBLISHED").version(1).build();
        var lesson=Lesson.builder().id(10L).unit(Unit.builder().id(2L).textbook(book).build())
                .title("L").status("PUBLISHED").build();
        when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(lesson));
        when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(List.of());
        when(grammar.findByLessonIdOrderByOrderIndexAsc(10L)).thenReturn(List.of());
        when(dialogue.findByLessonId(10L)).thenReturn(List.of());
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio);

        var reasons=catalog.inspect(10L,"N4").gaps().stream().map(g->g.reasonCode()).toList();

        assertTrue(reasons.contains("EMPTY_LESSON"));
    }

    @Test void communicationStageIsValidatedByScopeAndDoesNotPretendToBeAJlptTextbookLevel(){
        var book=Textbook.builder().id(1L).jlptLevel(null).status("PUBLISHED").version(1).build();
        var lesson=Lesson.builder().id(10L).unit(Unit.builder().id(2L).textbook(book).build())
                .title("Travel basic").status("PUBLISHED").build();
        when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(lesson));
        when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(List.of());
        when(grammar.findByLessonIdOrderByOrderIndexAsc(10L)).thenReturn(List.of());
        when(dialogue.findByLessonId(10L)).thenReturn(List.of());
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio);

        var reasons=catalog.inspect(10L,"BASIC").gaps().stream().map(g->g.reasonCode()).toList();

        assertFalse(reasons.contains("LEVEL_MISMATCH"));
    }

    @Test void vocabularyBoundaryDoesNotRequireAFlashcardActivityBelowFiveValidWords(){
        for (int count : List.of(0, 1, 4, 5, 6)) {
            reset(lessons, vocabRepo, links, readings, grammar, items, dialogue, audio);
            var book=Textbook.builder().id(1L).jlptLevel("N5").status("PUBLISHED")
                    .isLearningPathSource(true).version(1).build();
            var lesson=Lesson.builder().id(10L).unit(Unit.builder().id(2L).textbook(book).build())
                    .title("L").status("PUBLISHED").build();
            var words=new ArrayList<VocabularyEntry>();
            for (int i=1; i<=count; i++) words.add(VocabularyEntry.builder().id((long)i)
                    .normalizedExpression("語"+i).normalizedReading("ご"+i).expression("語"+i)
                    .reading("ご"+i).meaning("nghĩa "+i).dictionaryStatus("PUBLISHED").build());
            when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(lesson));
            when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(words);
            when(links.findByVocabularyIdIn(count == 0 ? List.of() : java.util.stream.LongStream.rangeClosed(1,count).boxed().toList()))
                    .thenReturn(List.of());
            when(grammar.findByLessonIdOrderByOrderIndexAsc(10L)).thenReturn(List.of(
                    GrammarPoint.builder().id(70L).lesson(lesson).pattern("N は N です")
                            .meaning("câu khẳng định").example("これは本です").build()));
            when(dialogue.findByLessonId(10L)).thenReturn(List.of());
            var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio);

            var snapshot=catalog.inspect(10L,"N5");

            assertFalse(snapshot.gaps().stream().anyMatch(g -> g.reasonCode().equals("MISSING_CANONICAL_MEMBER")),
                    "vocabulary count="+count+"; Dialogue and vocabulary activity are optional here");
            assertEquals(count, snapshot.vocabularyIds().size());
            assertTrue(snapshot.gaps().isEmpty(), "valid Grammar-only lesson should be ready at vocabulary count="+count);
        }
    }

    @Test void rejectsPublishedTextbookNotMarkedAsLearningPathSource(){
        var book=Textbook.builder().id(1L).jlptLevel("N5").status("PUBLISHED")
                .isLearningPathSource(false).version(1).build();
        var lesson=Lesson.builder().id(10L).unit(Unit.builder().id(2L).textbook(book).build())
                .title("L").status("PUBLISHED").build();
        when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(lesson));
        when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(List.of());
        when(grammar.findByLessonIdOrderByOrderIndexAsc(10L)).thenReturn(List.of(
                GrammarPoint.builder().id(70L).lesson(lesson).pattern("N は N です")
                        .meaning("câu khẳng định").example("これは本です").build()));
        when(dialogue.findByLessonId(10L)).thenReturn(List.of());
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio);

        var reasons=catalog.inspect(10L,"N5").gaps().stream().map(g->g.reasonCode()).toList();

        assertTrue(reasons.contains("SOURCE_NOT_LEARNING_PATH"));
    }

    @Test void exposesOnlyOneToFourValidPublishedWordsAsReadOnlyReference(){
        var book=Textbook.builder().id(1L).jlptLevel("N5").status("PUBLISHED")
                .isLearningPathSource(true).version(1).build();
        var lesson=Lesson.builder().id(10L).unit(Unit.builder().id(2L).textbook(book).build())
                .title("L").status("PUBLISHED").build();
        var words=List.of(
                VocabularyEntry.builder().id(1L).expression("猫").reading("ねこ").meaning("mèo").wordType("danh từ")
                        .normalizedExpression("猫").normalizedReading("ねこ").dictionaryStatus("PUBLISHED").build(),
                VocabularyEntry.builder().id(2L).expression("見る").reading("みる").meaning("nhìn").wordType("động từ")
                        .normalizedExpression("見る").normalizedReading("みる").dictionaryStatus("PUBLISHED").build());
        when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(lesson));
        when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(words);
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio);

        var references=catalog.referenceVocabularyForLesson(10L);

        assertEquals(List.of(new com.jlas.learning.dto.ReferenceVocabularyResponse(1L,"猫","ねこ","mèo","danh từ"),
                new com.jlas.learning.dto.ReferenceVocabularyResponse(2L,"見る","みる","nhìn","động từ")),references);
    }

    @Test void doesNotOfferShortReferenceListForFiveOrMoreWords(){
        var book=Textbook.builder().id(1L).jlptLevel("N5").status("PUBLISHED")
                .isLearningPathSource(true).version(1).build();
        var lesson=Lesson.builder().id(10L).unit(Unit.builder().id(2L).textbook(book).build())
                .title("L").status("PUBLISHED").build();
        var words=new ArrayList<VocabularyEntry>();
        for(int i=1;i<=5;i++) words.add(VocabularyEntry.builder().id((long)i).expression("語"+i)
                .reading("ご"+i).meaning("nghĩa"+i).normalizedExpression("語"+i).normalizedReading("ご"+i)
                .dictionaryStatus("PUBLISHED").build());
        when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(lesson));
        when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(words);
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio);

        assertTrue(catalog.referenceVocabularyForLesson(10L).isEmpty());
    }
}
