package com.jlas.learning;

import com.jlas.learning.dto.InteractiveLearningActivityResponse;
import com.jlas.learning.dto.InteractiveLearningSessionResponse;
import com.jlas.learning.dto.ReferenceVocabularyResponse;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.service.impl.InteractiveSessionProjector;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveSessionProjectionTest {
    private final InteractiveSessionProjector projector = new InteractiveSessionProjector();

    @Test void groupsExistingActivitiesAndKeepsTheServerOrderedSessionGate(){
        var activities=List.of(
                activity(1,LearningActivityType.VOCABULARY,LearningActivityState.PASSED,1),
                activity(2,LearningActivityType.KANJI,LearningActivityState.STUDYING,2),
                activity(3,LearningActivityType.GRAMMAR,LearningActivityState.LOCKED,3),
                activity(4,LearningActivityType.DIALOGUE,LearningActivityState.LOCKED,4));
        var counts=counts(5,3,2,1);

        var sessions=projector.project(LearningPackageStatus.IN_PROGRESS,null,activities,counts,List.of(),30);

        assertEquals(List.of("VOCABULARY","KANJI_GRAMMAR","DIALOGUE"),
                sessions.stream().map(s->s.sessionType().name()).toList());
        assertEquals(List.of("COMPLETED","IN_PROGRESS","LOCKED"),
                sessions.stream().map(s->s.state().name()).toList());
        assertEquals(List.of("REVIEW","PRACTICE"),sessions.get(0).allowedActions());
        assertEquals(List.of("CONTINUE"),sessions.get(1).allowedActions());
        assertTrue(sessions.get(2).allowedActions().isEmpty());
        assertEquals(2,sessions.get(1).activities().size());
        assertEquals(5,sessions.get(0).contentCount());
    }

    @Test void skipsAbsentVocabularyAndDialogueAndPutsShortReferenceListOnKanjiGrammar(){
        var references=List.of(new ReferenceVocabularyResponse(71L,"猫","ねこ","mèo","danh từ"),
                new ReferenceVocabularyResponse(72L,"見る","みる","nhìn","động từ"));
        var activities=List.of(activity(1,LearningActivityType.KANJI,LearningActivityState.LOCKED,1));

        var sessions=projector.project(LearningPackageStatus.AVAILABLE,null,activities,counts(0,1,0,0),references,12);

        assertEquals(1,sessions.size());
        assertEquals("KANJI_GRAMMAR",sessions.getFirst().sessionType().name());
        assertEquals("AVAILABLE",sessions.getFirst().state().name());
        assertEquals(List.of("START"),sessions.getFirst().allowedActions());
        assertEquals(references,sessions.getFirst().referenceVocabulary());
        assertEquals(2,sessions.getFirst().contentCounts().get("vocabulary"));
        assertEquals(3,sessions.getFirst().contentCount());
    }

    @Test void completedPackageExposesReviewAndPracticeForEveryPresentSession(){
        var activities=List.of(
                activity(1,LearningActivityType.GRAMMAR,LearningActivityState.PASSED,1),
                activity(2,LearningActivityType.DIALOGUE,LearningActivityState.COMPLETED,2));

        var sessions=projector.project(LearningPackageStatus.COMPLETED,null,activities,counts(0,0,1,1),List.of(),10);

        assertEquals(List.of("KANJI_GRAMMAR","DIALOGUE"),sessions.stream().map(s->s.sessionType().name()).toList());
        assertTrue(sessions.stream().allMatch(s->s.allowedActions().equals(List.of("REVIEW","PRACTICE"))));
    }

    @Test void packageLockOverridesActivityStateAndDoesNotOfferClientActions(){
        var sessions=projector.project(LearningPackageStatus.LOCKED,"Hoàn thành package trước.",
                List.of(activity(1,LearningActivityType.VOCABULARY,LearningActivityState.STUDYING,1)),counts(5,0,0,0),List.of(),15);

        assertEquals("LOCKED",sessions.getFirst().state().name());
        assertTrue(sessions.getFirst().allowedActions().isEmpty());
        assertEquals("Hoàn thành package trước.",sessions.getFirst().blockedReason());
    }

    private InteractiveLearningActivityResponse activity(long id,LearningActivityType type,LearningActivityState state,int order){
        return new InteractiveLearningActivityResponse(id,type,state,order,null,null);
    }
    private Map<LearningMemberType,Integer> counts(int vocabulary,int kanji,int grammar,int dialogue){
        var counts=new EnumMap<LearningMemberType,Integer>(LearningMemberType.class);
        counts.put(LearningMemberType.VOCABULARY_ENTRY,vocabulary); counts.put(LearningMemberType.KANJI,kanji);
        counts.put(LearningMemberType.GRAMMAR_POINT,grammar); counts.put(LearningMemberType.DIALOGUE_LINE,dialogue);
        return counts;
    }
}
