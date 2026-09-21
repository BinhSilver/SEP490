package com.jlas.shadowing.controller;

import com.jlas.auth.security.CurrentUser;
import com.jlas.shadowing.dto.PronunciationAssessmentDto;
import com.jlas.shadowing.service.AzurePronunciationAssessmentService;
import com.jlas.shadowing.service.PronunciationAttemptService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/pronunciation")
public class PronunciationAssessmentController {
    private final AzurePronunciationAssessmentService assessmentService;
    private final PronunciationAttemptService attemptService;

    public PronunciationAssessmentController(AzurePronunciationAssessmentService assessmentService,
                                             PronunciationAttemptService attemptService) {
        this.assessmentService = assessmentService;
        this.attemptService = attemptService;
    }

    @PostMapping(value = "/assess", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PronunciationAssessmentDto assess(
            @RequestParam Long subtitleId,
            @RequestParam("audio") MultipartFile audio) {
        return assessmentService.assess(subtitleId, audio);
    }

    @GetMapping("/attempts/latest")
    public java.util.List<PronunciationAssessmentDto> latest(@RequestParam Long videoId) {
        return attemptService.latestForVideo(CurrentUser.requireUserId(), videoId);
    }

    @GetMapping("/attempts/history")
    public java.util.List<PronunciationAssessmentDto> history(@RequestParam Long videoId) {
        return attemptService.historyForVideo(CurrentUser.requireUserId(), videoId);
    }

    @GetMapping("/attempts/{attemptId}/audio")
    public ResponseEntity<Resource> audio(@org.springframework.web.bind.annotation.PathVariable Long attemptId) {
        PronunciationAttemptService.StoredOwnedRecording recording = attemptService.loadOwnedAudio(
                CurrentUser.requireUserId(), attemptId);
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .contentType(MediaType.parseMediaType(recording.contentType()))
                    .contentLength(recording.resource().contentLength())
                    .body(recording.resource());
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/attempts/{attemptId}")
    public ResponseEntity<Void> delete(@org.springframework.web.bind.annotation.PathVariable Long attemptId) {
        attemptService.delete(CurrentUser.requireUserId(), attemptId);
        return ResponseEntity.noContent().build();
    }
}
