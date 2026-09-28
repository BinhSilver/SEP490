package com.jlas.learning;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.controller.InteractiveKanjiController;
import com.jlas.learning.dto.KanjiStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.service.InteractiveKanjiService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InteractiveKanjiControllerTest {
    private final UserPrincipal user = new UserPrincipal(7L, "learner", "", List.of());

    @Test void studyUsesPrincipalIdentityAndEnvelope() {
        var service = mock(InteractiveKanjiService.class);
        var state = new KanjiStudyState(1L, LearningActivityType.KANJI,
                LearningActivityState.STUDYING, "rev", 1, 0, 0, 1, false, List.of());
        when(service.studyState(7L, 1L)).thenReturn(state);
        var controller = new InteractiveKanjiController(service);

        var body = controller.study(1L, user).getBody();

        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertSame(state, body.getData());
        verify(service).studyState(7L, 1L);
    }

    @Test void flipAndRememberDelegateWithPrincipal() {
        var service = mock(InteractiveKanjiService.class);
        var state = new KanjiStudyState(1L, LearningActivityType.KANJI,
                LearningActivityState.STUDYING, "rev", 1, 0, 0, 1, false, List.of());
        when(service.flip(7L, 1L, 201L)).thenReturn(state);
        when(service.remember(7L, 1L, 201L, true)).thenReturn(state);
        var controller = new InteractiveKanjiController(service);

        controller.flip(1L, 201L, user);
        controller.remember(1L, 201L, true, user);

        verify(service).flip(7L, 1L, 201L);
        verify(service).remember(7L, 1L, 201L, true);
    }

    @Test void issueReturnsServerSnapshotWithoutAnswerKey() {
        var service = mock(InteractiveKanjiService.class);
        var question = new MicroCheckResponse.MicroCheckQuestion(1L, "KANJI_TO_READING", "prompt", List.of("a", "b"));
        var issued = new MicroCheckResponse(100L, 1L, 0, 1, 10, List.of(question));
        when(service.issueMicroCheck(7L, 1L)).thenReturn(issued);
        var controller = new InteractiveKanjiController(service);

        var body = controller.issue(1L, user).getBody();

        assertNotNull(body);
        assertSame(issued, body.getData());
        // MicroCheckQuestion has no correct-option field at all — the answer key cannot leak through this DTO.
        assertEquals(4, MicroCheckResponse.MicroCheckQuestion.class.getRecordComponents().length);
        verify(service).issueMicroCheck(7L, 1L);
    }

    @Test void submitDelegatesRequestAndPrincipal() {
        var service = mock(InteractiveKanjiService.class);
        var result = new MicroCheckResult(10, 7, 70, true, 70, 1, 2, null, false,
                LearningActivityState.PASSED, List.of());
        var request = new MicroCheckSubmitRequest(100L,
                List.of(new MicroCheckSubmitRequest.MicroCheckAnswer(1L, 1)));
        when(service.submitMicroCheck(7L, 1L, request)).thenReturn(result);
        var controller = new InteractiveKanjiController(service);

        var body = controller.submit(1L, request, user).getBody();

        assertNotNull(body);
        assertSame(result, body.getData());
        verify(service).submitMicroCheck(7L, 1L, request);
    }

    @Test void stateAndReviewDelegateWithPrincipal() {
        var service = mock(InteractiveKanjiService.class);
        var state = new MicroCheckStateResponse(1L, LearningActivityState.CHECK_READY, 1, 0, 0, 3,
                null, false, true, false, null, null, null);
        when(service.microCheckState(7L, 1L)).thenReturn(state);
        when(service.completeReview(7L, 1L)).thenReturn(state);
        var controller = new InteractiveKanjiController(service);

        controller.state(1L, user);
        controller.completeReview(1L, user);

        verify(service).microCheckState(7L, 1L);
        verify(service).completeReview(7L, 1L);
    }
}
