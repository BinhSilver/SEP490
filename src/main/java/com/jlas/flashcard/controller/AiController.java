package com.jlas.flashcard.controller;

import com.jlas.flashcard.dto.AiDtos.*;
import com.jlas.flashcard.service.AiLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AiController {

    private final AiLookupService aiService;

    @PostMapping("/lookup")
    public ResponseEntity<AiLookupResponse> lookup(@RequestBody AiLookupRequest req) {
        return ResponseEntity.ok(aiService.lookup(req));
    }

    @PostMapping("/explain")
    public ResponseEntity<AiLookupResponse> explain(@RequestBody AiLookupRequest req) {
        req.setType("explain");
        return ResponseEntity.ok(aiService.lookup(req));
    }

    @PostMapping("/examples")
    public ResponseEntity<AiLookupResponse> examples(@RequestBody AiLookupRequest req) {
        req.setType("examples");
        return ResponseEntity.ok(aiService.lookup(req));
    }

    @PostMapping("/kanji")
    public ResponseEntity<AiLookupResponse> kanji(@RequestBody AiLookupRequest req) {
        req.setType("kanji");
        return ResponseEntity.ok(aiService.lookup(req));
    }

    @PostMapping("/chat")
    public ResponseEntity<AiLookupResponse> chat(@RequestBody AiChatRequest req) {
        return ResponseEntity.ok(aiService.chat(req));
    }
}
