package com.jlas.learning;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.controller.InteractiveVocabularyController;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.dto.VocabularyStudyState;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.common.exception.ApiException;
import com.jlas.learning.service.InteractiveVocabularyService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InteractiveVocabularyControllerTest {
    private final UserPrincipal user = new UserPrincipal(7L, "learner", "", List.of());

    @Test void studyUsesPrincipalIdentityAndEnvelope() {
        var service = mock(InteractiveVocabularyService.class);
        var state = new VocabularyStudyState(1L, LearningActivityType.VOCABULARY,
                LearningActivityState.STUDYING, "rev", 1, 0, 0, 1, false, List.of());
        when(service.studyState(7L, 1L)).thenReturn(state);
        var controller = new InteractiveVocabularyController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        var body = controller.study(1L, user).getBody();

        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertSame(state, body.getData());
        verify(service).studyState(7L, 1L);
    }

    @Test void flipAndRememberDelegateWithPrincipal() {
        var service = mock(InteractiveVocabularyService.class);
        var state = new VocabularyStudyState(1L, LearningActivityType.VOCABULARY,
                LearningActivityState.STUDYING, "rev", 1, 0, 0, 1, false, List.of());
        when(service.flip(7L, 1L, 101L)).thenReturn(state);
        when(service.remember(7L, 1L, 101L, true)).thenReturn(state);
        var controller = new InteractiveVocabularyController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        controller.flip(1L, 101L, user);
        controller.remember(1L, 101L, true, user);

        verify(service).flip(7L, 1L, 101L);
        verify(service).remember(7L, 1L, 101L, true);
    }

    @Test void issueReturnsServerSnapshot() {
        var service = mock(InteractiveVocabularyService.class);
        var issued = new MicroCheckResponse(100L, 1L, 0, 1, 20, List.of());
        when(service.issueMicroCheck(7L, 1L)).thenReturn(issued);
        var controller = new InteractiveVocabularyController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        var body = controller.issue(1L, user).getBody();

        assertNotNull(body);
        assertSame(issued, body.getData());
        verify(service).issueMicroCheck(7L, 1L);
    }

    @Test void submitDelegatesRequestAndPrincipal() {
        var service = mock(InteractiveVocabularyService.class);
        var result = new MicroCheckResult(10, 7, 70, true, 70, 1, 2, null, false,
                LearningActivityState.PASSED, List.of());
        var request = new MicroCheckSubmitRequest(100L,
                List.of(new MicroCheckSubmitRequest.MicroCheckAnswer(1L, 1)));
        when(service.submitMicroCheck(7L, 1L, request)).thenReturn(result);
        var controller = new InteractiveVocabularyController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        var body = controller.submit(1L, request, user).getBody();

        assertNotNull(body);
        assertSame(result, body.getData());
        verify(service).submitMicroCheck(7L, 1L, request);
    }

    @Test void stateAndReviewDelegateWithPrincipal() {
        var service = mock(InteractiveVocabularyService.class);
        var state = new MicroCheckStateResponse(1L, LearningActivityState.CHECK_READY, 1, 0, 0, 3,
                null, false, true, false, null, null, null);
        when(service.microCheckState(7L, 1L)).thenReturn(state);
        when(service.completeReview(7L, 1L)).thenReturn(state);
        var controller = new InteractiveVocabularyController(service, new com.jlas.learning.service.impl.InteractiveRateLimitService());

        controller.state(1L, user);
        controller.completeReview(1L, user);

        verify(service).microCheckState(7L, 1L);
        verify(service).completeReview(7L, 1L);
    }

    @Test void issueChecksRateLimitBeforeDelegatingAndRejectsWhenExceeded() {
        var service = mock(InteractiveVocabularyService.class);
        var rateLimit = mock(InteractiveRateLimitService.class);
        doThrow(new ApiException(com.jlas.common.exception.ErrorCode.TOO_MANY_REQUESTS))
                .when(rateLimit).check(7L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        var controller = new InteractiveVocabularyController(service, rateLimit);

        assertThrows(ApiException.class, () -> controller.issue(1L, user));
        verify(service, never()).issueMicroCheck(anyLong(), anyLong());
    }

    @Test void submitChecksRateLimitBeforeDelegating() {
        var service = mock(InteractiveVocabularyService.class);
        var rateLimit = mock(InteractiveRateLimitService.class);
        var request = new MicroCheckSubmitRequest(100L,
                List.of(new MicroCheckSubmitRequest.MicroCheckAnswer(1L, 1)));
        var controller = new InteractiveVocabularyController(service, rateLimit);

        controller.submit(1L, request, user);

        verify(rateLimit).check(7L, InteractiveRateLimitService.Bucket.MICRO_CHECK_SUBMIT);
    }
}
