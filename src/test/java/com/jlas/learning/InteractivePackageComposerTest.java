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

    @Test void missingComponentRecordsGapAndPublishesNothing(){
        when(catalog.inspect(10L,"N4")).thenReturn(snapshot(10L,List.of(new CoverageGap("LESSON:10","MISSING_DIALOGUE"))));
        var result=composer.compose(path(),List.of(seed(10L,1)));
        assertFalse(result.published()); assertEquals("MISSING_DIALOGUE",result.gaps().getFirst().reasonCode());
        verify(packages,never()).save(any()); verify(items,never()).save(any()); verifyNoInteractions(members,stages);
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
            .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).title("P").build();}
    private ScheduledLearningResource seed(Long lessonId,int order){var resource=new LearningResourceCandidate(lessonId,"Bài",1L,1L,Set.of("K"),"N4",30,1,order,null,null,"GRAMMAR");
        return new ScheduledLearningResource(resource,LearningPhase.GOAL_LEARNING,"TARGET","",Set.of("K"),order,order,30);}
    private InteractiveContentSnapshot snapshot(Long lessonId,List<CoverageGap> gaps){return new InteractiveContentSnapshot(Lesson.builder().id(lessonId).build(),"revision",List.of(1L,2L),List.of(3L),List.of(4L),List.of(5L,6L),gaps);}
}
