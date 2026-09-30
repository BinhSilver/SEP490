package com.jlas.shadowing.controller;

import com.jlas.auth.security.CurrentUser;
import com.jlas.shadowing.dto.*;
import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.model.SubtitleQualityReview;
import com.jlas.shadowing.service.SubtitleQualityReviewService;
import com.jlas.shadowing.service.SubtitleService;
import com.jlas.shadowing.service.VideoService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SubtitleController {

    @Autowired private SubtitleService subtitleService;
    @Autowired private SubtitleQualityReviewService subtitleQualityReviewService;
    @Autowired private VideoService videoService;

    @PutMapping("/subtitles/{id}")
    public ResponseEntity<Subtitle> update(@PathVariable Long id, @RequestBody SubtitleUpdateRequest req) {
        return ResponseEntity.ok(subtitleService.update(id, req));
    }

    @DeleteMapping("/subtitles/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        subtitleService.delete(id);
        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }

    @PostMapping("/subtitles/{id}/regenerate")
    public ResponseEntity<Subtitle> regenerate(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String text = body != null ? body.get("text") : null;
        return ResponseEntity.ok(subtitleService.regenerate(id, text));
    }

    @PostMapping("/subtitles/{id}/split")
    public ResponseEntity<List<Subtitle>> split(@PathVariable Long id, @RequestBody SplitSubtitleRequest req) {
        return ResponseEntity.ok(subtitleService.split(id, req));
    }

    @PostMapping("/subtitles/merge")
    public ResponseEntity<List<Subtitle>> merge(@RequestBody MergeSubtitleRequest req) {
        return ResponseEntity.ok(subtitleService.merge(req));
    }

    @PostMapping("/videos/{videoId}/subtitles")
    public ResponseEntity<Subtitle> create(@PathVariable Long videoId, @RequestBody SubtitleCreateRequest req) {
        return ResponseEntity.ok(subtitleService.create(videoId, req));
    }

    @PutMapping("/videos/{videoId}/subtitles/bulk")
    public ResponseEntity<List<Subtitle>> bulkUpdate(
            @PathVariable Long videoId,
            @RequestBody List<SubtitleUpdateRequest> requests) {
        return ResponseEntity.ok(subtitleService.bulkUpdate(videoId, requests));
    }

    @GetMapping("/videos/{videoId}/subtitles/validate")
    public ResponseEntity<Map<String, Object>> validate(@PathVariable Long videoId) {
        return ResponseEntity.ok(subtitleService.validate(videoId));
    }

    @PutMapping("/videos/{videoId}/subtitles/reorder")
    public ResponseEntity<List<Subtitle>> reorder(
            @PathVariable Long videoId,
            @RequestBody ReorderSubtitlesRequest req) {
        return ResponseEntity.ok(subtitleService.reorder(videoId, req.getOrder()));
    }

    @PostMapping("/videos/{videoId}/regenerate-subtitles")
    public ResponseEntity<Map<String, String>> regenerateAll(@PathVariable Long videoId) {
        subtitleService.regenerateAllSubtitles(videoId);
        return ResponseEntity.accepted().body(Map.of("message", "Regenerating subtitles"));
    }

    @GetMapping("/videos/{videoId}/subtitle-quality")
    public ResponseEntity<Map<Long, SubtitleQualityReview>> qualityReviews(@PathVariable Long videoId) {
        videoService.requireOwner(videoService.getVideo(videoId));
        return ResponseEntity.ok(subtitleQualityReviewService.getForVideo(videoId));
    }

    @PutMapping("/subtitles/{id}/quality-review/approve")
    public ResponseEntity<SubtitleQualityReview> approveQualityReview(
            @PathVariable Long id, @Valid @RequestBody SubtitleQualityReviewApprovalRequest request) {
        Subtitle subtitle = subtitleService.getById(id);
        videoService.requireOwner(subtitle.getVideo());
        return ResponseEntity.ok(subtitleQualityReviewService.approve(
                id, request.text(), CurrentUser.requireUserId()));
    }
}
