package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathWritingView;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.JpathWritingService;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Writing practice (stroke order + tracing) of one item or of today's new items of a tab. */
@RestController
@RequestMapping("/api/learning/jlpt/writing")
@RequiredArgsConstructor
public class JpathWritingController {

    private final JpathWritingService writingService;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<JpathWritingView>>> today(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(defaultValue = "VOCAB") String tab) {
        return ResponseEntity.ok(ApiResponse.ok("OK", writingService.today(user.userId(), tab(tab))));
    }

    @GetMapping("/{type}/{code}")
    public ResponseEntity<ApiResponse<JpathWritingView>> view(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String type, @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok("OK", writingService.view(user.userId(), itemType(type), code)));
    }

    private static JpathItemType itemType(String type) {
        try {
            return JpathItemType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND);
        }
    }

    private static JpathTab tab(String tab) {
        try {
            return JpathTab.valueOf(tab.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tab không hợp lệ.");
        }
    }
}
