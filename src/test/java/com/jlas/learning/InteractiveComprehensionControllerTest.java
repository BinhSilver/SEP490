package com.jlas.learning;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.learning.controller.InteractiveComprehensionController;
import com.jlas.learning.dto.ComprehensionExerciseSource;
import com.jlas.learning.dto.ComprehensionStudyQuestion;
import com.jlas.learning.dto.ComprehensionStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.service.InteractiveComprehensionService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** [JLAS-W0193] RC Task 5 — same principal-delegation/envelope shape as {@code InteractiveGrammarControllerTest}
 * and {@code InteractiveDialogueControllerTest}. One type-safe route serves both Reading and Listening because
 * {@link InteractiveComprehensionService} resolves the real activity type from the owned activity row itself. */
class InteractiveComprehensionControllerTest {
    private final UserPrincipal user = new UserPrincipal(7L, "learner", "", List.of());

    @Test void studyUsesPrincipalIdentityAndEnvelope() {
        var service = mock(InteractiveComprehensionService.class);
        var state = new ComprehensionStudyState(1L, LearningActivityType.READING, LearningActivityState.STUDYING,
                "rev", 2, 0, new ComprehensionExerciseSource("Đoạn văn mẫu.", null),
                List.of(new ComprehensionStudyQuestion(10L, "Ý chính là gì?", List.of("A", "B"))),
                new MicroCheckStateResponse(1L, LearningActivityState.STUDYING, 2, 0, 0, 3, null, false, true,
                        false, null, null, null));
        when(service.studyState(7L, 1L)).thenReturn(state);
        var controller = new InteractiveComprehensionController(service, new InteractiveRateLimitService());

        var body = controller.study(1L, user).getBody();

        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertSame(state, body.getData());
        verify(service).studyState(7L, 1L);
    }

    @Test void studyNeverExposesTheCorrectOptionForAnyQuestion() {
        // ComprehensionStudyQuestion carries only id/prompt/options - no answer field can exist to leak.
        assertEquals(3, ComprehensionStudyQuestion.class.getRecordComponents().length);
    }

    @Test void issueReturnsServerSnapshotWithoutAnswerKey() {
        var service = mock(InteractiveComprehensionService.class);
        var question = new MicroCheckResponse.MicroCheckQuestion(10L, "READING_MICRO_CHECK", "Ý chính là gì?", List.of("A", "B"));
        var issued = new MicroCheckResponse(900L, 1L, 0, 2, 2, List.of(question));
        when(service.issueMicroCheck(7L, 1L)).thenReturn(issued);
        var controller = new InteractiveComprehensionController(service, new InteractiveRateLimitService());

        var body = controller.issue(1L, user).getBody();

        assertNotNull(body);
        assertSame(issued, body.getData());
        assertEquals(4, MicroCheckResponse.MicroCheckQuestion.class.getRecordComponents().length);
        verify(service).issueMicroCheck(7L, 1L);
    }

    @Test void submitDelegatesRequestAndPrincipal() {
        var service = mock(InteractiveComprehensionService.class);
        var result = new MicroCheckResult(2, 2, 100, true, 70, 1, 2, null, false,
                LearningActivityState.CHECK_READY, List.of());
        var request = new MicroCheckSubmitRequest(900L, List.of(new MicroCheckSubmitRequest.MicroCheckAnswer(10L, 1)));
        when(service.submitMicroCheck(7L, 1L, request)).thenReturn(result);
        var controller = new InteractiveComprehensionController(service, new InteractiveRateLimitService());

        var body = controller.submit(1L, request, user).getBody();

        assertNotNull(body);
        assertSame(result, body.getData());
        verify(service).submitMicroCheck(7L, 1L, request);
    }

    @Test void stateDelegatesWithPrincipal() {
        var service = mock(InteractiveComprehensionService.class);
        var state = new MicroCheckStateResponse(1L, LearningActivityState.CHECK_READY, 2, 0, 0, 3,
                null, false, true, false, null, null, null);
        when(service.microCheckState(7L, 1L)).thenReturn(state);
        var controller = new InteractiveComprehensionController(service, new InteractiveRateLimitService());

        var body = controller.state(1L, user).getBody();

        assertSame(state, body.getData());
        verify(service).microCheckState(7L, 1L);
    }

    @Test void reviewEvidenceTakesNoClientBodyAndDelegatesWithPrincipalOnly() {
        var service = mock(InteractiveComprehensionService.class);
        var state = new MicroCheckStateResponse(1L, LearningActivityState.COOLDOWN, 2, 0, 3, 0,
                null, true, false, false, 40, 40, 40);
        when(service.recordReviewEvidence(7L, 1L)).thenReturn(state);
        var controller = new InteractiveComprehensionController(service, new InteractiveRateLimitService());

        var body = controller.reviewEvidence(1L, user).getBody();

        assertSame(state, body.getData());
        verify(service).recordReviewEvidence(7L, 1L);
    }

    @Test void completeReviewDelegatesWithPrincipal() {
        var service = mock(InteractiveComprehensionService.class);
        var state = new MicroCheckStateResponse(1L, LearningActivityState.CHECK_READY, 2, 0, 0, 3,
                null, false, true, false, null, null, null);
        when(service.completeReview(7L, 1L)).thenReturn(state);
        var controller = new InteractiveComprehensionController(service, new InteractiveRateLimitService());

        var body = controller.completeReview(1L, user).getBody();

        assertSame(state, body.getData());
        verify(service).completeReview(7L, 1L);
    }

    @Test void issueChecksRateLimitBeforeDelegatingAndRejectsWhenExceeded() {
        var service = mock(InteractiveComprehensionService.class);
        var rateLimit = mock(InteractiveRateLimitService.class);
        doThrow(new ApiException(com.jlas.common.exception.ErrorCode.TOO_MANY_REQUESTS))
                .when(rateLimit).check(7L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        var controller = new InteractiveComprehensionController(service, rateLimit);

        assertThrows(ApiException.class, () -> controller.issue(1L, user));
        verify(service, never()).issueMicroCheck(anyLong(), anyLong());
    }

    @Test void submitChecksRateLimitBeforeDelegating() {
        var service = mock(InteractiveComprehensionService.class);
        var rateLimit = mock(InteractiveRateLimitService.class);
        var request = new MicroCheckSubmitRequest(900L, List.of(new MicroCheckSubmitRequest.MicroCheckAnswer(10L, 1)));
        var controller = new InteractiveComprehensionController(service, rateLimit);

        controller.submit(1L, request, user);

        verify(rateLimit).check(7L, InteractiveRateLimitService.Bucket.MICRO_CHECK_SUBMIT);
    }

    @Test void reviewEvidenceAndCompleteReviewAreNotRateLimited() {
        // Matches InteractiveDialogueController's reached-bottom precedent: only issue/submit are rate limited.
        var service = mock(InteractiveComprehensionService.class);
        var rateLimit = mock(InteractiveRateLimitService.class);
        var state = new MicroCheckStateResponse(1L, LearningActivityState.CHECK_READY, 2, 0, 0, 3,
                null, false, true, false, null, null, null);
        when(service.recordReviewEvidence(7L, 1L)).thenReturn(state);
        when(service.completeReview(7L, 1L)).thenReturn(state);
        var controller = new InteractiveComprehensionController(service, rateLimit);

        controller.reviewEvidence(1L, user);
        controller.completeReview(1L, user);

        verifyNoInteractions(rateLimit);
    }
}
