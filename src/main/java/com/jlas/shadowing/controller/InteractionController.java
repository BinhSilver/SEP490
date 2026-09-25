package com.jlas.shadowing.controller;

import com.jlas.shadowing.dto.*;
import com.jlas.shadowing.service.InteractionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InteractionController {

    @Autowired private InteractionService interactionService;

    @GetMapping("/videos/{id}/favorite")
    public ResponseEntity<Map<String, Object>> getFavorite(
            @PathVariable Long id,
            @RequestParam String clientId) {
        return ResponseEntity.ok(Map.of("favorited", interactionService.isFavorited(clientId, id)));
    }

    @PostMapping("/videos/{id}/favorite")
    public ResponseEntity<Map<String, Object>> toggleFavorite(
            @PathVariable Long id,
            @RequestParam String clientId) {
        boolean favorited = interactionService.toggleFavorite(clientId, id);
        return ResponseEntity.ok(Map.of("favorited", favorited));
    }

    @GetMapping("/videos/{id}/rating")
    public ResponseEntity<RatingSummaryDto> getRating(
            @PathVariable Long id,
            @RequestParam(required = false) String clientId) {
        return ResponseEntity.ok(interactionService.getRatingSummary(clientId, id));
    }

    @PostMapping("/videos/{id}/rating")
    public ResponseEntity<RatingSummaryDto> saveRating(
            @PathVariable Long id,
            @RequestBody RatingRequest req) {
        return ResponseEntity.ok(interactionService.saveRating(req.getClientId(), id, req.getRating()));
    }

    @GetMapping("/videos/{id}/comments")
    public ResponseEntity<List<CommentDto>> getComments(
            @PathVariable Long id,
            @RequestParam(required = false) String clientId) {
        return ResponseEntity.ok(interactionService.getComments(id, clientId));
    }

    @PostMapping("/videos/{id}/comments")
    public ResponseEntity<CommentDto> addComment(
            @PathVariable Long id,
            @RequestBody CommentRequest req) {
        return ResponseEntity.ok(interactionService.addComment(id, req));
    }

    @DeleteMapping("/comments/{id}")
    public ResponseEntity<Map<String, String>> deleteComment(
            @PathVariable Long id,
            @RequestParam String clientId) {
        interactionService.deleteComment(id, clientId);
        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }

    @PostMapping("/videos/{id}/watch")
    public ResponseEntity<WatchResultDto> recordWatch(
            @PathVariable Long id,
            @RequestBody WatchRequest req) {
        return ResponseEntity.ok(interactionService.recordWatch(id, req));
    }
}
