package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathSpeakResult;
import com.jlas.jlptpath.service.impl.JpathSpeakService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * [JLAS-W0108] "Luyện nói theo" scoring of the Listening dictation lines: POST /api/learning/jlpt/speak (multipart:
 * {@code text} = the sentence, {@code audio} = the recording) → a {@link JpathSpeakResult}; GET /speak/left = scored
 * attempts left today. The learner is always the principal.
 */
@RestController
@RequestMapping("/api/learning/jlpt/speak")
public class JpathSpeakController {

    private final JpathSpeakService speak;

    public JpathSpeakController(JpathSpeakService speak) {
        this.speak = speak;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<JpathSpeakResult>> score(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam("text") String text, @RequestParam("audio") MultipartFile audio) {
        return ResponseEntity.ok(ApiResponse.ok("OK", speak.score(user.userId(), text, audio)));
    }

    @GetMapping("/left")
    public ResponseEntity<ApiResponse<Integer>> left(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", speak.left(user.userId())));
    }
}
