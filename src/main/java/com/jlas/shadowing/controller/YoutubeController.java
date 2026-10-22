package com.jlas.shadowing.controller;

import com.jlas.shadowing.dto.YoutubeAnalyzeResultDto;
import com.jlas.shadowing.dto.YoutubeImportResultDto;
import com.jlas.shadowing.dto.YoutubeImportStatusDto;
import com.jlas.shadowing.dto.YoutubeUrlRequest;
import com.jlas.shadowing.service.YoutubeImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/youtube")
public class YoutubeController {

    @Autowired private YoutubeImportService youtubeImportService;

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody YoutubeUrlRequest request) {
        try {
            if (request.getUrl() == null || request.getUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Vui lòng nhập link YouTube."));
            }
            YoutubeAnalyzeResultDto result = youtubeImportService.analyze(request.getUrl().trim());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Không phân tích được video: " + detail));
        }
    }

    @PostMapping("/import")
    public ResponseEntity<?> importVideo(@RequestBody YoutubeUrlRequest request) {
        try {
            if (request.getUrl() == null || request.getUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Vui lòng nhập link YouTube."));
            }
            YoutubeImportResultDto result = youtubeImportService.startImport(
                    request.getUrl().trim(),
                    request.getCategory(),
                    request.getTargetLanguage(),
                    request.getGenerateFurigana(),
                    request.getGenres());
            return ResponseEntity.accepted().body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Import thất bại."));
        }
    }

    @GetMapping("/import/{jobId}")
    public ResponseEntity<YoutubeImportStatusDto> importStatus(@PathVariable Long jobId) {
        return ResponseEntity.ok(youtubeImportService.getImportStatus(jobId));
    }

    @PostMapping("/import/{jobId}/retry")
    public ResponseEntity<?> retryImport(@PathVariable Long jobId) {
        youtubeImportService.retryImport(jobId);
        return ResponseEntity.ok(Map.of("message", "Retry started"));
    }
}
