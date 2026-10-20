package com.jlas.learning;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.controller.InteractiveGrammarController;
import com.jlas.learning.dto.GrammarFillSubmitRequest;
import com.jlas.learning.dto.GrammarOrderingSubmitRequest;
import com.jlas.learning.dto.GrammarPracticeResult;
import com.jlas.learning.dto.GrammarStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.common.exception.ApiException;
import com.jlas.learning.service.InteractiveGrammarService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** [JLAS-W0193] Task 6 — same envelope/principal-delegation shape as {@code InteractiveKanjiControllerTest}. */
class InteractiveGrammarControllerTest {
    private final UserPrincipal user = new UserPrincipal(7L, "learner", "", List.of());

    @Test void studyUsesPrincipalIdentityAndEnvelope() {
        var service = mock(InteractiveGrammarService.class);
        var state = new GrammarStudyState(1L, LearningActivityType.GRAMMAR,
                LearningActivityState.STUDYING, "rev", 1, 0, false, List.of());
        when(service.studyState(7L, 1L)).thenReturn(state);
        var controller = new InteractiveGrammarController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        var body = controller.study(1L, user).getBody();

        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertSame(state, body.getData());
        verify(service).studyState(7L, 1L);
    }

    @Test void reviewContentIsReadOnlyRouteUsingAuthenticatedOwner() {
        var service = mock(InteractiveGrammarService.class);
        var state = new GrammarStudyState(1L, LearningActivityType.GRAMMAR,
                LearningActivityState.PASSED, "rev", 1, 1, true, List.of());
        when(service.reviewState(7L, 1L)).thenReturn(state);
        var controller = new InteractiveGrammarController(service, new InteractiveRateLimitService());

        var body = controller.review(1L, user).getBody();

        assertSame(state, body.getData());
        verify(service).reviewState(7L, 1L);
    }

    @Test void practiceSubmitsDelegateWithPrincipal() {
        var service = mock(InteractiveGrammarService.class);
        var orderingRequest = new GrammarOrderingSubmitRequest(List.of(0, 1));
        var fillRequest = new GrammarFillSubmitRequest("です");
        var orderingResult = new GrammarPracticeResult(501L, "ORDERING", true, false, "a b", "ok");
        var fillResult = new GrammarPracticeResult(501L, "FILL_BLANK", true, true, "です", "ok");
        when(service.submitOrdering(7L, 1L, 501L, orderingRequest)).thenReturn(orderingResult);
        when(service.submitFill(7L, 1L, 501L, fillRequest)).thenReturn(fillResult);
        var controller = new InteractiveGrammarController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        var orderingBody = controller.submitOrdering(1L, 501L, orderingRequest, user).getBody();
        var fillBody = controller.submitFill(1L, 501L, fillRequest, user).getBody();

        assertSame(orderingResult, orderingBody.getData());
        assertSame(fillResult, fillBody.getData());
        verify(service).submitOrdering(7L, 1L, 501L, orderingRequest);
        verify(service).submitFill(7L, 1L, 501L, fillRequest);
    }

    @Test void issueReturnsServerSnapshotWithoutAnswerKey() {
        var service = mock(InteractiveGrammarService.class);
        var question = new MicroCheckResponse.MicroCheckQuestion(1L, "MEANING_MATCH", "prompt", List.of("a", "b"));
        var issued = new MicroCheckResponse(100L, 1L, 0, 1, 2, List.of(question));
        when(service.issueMicroCheck(7L, 1L)).thenReturn(issued);
        var controller = new InteractiveGrammarController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        var body = controller.issue(1L, user).getBody();

        assertNotNull(body);
        assertSame(issued, body.getData());
        assertEquals(4, MicroCheckResponse.MicroCheckQuestion.class.getRecordComponents().length);
        verify(service).issueMicroCheck(7L, 1L);
    }

    @Test void submitDelegatesRequestAndPrincipal() {
        var service = mock(InteractiveGrammarService.class);
        var result = new MicroCheckResult(4, 3, 75, true, 70, 1, 2, null, false,
                LearningActivityState.PASSED, List.of());
        var request = new MicroCheckSubmitRequest(100L,
                List.of(new MicroCheckSubmitRequest.MicroCheckAnswer(1L, 1)));
        when(service.submitMicroCheck(7L, 1L, request)).thenReturn(result);
        var controller = new InteractiveGrammarController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        var body = controller.submit(1L, request, user).getBody();

        assertNotNull(body);
        assertSame(result, body.getData());
        verify(service).submitMicroCheck(7L, 1L, request);
    }

    @Test void stateAndReviewDelegateWithPrincipal() {
        var service = mock(InteractiveGrammarService.class);
        var state = new MicroCheckStateResponse(1L, LearningActivityState.CHECK_READY, 1, 0, 0, 3,
                null, false, true, false, null, null, null);
        when(service.microCheckState(7L, 1L)).thenReturn(state);
        when(service.completeReview(7L, 1L)).thenReturn(state);
        var controller = new InteractiveGrammarController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        controller.state(1L, user);
        controller.completeReview(1L, user);

        verify(service).microCheckState(7L, 1L);
        verify(service).completeReview(7L, 1L);
    }

    @Test void issueChecksRateLimitBeforeDelegatingAndRejectsWhenExceeded() {
        var service = mock(InteractiveGrammarService.class);
        var rateLimit = mock(InteractiveRateLimitService.class);
        doThrow(new ApiException(com.jlas.common.exception.ErrorCode.TOO_MANY_REQUESTS))
                .when(rateLimit).check(7L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        var controller = new InteractiveGrammarController(service, rateLimit);

        assertThrows(ApiException.class, () -> controller.issue(1L, user));
        verify(service, never()).issueMicroCheck(anyLong(), anyLong());
    }

    @Test void submitChecksRateLimitBeforeDelegating() {
        var service = mock(InteractiveGrammarService.class);
        var rateLimit = mock(InteractiveRateLimitService.class);
        var request = new MicroCheckSubmitRequest(100L,
                List.of(new MicroCheckSubmitRequest.MicroCheckAnswer(1L, 1)));
        var controller = new InteractiveGrammarController(service, rateLimit);

        controller.submit(1L, request, user);

        verify(rateLimit).check(7L, InteractiveRateLimitService.Bucket.MICRO_CHECK_SUBMIT);
    }
}
