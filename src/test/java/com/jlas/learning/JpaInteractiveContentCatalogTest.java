package com.jlas.learning;

import com.jlas.dictionary.entity.*;
import com.jlas.dictionary.repository.*;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
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
    @Mock AssessmentQuestionRepository assessmentQuestions;

    @Test void ownedLessonCanUseDraftDictionaryIdentityOnlyWithItsOwnCompleteMeaning() {
        assertDraftDictionaryIdentityReady("LP-OWNED-BOOK-N5");
        reset(lessons, vocabRepo, links, readings, grammar, items, dialogue, audio);
        assertDraftDictionaryIdentityReady("LP-REVIEWED-BOOK-N5");
    }

    private void assertDraftDictionaryIdentityReady(String sourceCode) {
        var book = Textbook.builder().id(1L).sourceCode(sourceCode).jlptLevel("N5")
                .status("PUBLISHED").isLearningPathSource(true).version(1).build();
        var lesson = Lesson.builder().id(10L).unit(Unit.builder().id(2L).textbook(book).build())
                .status("PUBLISHED").build();
        var words = new ArrayList<VocabularyEntry>();
        var contexts = new ArrayList<ContentVocabulary>();
        for (int i=1;i<=5;i++) {
            var word = VocabularyEntry.builder().id((long)i).expression("語"+i).reading("ご"+i)
                    .normalizedExpression("語"+i).normalizedReading("ご"+i)
                    .meaning("nghĩa từ điển"+i).dictionaryStatus("DRAFT").build();
            words.add(word);
            contexts.add(ContentVocabulary.builder().vocabularyEntry(word)
                    .learningMeaningVi("nghĩa theo bài"+i).build());
        }
        when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(lesson));
        when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(words);
        when(vocabRepo.findLearningContextsByLessonId(10L)).thenReturn(contexts);
        var catalog = new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

        var snapshot = catalog.inspect(10L,"N5");

        assertTrue(snapshot.gaps().isEmpty(), sourceCode + ": " + snapshot.gaps());
        assertEquals("nghĩa theo bài1", snapshot.vocabularyContexts().get(1L).meaning());
        assertEquals("DRAFT", words.getFirst().getDictionaryStatus());
    }

    @Test void ownedVocabularyFollowsLessonItemOrderRatherThanCanonicalDictionaryId() {
        var book = Textbook.builder().id(1L).sourceCode("LP-OWNED-BOOK-N5").jlptLevel("N5")
                .status("PUBLISHED").isLearningPathSource(true).version(1).build();
        var lesson = Lesson.builder().id(10L).unit(Unit.builder().id(2L).textbook(book).build())
                .status("PUBLISHED").build();
        var words = new ArrayList<VocabularyEntry>();
        var contexts = new ArrayList<ContentVocabulary>();
        for (int i=1;i<=5;i++) words.add(VocabularyEntry.builder().id((long)i)
                .expression("語"+i).reading("ご"+i).normalizedExpression("語"+i).normalizedReading("ご"+i)
                .meaning("nghĩa"+i).dictionaryStatus("PUBLISHED").build());
        for (int i=4;i>=0;i--) contexts.add(ContentVocabulary.builder()
                .vocabularyEntry(words.get(i)).learningMeaningVi("nghĩa bài"+(i+1)).build());
        when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(lesson));
        when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(words);
        when(vocabRepo.findLearningContextsByLessonId(10L)).thenReturn(contexts);
        var catalog = new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

        assertEquals(List.of(5L,4L,3L,2L,1L), catalog.inspect(10L,"N5").vocabularyIds());
    }

    @Test void mapsOnlyExactSekaiN5ReadingAndListeningRowsToTheirOrderedCourseLesson() {
        var book=Textbook.builder().id(1L).title("Sekai N5 — Giáo trình tiếng Nhật").jlptLevel("N5")
                .status("PUBLISHED").isLearningPathSource(true).version(1).build();
        var unit=Unit.builder().id(1L).textbook(book).orderIndex(2).title("N5 — Phần 2").build();
        var lesson=Lesson.builder().id(6L).unit(unit).orderIndex(1).lessonType("CURRICULUM")
                .title("Bài 6: Ăn gì uống gì: việc làm mỗi ngày").status("PUBLISHED").build();
        when(lessons.findWithUnitById(6L)).thenReturn(Optional.of(lesson));
        List<VocabularyEntry> words=java.util.stream.IntStream.rangeClosed(1,5).mapToObj(i -> VocabularyEntry.builder()
                .id((long)i).expression("語"+i).reading("ご"+i).normalizedExpression("語"+i)
                .normalizedReading("ご"+i).meaning("nghĩa"+i).dictionaryStatus("PUBLISHED").build()).toList();
        when(vocabRepo.findVocabularyByLessonId(6L)).thenReturn(words);
        when(vocabRepo.findLearningContextsByLessonId(6L)).thenReturn(List.of());
        when(links.findByVocabularyIdIn(List.of(1L,2L,3L,4L,5L))).thenReturn(List.of());
        when(grammar.findByLessonIdOrderByOrderIndexAsc(6L)).thenReturn(List.of());
        when(dialogue.findByLessonId(6L)).thenReturn(List.of());
        var reading=List.of(question(601L,"LP-OWNED-SEKAI-N5-L06-READ-c1","READING"),
                question(602L,"LP-OWNED-SEKAI-N5-L06-READ-c2","READING"));
        var listening=List.of(question(603L,"LP-OWNED-SEKAI-N5-L06-LISTEN-c1","LISTENING"),
                question(604L,"LP-OWNED-SEKAI-N5-L06-LISTEN-c2","LISTENING"));
        when(assessmentQuestions.findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                "READING","N5","LP-OWNED-SEKAI-N5-L06-READ-")).thenReturn(reading);
        when(assessmentQuestions.findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                "READING","N5","LP-OWNED-MINNA-N5-L06-READ-")).thenReturn(List.of());
        when(assessmentQuestions.findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                "LISTENING","N5","LP-OWNED-MINNA-N5-L06-LISTEN-")).thenReturn(List.of());
        when(assessmentQuestions.findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                "LISTENING","N5","LP-OWNED-SEKAI-N5-L06-LISTEN-")).thenReturn(listening);

        var snapshot=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,
                assessmentQuestions).inspect(6L,"N5");

        assertTrue(snapshot.gaps().isEmpty(),snapshot.gaps().toString());
        assertEquals(List.of(601L,602L),snapshot.readingQuestionIds());
        assertEquals(List.of(603L,604L),snapshot.listeningQuestionIds());
        verify(assessmentQuestions).findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                "READING","N5","LP-OWNED-SEKAI-N5-L06-READ-");
    }

    private AssessmentQuestion question(Long id,String sourceCode,String skill) {
        return AssessmentQuestion.builder().id(id).sourceCode(sourceCode).skill(skill).jlptLevel("N5").isActive(true).build();
    }

    @Test void mapsMinnaN4ChecksToTheMatchingOwnedSekaiChapter() {
        var book = Textbook.builder().id(4000002L).sourceCode("LP-OWNED-SEKAI-N4-CORE")
                .title("Learning N4 — Sekai N4 — Giáo trình tiếng Nhật").jlptLevel("N4")
                .status("PUBLISHED").isLearningPathSource(true).version(1).build();
        var unit = Unit.builder().id(4000002L).textbook(book).orderIndex(1).title("N4 — Phần 1").build();
        var lesson = Lesson.builder().id(4000026L).unit(unit).orderIndex(1).lessonType("CURRICULUM")
                .title("Bài 26").status("PUBLISHED").build();
        when(lessons.findWithUnitById(4000026L)).thenReturn(Optional.of(lesson));
        var words = java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(i -> VocabularyEntry.builder()
                .id((long) i).expression("語" + i).reading("ご" + i).normalizedExpression("語" + i)
                .normalizedReading("ご" + i).meaning("nghĩa" + i).dictionaryStatus("PUBLISHED").build()).toList();
        when(vocabRepo.findVocabularyByLessonId(4000026L)).thenReturn(words);
        when(vocabRepo.findLearningContextsByLessonId(4000026L)).thenReturn(List.of());
        when(links.findByVocabularyIdIn(List.of(1L, 2L, 3L, 4L, 5L))).thenReturn(List.of());
        when(grammar.findByLessonIdOrderByOrderIndexAsc(4000026L)).thenReturn(List.of());
        when(dialogue.findByLessonId(4000026L)).thenReturn(List.of());
        var checks = List.of(
                question(701L, "LP-OWNED-MINNA-N4-L26-LISTEN-c1-L-c1-1", "LISTENING"),
                question(702L, "LP-OWNED-MINNA-N4-L26-LISTEN-c2-L-c2-1", "LISTENING"),
                question(703L, "LP-OWNED-MINNA-N4-L26-LISTEN-c3-L-c3-1", "LISTENING"));
        checks.forEach(row -> row.setJlptLevel("N4"));
        var reading = question(704L, "LP-OWNED-MINNA-N4-L26-READ-P001-Q001", "READING");
        reading.setJlptLevel("N4");
        when(assessmentQuestions.findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                "LISTENING", "N4", "LP-OWNED-MINNA-N4-L26-LISTEN-")).thenReturn(checks);
        when(assessmentQuestions.findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                "READING", "N4", "LP-OWNED-MINNA-N4-L26-READ-")).thenReturn(List.of(reading));

        var snapshot = new JpaInteractiveContentCatalog(lessons, vocabRepo, links, readings, grammar,
                items, dialogue, audio, assessmentQuestions).inspect(4000026L, "N4");

        assertTrue(snapshot.gaps().isEmpty(), snapshot.gaps().toString());
        assertEquals(List.of(704L), snapshot.readingQuestionIds());
        assertEquals(List.of(701L, 702L, 703L), snapshot.listeningQuestionIds());
    }

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
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

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
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

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
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

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
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

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
            var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

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
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

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
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

        var references=catalog.referenceVocabularyForLesson(10L);

        assertEquals(List.of(new com.jlas.learning.dto.ReferenceVocabularyResponse(1L,"猫","ねこ","mèo","danh từ"),
                new com.jlas.learning.dto.ReferenceVocabularyResponse(2L,"見る","みる","nhìn","động từ")),references);
    }

    @Test void existingPackageKeepsItsShortReferenceAfterLegacyBookIsRemovedFromNewPathSources(){
        var book=Textbook.builder().id(1L).jlptLevel("N5").status("PUBLISHED")
                .isLearningPathSource(false).version(1).build();
        var lesson=Lesson.builder().id(10L).unit(Unit.builder().id(2L).textbook(book).build())
                .status("PUBLISHED").build();
        var word=VocabularyEntry.builder().id(1L).expression("猫").reading("ねこ").meaning("mèo")
                .normalizedExpression("猫").normalizedReading("ねこ").dictionaryStatus("PUBLISHED").build();
        when(lessons.findWithUnitById(10L)).thenReturn(Optional.of(lesson));
        when(vocabRepo.findVocabularyByLessonId(10L)).thenReturn(List.of(word));
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

        assertEquals(List.of(new com.jlas.learning.dto.ReferenceVocabularyResponse(1L,"猫","ねこ","mèo",null)),
                catalog.referenceVocabularyForLesson(10L));
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
        var catalog=new JpaInteractiveContentCatalog(lessons,vocabRepo,links,readings,grammar,items,dialogue,audio,assessmentQuestions);

        assertTrue(catalog.referenceVocabularyForLesson(10L).isEmpty());
    }
}
