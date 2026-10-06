package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.learning.config.InteractiveLearningV2Properties;
import com.jlas.learning.dto.*;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.InteractiveContentCatalog;
import com.jlas.learning.service.impl.InteractivePackageComposerImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractivePackageComposerTest {
    @Mock InteractiveContentCatalog catalog; @Mock LearningPathPackageRepository packages;
    @Mock UserLearningPathItemRepository items; @Mock LearningActivityMemberRepository members;
    @Mock UserLearningStageRepository stages;
    InteractiveLearningV2Properties properties; InteractivePackageComposerImpl composer;

    @BeforeEach void setUp(){properties=new InteractiveLearningV2Properties(); properties.setEnabled(true);
        composer=new InteractivePackageComposerImpl(properties,catalog,packages,items,members,stages);
        lenient().when(packages.existsByLearningPathId(anyLong())).thenReturn(false);
        lenient().when(packages.existsByLearningPathIdAndPhase(anyLong(),anyString())).thenReturn(false);
        lenient().when(packages.findByLearningPathIdOrderByOrderIndex(anyLong())).thenReturn(List.of());
        lenient().when(items.findMaxOrderIndexByLearningPathId(anyLong())).thenReturn(0);
        lenient().when(items.findMaxDayNumberByLearningPathId(anyLong())).thenReturn(0);
        lenient().when(stages.findByLearningPathIdOrderByStageIndexAsc(anyLong())).thenReturn(List.of(
                UserLearningStage.builder().id(41L).level("N4").stageIndex(0).status("ACTIVE").build()));
        lenient().when(packages.save(any())).thenAnswer(i->{var p=i.getArgument(0,LearningPathPackage.class);p.setId((long)p.getOrderIndex());return p;});
        lenient().when(items.save(any())).thenAnswer(i->{var item=i.getArgument(0,UserLearningPathItem.class);item.setId((long)item.getOrderIndex());return item;});}

    @Test void disabledFlagPreventsAnyComposition(){properties.setEnabled(false);
        var error=assertThrows(ApiException.class,()->composer.compose(path(),List.of(seed(10L,1))));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,error.getErrorCode()); verifyNoInteractions(catalog,packages,items,members,stages);}

    @Test void lessonWithoutKanjiOrGrammarRecordsGapAndPublishesNothing(){
        when(catalog.inspect(10L,"N4")).thenReturn(snapshot(10L,List.of(new CoverageGap("LESSON:10","KANJI_AND_GRAMMAR_NOT_READY"))));
        var result=composer.compose(path(),List.of(seed(10L,1)));
        assertFalse(result.published()); assertEquals("KANJI_AND_GRAMMAR_NOT_READY",result.gaps().getFirst().reasonCode());
        verify(packages,never()).save(any()); verify(items,never()).save(any()); verifyNoInteractions(members,stages);
    }

    /** Owner decision 2026-10-06: a listening-only lesson whose dialogue audio is missing is deferred (reported, not
     * silently dropped) instead of blocking the whole path; it joins the path once the audio exists. */
    @Test void listeningOnlyLessonWithoutAudioIsDeferredAndDoesNotBlockThePath(){
        when(catalog.inspect(10L,"N4")).thenReturn(snapshot(10L,List.of()));
        when(catalog.inspect(20L,"N4")).thenReturn(new InteractiveContentSnapshot(Lesson.builder().id(20L).build(),"rev",
                List.of(),List.of(),List.of(),List.of(70L,71L),List.of(new CoverageGap("LESSON:20","DIALOGUE_AUDIO_NOT_READY"))));
        var result=composer.compose(path(),List.of(seed(10L,1),seed(20L,2)));
        assertTrue(result.published()); assertEquals(1,result.packageCount());
        assertEquals(List.of(new CoverageGap("LESSON:20","DEFERRED_DIALOGUE_AUDIO_NOT_READY")),result.gaps());
        verify(packages,times(1)).save(any());
    }

    @Test void pathWithOnlyDeferredLessonsIsNotPublished(){
        when(catalog.inspect(20L,"N4")).thenReturn(new InteractiveContentSnapshot(Lesson.builder().id(20L).build(),"rev",
                List.of(),List.of(),List.of(),List.of(70L),List.of(new CoverageGap("LESSON:20","DIALOGUE_AUDIO_NOT_READY"))));
        var result=composer.compose(path(),List.of(seed(20L,1)));
        assertFalse(result.published());
        verify(packages,never()).save(any());
    }

    @Test void composesExactlyFourActivitiesWithCanonicalMembershipAndDeduplicatesLesson(){
        when(catalog.inspect(10L,"N4")).thenReturn(snapshot(10L,List.of()));
        var result=composer.compose(path(),List.of(seed(10L,1),seed(10L,2)));
        assertTrue(result.published()); assertEquals(1,result.packageCount());
        verify(packages,times(1)).save(any()); verify(items,times(4)).save(any()); verify(members,times(4)).saveAll(any());
        var captor=ArgumentCaptor.forClass(UserLearningPathItem.class); verify(items,times(4)).save(captor.capture());
        assertEquals(List.of(LearningActivityType.VOCABULARY,LearningActivityType.KANJI,LearningActivityType.GRAMMAR,LearningActivityType.DIALOGUE),
                captor.getAllValues().stream().map(UserLearningPathItem::getActivityType).toList());
        verify(packages).save(argThat(value -> Long.valueOf(41L).equals(value.getStageId())));
    }

    @Test void skipsVocabularyActivityBelowFiveWordsAndAllowsOptionalDialogue(){
        var smallVocabulary=new InteractiveContentSnapshot(Lesson.builder().id(10L).build(),"revision",
                List.of(1L,2L,3L,4L),List.of(30L),List.of(40L),List.of(),List.of());
        when(catalog.inspect(10L,"N4")).thenReturn(smallVocabulary);

        var result=composer.compose(path(),List.of(seed(10L,1)));

        assertTrue(result.published());
        var captor=ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items,times(2)).save(captor.capture());
        assertEquals(List.of(LearningActivityType.KANJI,LearningActivityType.GRAMMAR),
                captor.getAllValues().stream().map(UserLearningPathItem::getActivityType).toList());
        verify(members,times(2)).saveAll(any());
    }

    @Test void createsVocabularyActivityAtFiveAndKeepsEveryVocabularyMember(){
        var fiveVocabulary=new InteractiveContentSnapshot(Lesson.builder().id(10L).build(),"revision",
                List.of(1L,2L,3L,4L,5L),List.of(30L),List.of(40L),List.of(),List.of());
        when(catalog.inspect(10L,"N4")).thenReturn(fiveVocabulary);

        var result=composer.compose(path(),List.of(seed(10L,1)));

        assertTrue(result.published());
        var captor=ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items,times(3)).save(captor.capture());
        assertEquals(List.of(LearningActivityType.VOCABULARY,LearningActivityType.KANJI,LearningActivityType.GRAMMAR),
                captor.getAllValues().stream().map(UserLearningPathItem::getActivityType).toList());
        verify(members).saveAll(argThat(rows -> {
            var savedRows=StreamSupport.stream(rows.spliterator(),false).toList();
            return savedRows.size()==5 && savedRows.stream().allMatch(row -> row.getMemberType()==LearningMemberType.VOCABULARY_ENTRY);
        }));
    }

    @Test void splitsLongVocabularyAndKanjiMembershipIntoOrderedActivitiesOfAtMostFifteen(){
        var vocabularyIds=java.util.stream.LongStream.rangeClosed(1,32).boxed().toList();
        var kanjiIds=java.util.stream.LongStream.rangeClosed(101,117).boxed().toList();
        when(catalog.inspect(10L,"N4")).thenReturn(new InteractiveContentSnapshot(
                Lesson.builder().id(10L).build(),"revision",vocabularyIds,kanjiIds,List.of(),List.of(),List.of()));

        assertTrue(composer.compose(path(),List.of(seed(10L,1))).published());

        var activityCaptor=ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items,times(5)).save(activityCaptor.capture());
        assertEquals(List.of(LearningActivityType.VOCABULARY,LearningActivityType.VOCABULARY,
                        LearningActivityType.VOCABULARY,LearningActivityType.KANJI,LearningActivityType.KANJI),
                activityCaptor.getAllValues().stream().map(UserLearningPathItem::getActivityType).toList());
        assertEquals(List.of(1,2,3,4,5),
                activityCaptor.getAllValues().stream().map(UserLearningPathItem::getOrderIndex).toList());
        // uk(package, activity_type, part_index): same-type activities of one package must be numbered 1..n
        // (UAT 2026-10-06: every part was saved as 1 and MySQL rejected the second Vocabulary part).
        var partsByPackageAndType=activityCaptor.getAllValues().stream().collect(java.util.stream.Collectors.groupingBy(
                a->a.getLearningPackage().getOrderIndex()+":"+a.getActivityType(),java.util.LinkedHashMap::new,
                java.util.stream.Collectors.mapping(UserLearningPathItem::getPartIndex,java.util.stream.Collectors.toList())));
        partsByPackageAndType.values().forEach(parts->assertEquals(
                java.util.stream.IntStream.rangeClosed(1,parts.size()).boxed().toList(),parts,partsByPackageAndType.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<LearningActivityMember>> memberCaptor=ArgumentCaptor.forClass(Iterable.class);
        verify(members,times(5)).saveAll(memberCaptor.capture());
        var groups=memberCaptor.getAllValues().stream()
                .map(group -> StreamSupport.stream(group.spliterator(),false).toList()).toList();
        assertEquals(List.of(11,11,10,9,8),groups.stream().map(List::size).toList());
        assertEquals(vocabularyIds,groups.subList(0,3).stream().flatMap(List::stream)
                .map(LearningActivityMember::getCanonicalId).toList());
        assertEquals(kanjiIds,groups.subList(3,5).stream().flatMap(List::stream)
                .map(LearningActivityMember::getCanonicalId).toList());
    }

    @Test void spillsLargeLessonAcrossBudgetedDaysWithoutLosingCanonicalMembership(){
        properties.setEnabled(true);
        var boundedPath = path();
        boundedPath.setMinutesPerDay(15);
        var vocabularyIds = java.util.stream.LongStream.rangeClosed(1, 30).boxed().toList();
        var kanjiIds = List.of(101L);
        var grammarIds = List.of(201L);
        when(catalog.inspect(10L,"N4")).thenReturn(new InteractiveContentSnapshot(
                Lesson.builder().id(10L).build(),"revision",vocabularyIds,kanjiIds,grammarIds,List.of(),List.of()));

        var result = composer.compose(boundedPath,List.of(seed(10L,1)));

        assertTrue(result.published());
        assertTrue(result.packageCount() > 1);
        var packageCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages,atLeast(2)).save(packageCaptor.capture());
        var createdPackages = packageCaptor.getAllValues();
        assertTrue(createdPackages.stream().allMatch(pkg -> pkg.getEstimatedMinutes() <= 15));
        assertEquals(java.util.stream.IntStream.rangeClosed(1,createdPackages.size()).boxed().toList(),
                createdPackages.stream().map(LearningPathPackage::getSourceSegmentIndex).toList());
        assertEquals(java.util.stream.IntStream.rangeClosed(1,createdPackages.size()).boxed().toList(),
                createdPackages.stream().map(LearningPathPackage::getDayNumber).toList());

        var activityCaptor = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items,atLeast(1)).save(activityCaptor.capture());
        var activities = activityCaptor.getAllValues();
        assertTrue(activities.stream().allMatch(activity -> activity.getEstimatedMinutes() != null
                && activity.getEstimatedMinutes() > 0));
        assertTrue(activities.stream().allMatch(activity -> activity.getEstimatedMinutes()
                <= 15));
        assertEquals(2, activities.stream()
                .filter(activity -> activity.getActivityType() == LearningActivityType.VOCABULARY).count());
        assertEquals(1, activities.stream()
                .filter(activity -> activity.getActivityType() == LearningActivityType.KANJI).count());
        assertEquals(1, activities.stream()
                .filter(activity -> activity.getActivityType() == LearningActivityType.GRAMMAR).count());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<LearningActivityMember>> memberCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(members,atLeast(1)).saveAll(memberCaptor.capture());
        var persistedMembers = memberCaptor.getAllValues().stream()
                .flatMap(group -> StreamSupport.stream(group.spliterator(),false)).toList();
        assertEquals(32,persistedMembers.size());
        assertEquals(vocabularyIds,persistedMembers.stream()
                .filter(member -> member.getMemberType() == LearningMemberType.VOCABULARY_ENTRY)
                .map(LearningActivityMember::getCanonicalId).toList());
        assertEquals(kanjiIds,persistedMembers.stream()
                .filter(member -> member.getMemberType() == LearningMemberType.KANJI)
                .map(LearningActivityMember::getCanonicalId).toList());
        assertEquals(grammarIds,persistedMembers.stream()
                .filter(member -> member.getMemberType() == LearningMemberType.GRAMMAR_POINT)
                .map(LearningActivityMember::getCanonicalId).toList());
    }

    @Test void composesN3ContentIntoTheMatchingN3Stage(){
        when(stages.findByLearningPathIdOrderByStageIndexAsc(5L)).thenReturn(List.of(
                UserLearningStage.builder().id(51L).level("N3").stageIndex(0).status("ACTIVE").build()));
        when(catalog.inspect(30L,"N3")).thenReturn(snapshot(30L,List.of()));

        var result=composer.compose(path(),List.of(seed(30L,1,"N3")));

        assertTrue(result.published());
        verify(catalog).inspect(30L,"N3");
        verify(packages).save(argThat(value -> Long.valueOf(51L).equals(value.getStageId())));
    }

    @Test void missingStageBlocksCompositionWithLevelMismatch(){
        when(stages.findByLearningPathIdOrderByStageIndexAsc(5L)).thenReturn(List.of());
        when(catalog.inspect(10L,"N4")).thenReturn(snapshot(10L,List.of()));

        var result=composer.compose(path(),List.of(seed(10L,1)));

        assertFalse(result.published());
        assertEquals("LEVEL_MISMATCH",result.gaps().getFirst().reasonCode());
        verify(packages,never()).save(any());
    }

    @Test void retryReturnsExistingPackagesWithoutRecomposing(){
        when(packages.existsByLearningPathIdAndPhase(5L,"GOAL_LEARNING")).thenReturn(true);
        when(packages.findByLearningPathIdOrderByOrderIndex(5L)).thenReturn(
                List.of(LearningPathPackage.builder().phase("GOAL_LEARNING").build()));
        var result=composer.compose(path(),List.of(seed(10L,1)));
        assertTrue(result.published()); assertEquals(1,result.packageCount()); verifyNoInteractions(catalog,items,members,stages);
    }

    /** [JLAS-W0193] Task 9 regression: a real bug found while wiring Warm-up B — an unscoped
     * {@code existsByLearningPathId} check made this composer wrongly believe target packages already
     * existed (because warm-up ones did) and silently compose zero GOAL_LEARNING packages for every V2
     * path that has any warm-up content. The idempotency check must be scoped to GOAL_LEARNING only. */
    @Test void composesTargetPackagesEvenWhenWarmUpPackagesAlreadyExistForThePath(){
        when(packages.existsByLearningPathId(5L)).thenReturn(true);
        when(packages.existsByLearningPathIdAndPhase(5L,"GOAL_LEARNING")).thenReturn(false);
        when(packages.findByLearningPathIdOrderByOrderIndex(5L)).thenReturn(
                List.of(LearningPathPackage.builder().orderIndex(1).phase("WARM_UP").build()));
        when(catalog.inspect(10L,"N4")).thenReturn(snapshot(10L,List.of()));

        var result=composer.compose(path(),List.of(seed(10L,1)));

        assertTrue(result.published()); assertEquals(1,result.packageCount());
        var captor=ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages).save(captor.capture());
        // order_index continues after the existing warm-up package (1) instead of colliding with it.
        assertEquals(2,captor.getValue().getOrderIndex());
        // Not AVAILABLE: a warm-up package precedes it, so it must start LOCKED (spec §5.6).
        assertEquals(LearningPackageStatus.LOCKED,captor.getValue().getStatus());
    }

    private UserLearningPath path(){return UserLearningPath.builder().id(5L).user(User.builder().id(7L).build())
            .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).minutesPerDay(30).title("P").build();}
    private ScheduledLearningResource seed(Long lessonId,int order){return seed(lessonId,order,"N4");}
    private ScheduledLearningResource seed(Long lessonId,int order,String level){var resource=new LearningResourceCandidate(lessonId,"Bài",1L,1L,Set.of("K"),level,30,1,order,null,null,"GRAMMAR");
        return new ScheduledLearningResource(resource,LearningPhase.GOAL_LEARNING,"TARGET","",Set.of("K"),order,order,30);}
    private InteractiveContentSnapshot snapshot(Long lessonId,List<CoverageGap> gaps){return new InteractiveContentSnapshot(Lesson.builder().id(lessonId).build(),"revision",List.of(1L,2L,3L,4L,5L),List.of(3L),List.of(4L),List.of(5L,6L),gaps);}
}
