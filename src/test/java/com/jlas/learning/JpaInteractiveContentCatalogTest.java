package com.jlas.learning;

import com.jlas.dictionary.entity.*;
import com.jlas.dictionary.repository.*;
import com.jlas.learning.service.impl.JpaInteractiveContentCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
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

        assertTrue(reasons.containsAll(List.of("SOURCE_NOT_PUBLISHED", "LEVEL_MISMATCH",
                "DUPLICATE_VOCABULARY_IDENTITY", "KANJI_WITHOUT_LESSON_VOCAB",
                "MISSING_CANONICAL_MEMBER")));
        verifyNoInteractions(readings,audio);
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
}
