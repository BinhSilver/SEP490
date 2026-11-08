package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathAnswerRequest;
import com.jlas.jlptpath.dto.JpathAnswerResult;
import com.jlas.jlptpath.dto.JpathCheckIssueRequest;
import com.jlas.jlptpath.dto.JpathCheckResult;
import com.jlas.jlptpath.dto.JpathCheckSubmitRequest;
import com.jlas.jlptpath.dto.JpathCheckView;
import com.jlas.jlptpath.dto.JpathPairPickRequest;
import com.jlas.jlptpath.dto.JpathPairPickResult;
import com.jlas.jlptpath.service.JpathCheckService;
import jakarta.validation.Valid;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] Checks of the JLPT path: issue, read, pick a pair, answer, submit, and the audio of a listening
 * question (served by token + slot so the file name, which is the item code, never reaches the page).
 */
@RestController
@RequestMapping("/api/learning/jlpt/checks")
@RequiredArgsConstructor
public class JpathCheckController {

    private final JpathCheckService checkService;

    @PostMapping
    public ResponseEntity<ApiResponse<JpathCheckView>> issue(@AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody JpathCheckIssueRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK", checkService.issue(user.userId(), request)));
    }

    @GetMapping("/{token}")
    public ResponseEntity<ApiResponse<JpathCheckView>> get(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.ok("OK", checkService.get(user.userId(), token)));
    }

    @PostMapping("/{token}/pick")
    public ResponseEntity<ApiResponse<JpathPairPickResult>> pick(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String token, @Valid @RequestBody JpathPairPickRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK", checkService.pick(user.userId(), token, request)));
    }

    @PostMapping("/{token}/answers")
    public ResponseEntity<ApiResponse<JpathAnswerResult>> answer(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String token, @Valid @RequestBody JpathAnswerRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK", checkService.answer(user.userId(), token, request)));
    }

    @PostMapping("/{token}/submit")
    public ResponseEntity<ApiResponse<JpathCheckResult>> submit(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String token, @Valid @RequestBody(required = false) JpathCheckSubmitRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã nộp bài.", checkService.submit(user.userId(), token, request)));
    }

    @GetMapping("/{token}/audio/{slot}")
    public ResponseEntity<Resource> audio(@AuthenticationPrincipal UserPrincipal user, @PathVariable String token,
            @PathVariable int slot) {
        Path file = checkService.audio(user.userId(), token, slot);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(file));
    }
}
