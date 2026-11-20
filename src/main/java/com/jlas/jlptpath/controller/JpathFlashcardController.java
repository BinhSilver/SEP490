package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.impl.JpathFlashcardService;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Owner 18/11: one private flashcard set per JLPT part (team flashcard feature). */
@RestController
@RequestMapping("/api/learning/jlpt/flashcards")
@RequiredArgsConstructor
public class JpathFlashcardController {

    private final JpathFlashcardService service;

    /** The sets of one tab's parts; today's set is made here without a button. */
    @GetMapping
    public ResponseEntity<ApiResponse<JpathFlashcardService.PartSets>> sets(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam String tab) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.sets(user.userId(), tab(tab))));
    }

    /** "Học bằng flashcard" on a learned or upcoming part. */
    @PostMapping("/open")
    public ResponseEntity<ApiResponse<JpathFlashcardService.PartSet>> open(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam String tab, @RequestParam int part) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.open(user.userId(), tab(tab), part)));
    }

    private static JpathTab tab(String tab) {
        try {
            return JpathTab.valueOf(tab.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chỉ từ vựng và Kanji có flashcard.");
        }
    }
}
