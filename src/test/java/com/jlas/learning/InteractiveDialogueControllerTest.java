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

    @Test void reviewAndPracticeRoutesReturnReadOnlyScriptForAuthenticatedOwner() {
        var service = mock(InteractiveDialogueService.class);
        var state = new DialogueStudyState(1L, LearningActivityType.DIALOGUE, LearningActivityState.COMPLETED,
                "rev", 180, 180, 0, true, true, List.of());
        when(service.reviewState(7L, 1L)).thenReturn(state);
        when(service.practiceState(7L, 1L)).thenReturn(state);
        var controller = new InteractiveDialogueController(service, new InteractiveRateLimitService());

        assertSame(state, controller.review(1L, user).getBody().getData());
        assertSame(state, controller.practice(1L, user).getBody().getData());
        verify(service).reviewState(7L, 1L);
        verify(service).practiceState(7L, 1L);
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
        // DialogueProgressResult has exactly these 12 server-computed components (then: the communication
        // line-practice counts and the "Từ & cụm từ" card counts) — no client-settable field.
        assertEquals(java.util.List.of("activityId", "activityState", "requiredSeconds", "accumulatedSeconds",
                "remainingSeconds", "reachedBottom", "completed", "lineByLinePractice", "practicedLines", "totalLines",
                "flippedPhrases", "totalPhrases"), java.util.Arrays.stream(DialogueProgressResult.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    @Test void challengeWritesAreRateLimitedAndPassOnlyTheChoicesToTheService() {
        var service = mock(InteractiveDialogueService.class);
        var rateLimit = mock(InteractiveRateLimitService.class);
        var controller = new InteractiveDialogueController(service, rateLimit);

        controller.challenge(1L, user);
        controller.answerChallenge(1L, new com.jlas.learning.dto.DialogueChallenge.AnswerRequest(0, "b"), user);
        controller.completeChallenge(1L, new com.jlas.learning.dto.DialogueChallenge.CompleteRequest(java.util.List.of("b", "a")), user);

        verify(service).challenge(7L, 1L);
        verify(service).answerChallenge(7L, 1L, 0, "b");
        verify(service).completeChallenge(7L, 1L, java.util.List.of("b", "a"));
        verify(rateLimit, times(2)).check(7L, InteractiveRateLimitService.Bucket.DIALOGUE_PRACTICE);
    }

    @Test void flippingAPhraseChecksTheRateLimitAndDelegatesOnlyTheIndex() {
        var service = mock(InteractiveDialogueService.class);
        var rateLimit = mock(InteractiveRateLimitService.class);
        var controller = new InteractiveDialogueController(service, rateLimit);

        controller.flipPhrase(1L, 3, user);
        controller.practiceLine(1L, 201L, user);

        verify(rateLimit, times(2)).check(7L, InteractiveRateLimitService.Bucket.DIALOGUE_PRACTICE);
        verify(rateLimit, never()).check(7L, InteractiveRateLimitService.Bucket.DIALOGUE_HEARTBEAT);
        verify(service).flipPhrase(7L, 1L, 3);
        verify(service).practiceLine(7L, 1L, 201L);
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
