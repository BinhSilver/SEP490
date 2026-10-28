package com.jlas.assessment.controller;

import com.jlas.assessment.service.JlptMockAttemptService;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Read-only review and internal achievement certificate pages for a learner's saved JLPT mock attempt. */
@Controller
@RequiredArgsConstructor
public class JlptMockResultPageController {
    private final JlptMockAttemptService attempts;
    private final UserRepository users;

    @GetMapping("/jlpt/result")
    public String resultPage(Model model) {
        model.addAttribute("layoutMode", "app");
        return "jlpt-result";
    }

    @GetMapping("/jlpt/certificate")
    public String certificate(@AuthenticationPrincipal UserPrincipal principal,
            @RequestParam long attemptId, Model model) {
        var review = attempts.review(principal.userId(), attemptId);
        if (!review.result().passed()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Chứng nhận kết quả chỉ có khi đạt bài thi thử.");
        }
        var user = users.findById(principal.userId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        String learnerName = user.getFullName();
        if (learnerName == null || learnerName.isBlank()) {
            learnerName = user.getUsername() == null || user.getUsername().isBlank() ? user.getEmail() : user.getUsername();
        }
        model.addAttribute("layoutMode", "app");
        model.addAttribute("learnerName", learnerName);
        model.addAttribute("result", review.result());
        return "jlpt-mock-certificate";
    }
}
