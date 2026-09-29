package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.controller.InteractiveLearningCertificateController;
import com.jlas.learning.dto.InteractiveComprehensionEvidence;
import com.jlas.learning.dto.InteractiveDialogueSummary;
import com.jlas.learning.dto.InteractivePathSummaryResponse;
import com.jlas.learning.dto.InteractiveReadinessConfidence;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.InteractivePathSummaryService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** [JLAS-W0193] Task 10 — {@link InteractiveLearningCertificateController}: owner-scoped, eligibility-gated,
 * and (because nothing here writes) trivially idempotent by construction — every assertion below is really
 * checking "does this refuse/render the same way no matter how many times or which user calls it". */
class InteractiveLearningCertificateControllerTest {
    private final UserLearningPathRepository paths = mock(UserLearningPathRepository.class);
    private final InteractivePathSummaryService summaryService = mock(InteractivePathSummaryService.class);
    private final InteractiveRateLimitService rateLimit = new InteractiveRateLimitService();
    private final InteractiveLearningCertificateController controller =
            new InteractiveLearningCertificateController(paths, summaryService, rateLimit);

    private UserPrincipal principalFor(Long userId) {
        return new UserPrincipal(userId, "u@b.com", "hash", List.of());
    }

    private InteractivePathSummaryResponse summaryFor(Long pathId, boolean eligible, LocalDateTime completedAt) {
        return new InteractivePathSummaryResponse(pathId, eligible ? "COMPLETED" : "ACTIVE", completedAt,
                List.of(), new InteractiveDialogueSummary(0, 0, true),
                List.of(new InteractiveComprehensionEvidence("READING", false, null, null, null, false, "note")),
                new InteractiveReadinessConfidence("LOW", "explanation"), eligible);
    }

    @Test void rendersForTheOwnerOfAnEligiblePath() {
        var owner = User.builder().id(1L).build();
        var completedAt = LocalDateTime.now();
        var path = UserLearningPath.builder().id(5L).user(owner).title("N5 → N4").targetLevel("N4").build();
        when(summaryService.summary(1L)).thenReturn(summaryFor(5L, true, completedAt));
        when(paths.findById(5L)).thenReturn(Optional.of(path));
        Model model = new ExtendedModelMap();

        String view = controller.certificate(principalFor(1L), model);

        assertEquals("learning/interactive-certificate", view);
        assertEquals("N4", model.getAttribute("targetLevel"));
        assertEquals(completedAt, model.getAttribute("completedAt"));
    }

    @Test void refusesWhenTheSummaryReportsThePathIsNotCertificateEligible() {
        when(summaryService.summary(1L)).thenReturn(summaryFor(5L, false, null));
        Model model = new ExtendedModelMap();

        var ex = assertThrows(ApiException.class, () -> controller.certificate(principalFor(1L), model));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verifyNoInteractions(paths);
    }

    @Test void refusesWhenTheResolvedPathBelongsToAnotherUser() {
        var otherOwner = User.builder().id(2L).build();
        var path = UserLearningPath.builder().id(5L).user(otherOwner).targetLevel("N4").build();
        when(summaryService.summary(1L)).thenReturn(summaryFor(5L, true, LocalDateTime.now()));
        when(paths.findById(5L)).thenReturn(Optional.of(path));
        Model model = new ExtendedModelMap();

        var ex = assertThrows(ApiException.class, () -> controller.certificate(principalFor(1L), model));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test void repeatedRequestsNeverMutateAnythingAndAlwaysReturnTheSameView() {
        var owner = User.builder().id(1L).build();
        var completedAt = LocalDateTime.now();
        var path = UserLearningPath.builder().id(5L).user(owner).targetLevel("N4").build();
        when(summaryService.summary(1L)).thenReturn(summaryFor(5L, true, completedAt));
        when(paths.findById(5L)).thenReturn(Optional.of(path));

        String first = controller.certificate(principalFor(1L), new ExtendedModelMap());
        String second = controller.certificate(principalFor(1L), new ExtendedModelMap());

        assertEquals(first, second);
        assertEquals("learning/interactive-certificate", first);
    }

    @Test void checksRateLimitBeforeDoingAnyWorkAndRejectsWhenExceeded() {
        var limitedRateLimit = mock(InteractiveRateLimitService.class);
        doThrow(new ApiException(ErrorCode.TOO_MANY_REQUESTS))
                .when(limitedRateLimit).check(1L, InteractiveRateLimitService.Bucket.CERTIFICATE_VIEW);
        var limitedController = new InteractiveLearningCertificateController(paths, summaryService, limitedRateLimit);

        assertThrows(ApiException.class, () -> limitedController.certificate(principalFor(1L), new ExtendedModelMap()));
        verifyNoInteractions(summaryService);
        verifyNoInteractions(paths);
    }
}
