package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.DialogueHeartbeatRequest;
import com.jlas.learning.dto.DialogueProgressResult;
import com.jlas.learning.dto.DialogueStudyState;
import com.jlas.learning.service.InteractiveDialogueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** [JLAS-W0193] Task 7 — Dialogue: read-only canonical script + server-timed completion for an owned V2
 * activity. No micro-check sub-path here (unlike Vocabulary/Kanji/Grammar) — Dialogue completes purely from
 * server-computed active time plus persisted bottom-reached evidence. */
@RestController
@RequestMapping("/api/learning/v2/activities/{activityId}/dialogue")
@RequiredArgsConstructor
public class InteractiveDialogueController {
    private final InteractiveDialogueService service;

    @GetMapping("/study")
    public ResponseEntity<ApiResponse<DialogueStudyState>> study(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.studyState(user.userId(), activityId)));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<ApiResponse<DialogueProgressResult>> heartbeat(
            @PathVariable Long activityId, @Valid @RequestBody DialogueHeartbeatRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.heartbeat(user.userId(), activityId, request)));
    }

    @PostMapping("/reached-bottom")
    public ResponseEntity<ApiResponse<DialogueProgressResult>> reachedBottom(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.reachedBottom(user.userId(), activityId)));
    }
}
