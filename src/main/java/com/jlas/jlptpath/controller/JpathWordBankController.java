package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathConfusablePairView;
import com.jlas.jlptpath.dto.JpathExtraLinksView;
import com.jlas.jlptpath.service.JpathConfusableService;
import com.jlas.jlptpath.service.JpathExtraLinksService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] Word bank side of the JLPT path: confusable pairs ({@code GET /confusables?tab=VOCAB|KANJI}) and the
 * "Luyện thêm" strip ({@code GET /extra-links}). The known store and the notebook are listed by
 * {@code JpathListController} ({@code /known}, {@code /notebook}).
 */
@RestController
@RequestMapping("/api/learning/jlpt")
@RequiredArgsConstructor
public class JpathWordBankController {

    private final JpathConfusableService confusables;
    private final JpathExtraLinksService extraLinks;

    @GetMapping("/confusables")
    public ResponseEntity<ApiResponse<List<JpathConfusablePairView>>> confusables(
            @AuthenticationPrincipal UserPrincipal user, @RequestParam(defaultValue = "VOCAB") String tab) {
        return ResponseEntity.ok(ApiResponse.ok("OK", confusables.pairs(user.userId(), tab)));
    }

    @GetMapping("/extra-links")
    public ResponseEntity<ApiResponse<JpathExtraLinksView>> extraLinks(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", extraLinks.links(user.userId())));
    }
}
