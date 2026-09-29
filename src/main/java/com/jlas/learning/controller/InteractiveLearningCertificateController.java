package com.jlas.learning.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.InteractivePathSummaryService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** [JLAS-W0193] Task 10 — "Chứng nhận hoàn thành lộ trình J-LAS" for an INTERACTIVE_V2 path (design spec
 * §6.3). Follows the exact V1 precedent ({@link LearningCertificatePageController}: no "issued certificate"
 * table — the page is derived directly, read-only, from the path/package completion state that
 * {@code InteractiveLearningPathServiceImpl} already owns and guards exactly-once. Because nothing here ever
 * writes anything, issuance is trivially idempotent: re-requesting this page any number of times can never
 * "issue twice," since there is no issuance write path to race or duplicate — only a read that either shows
 * the same certificate or refuses. Ownership is principal-derived only, reusing
 * {@link InteractivePathSummaryService#summary} (already owner-scoped and unit-tested) rather than
 * re-deriving the eligibility gate a second time. */
@Controller
@RequiredArgsConstructor
public class InteractiveLearningCertificateController {
    private final UserLearningPathRepository paths;
    private final InteractivePathSummaryService summaryService;
    private final InteractiveRateLimitService rateLimit;

    @GetMapping("/learning/v2/certificate")
    public String certificate(@AuthenticationPrincipal UserPrincipal user, Model model) {
        rateLimit.check(user.userId(), InteractiveRateLimitService.Bucket.CERTIFICATE_VIEW);
        var summary = summaryService.summary(user.userId());
        if (!summary.certificateEligible()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Chứng nhận chưa được cấp cho lộ trình này.");
        }
        var path = paths.findById(summary.pathId())
                .filter(p -> p.getUser().getId().equals(user.userId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        model.addAttribute("layoutMode", "app");
        model.addAttribute("pathTitle", path.getTitle());
        model.addAttribute("targetLevel", path.getTargetLevel());
        model.addAttribute("completedAt", summary.completedAt());
        return "learning/interactive-certificate";
    }
}
