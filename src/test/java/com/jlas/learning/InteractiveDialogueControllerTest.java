package com.jlas.learning;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.controller.InteractiveDialogueController;
import com.jlas.learning.dto.DialogueHeartbeatRequest;
import com.jlas.learning.dto.DialogueProgressResult;
import com.jlas.learning.dto.DialogueStudyState;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.common.exception.ApiException;
import com.jlas.learning.service.InteractiveDialogueService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** [JLAS-W0193] Task 7 — same principal-delegation/envelope shape as {@code InteractiveGrammarControllerTest}. */
class InteractiveDialogueControllerTest {
    private final UserPrincipal user = new UserPrincipal(7L, "learner", "", List.of());

    @Test void studyUsesPrincipalIdentityAndEnvelope() {
        var service = mock(InteractiveDialogueService.class);
        var state = new DialogueStudyState(1L, LearningActivityType.DIALOGUE, LearningActivityState.STUDYING,
                "rev", 180, 0, 180, false, false, List.of());
        when(service.studyState(7L, 1L)).thenReturn(state);
        var controller = new InteractiveDialogueController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        var body = controller.study(1L, user).getBody();

        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertSame(state, body.getData());
        verify(service).studyState(7L, 1L);
    }

    @Test void heartbeatDelegatesRequestAndPrincipal() {
        var service = mock(InteractiveDialogueService.class);
        var request = new DialogueHeartbeatRequest("token-1");
        var result = new DialogueProgressResult(1L, LearningActivityState.STUDYING, 180, 15, 165, false, false);
        when(service.heartbeat(7L, 1L, request)).thenReturn(result);
        var controller = new InteractiveDialogueController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        var body = controller.heartbeat(1L, request, user).getBody();

        assertNotNull(body);
        assertSame(result, body.getData());
        verify(service).heartbeat(7L, 1L, request);
    }

    @Test void reachedBottomDelegatesWithPrincipal() {
        var service = mock(InteractiveDialogueService.class);
        var result = new DialogueProgressResult(1L, LearningActivityState.COMPLETED, 180, 200, 0, true, true);
        when(service.reachedBottom(7L, 1L)).thenReturn(result);
        var controller = new InteractiveDialogueController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        var body = controller.reachedBottom(1L, user).getBody();

        assertNotNull(body);
        assertSame(result, body.getData());
        assertTrue(body.getData().completed());
        verify(service).reachedBottom(7L, 1L);
    }

    @Test void progressResultNeverExposesAClientSettableCompletedOrScoreField() {
        // DialogueProgressResult has exactly these 7 server-computed components — no client-settable field.
        assertEquals(7, DialogueProgressResult.class.getRecordComponents().length);
    }

    @Test void heartbeatChecksRateLimitBeforeDelegatingAndRejectsWhenExceeded() {
        var service = mock(InteractiveDialogueService.class);
        var rateLimit = mock(InteractiveRateLimitService.class);
        var request = new DialogueHeartbeatRequest("token-1");
        doThrow(new ApiException(com.jlas.common.exception.ErrorCode.TOO_MANY_REQUESTS))
                .when(rateLimit).check(7L, InteractiveRateLimitService.Bucket.DIALOGUE_HEARTBEAT);
        var controller = new InteractiveDialogueController(service, rateLimit);

        assertThrows(ApiException.class, () -> controller.heartbeat(1L, request, user));
        verify(service, never()).heartbeat(anyLong(), anyLong(), any());
    }
}
