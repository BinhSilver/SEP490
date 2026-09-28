package com.jlas.learning.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.repository.UserLearningPathRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * [JLAS-W0108] Trang chứng chỉ thành tựu nội bộ — chỉ chủ sở hữu path đã COMPLETED mới xem được;
 * không có bảng "chứng chỉ đã cấp" riêng, dựng trực tiếp từ dữ liệu path đã hoàn thành.
 */
@Controller
@RequiredArgsConstructor
public class LearningCertificatePageController {
    private final UserLearningPathRepository paths;

    @GetMapping("/learning/certificate/{pathId}")
    public String certificate(@PathVariable Long pathId, @AuthenticationPrincipal UserPrincipal user,
            Model model) {
        var path = paths.findById(pathId)
                .filter(p -> p.getUser().getId().equals(user.userId()))
                .filter(p -> "COMPLETED".equals(p.getStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Certificate not found."));
        model.addAttribute("targetLevel", path.getTargetLevel());
        model.addAttribute("completedAt", path.getCompletedAt());
        return "learning/certificate";
    }
}
