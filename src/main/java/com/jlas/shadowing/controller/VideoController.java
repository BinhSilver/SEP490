package com.jlas.shadowing.controller;

import com.jlas.common.exception.ApiException;
import com.jlas.shadowing.dto.*;
import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.service.VideoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    @Autowired private VideoService videoService;

    @GetMapping
    public ResponseEntity<List<VideoSummaryDto>> getAll(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false, defaultValue = "newest") String sort,
            @RequestParam(required = false) Boolean favoritesOnly,
            @RequestParam(required = false, defaultValue = "public") String scope) {
        return ResponseEntity.ok(videoService.search(q, tag, category, clientId, sort, favoritesOnly, scope));
    }

    @GetMapping("/categories")
    public ResponseEntity<List<String>> getCategories() {
        return ResponseEntity.ok(videoService.getCategories());
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "targetLanguage", defaultValue = "vi") String targetLanguage,
            @RequestParam(value = "generateFurigana", required = false, defaultValue = "false") Boolean generateFurigana) {
        try {
            Video video = videoService.uploadVideo(file, thumbnail, title, category, tags, targetLanguage, generateFurigana);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "id", video.getId(),
                    "status", video.getStatus().name(),
                    "message", "Processing started"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Video> getVideo(@PathVariable Long id) {
        return ResponseEntity.ok(videoService.getVideo(id));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<VideoStatusDto> getStatus(@PathVariable Long id) {
        return ResponseEntity.ok(videoService.getStatus(id));
    }

    @GetMapping("/{id}/subtitles")
    public ResponseEntity<List<Subtitle>> getSubtitles(@PathVariable Long id) {
        return ResponseEntity.ok(videoService.getSubtitles(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Video> updateVideo(@PathVariable Long id, @RequestBody VideoUpdateRequest req) {
        return ResponseEntity.ok(videoService.updateVideo(id, req));
    }

    @PutMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(videoService.publish(id));
        } catch (ApiException e) {
            return ResponseEntity.status(e.getErrorCode().getStatus()).body(Map.of(
                    "code", e.getErrorCode().name(),
                    "error", e.getMessage() != null ? e.getMessage() : e.getErrorCode().getDefaultMessage(),
                    "message", e.getMessage() != null ? e.getMessage() : e.getErrorCode().getDefaultMessage()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Không xuất bản được",
                    "message", e.getMessage() != null ? e.getMessage() : "Validation failed"
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Lỗi xuất bản"
            ));
        }
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable Long id) {
        videoService.retryProcessing(id);
        return ResponseEntity.ok(Map.of("message", "Retry started"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        videoService.deleteVideo(id);
        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }

    @GetMapping("/{id}/stream")
    public ResponseEntity<Resource> streamVideo(@PathVariable Long id) {
        Video video = videoService.getVideo(id);
        File file = new File(video.getVideoPath());
        if (!file.exists()) return ResponseEntity.notFound().build();
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
                .contentType(Objects.requireNonNull(resolveStreamMediaType(file.getName())))
                .body(resource);
    }

    private static MediaType resolveStreamMediaType(String fileName) {
        String lower = fileName != null ? fileName.toLowerCase() : "";
        if (lower.endsWith(".mp3")) return MediaType.parseMediaType("audio/mpeg");
        if (lower.endsWith(".wav")) return MediaType.parseMediaType("audio/wav");
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) return MediaType.parseMediaType("audio/mp4");
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) return MediaType.parseMediaType("audio/ogg");
        if (lower.endsWith(".flac")) return MediaType.parseMediaType("audio/flac");
        if (lower.endsWith(".webm")) return MediaType.parseMediaType("video/webm");
        if (lower.endsWith(".mov")) return MediaType.parseMediaType("video/quicktime");
        if (lower.endsWith(".mkv")) return MediaType.parseMediaType("video/x-matroska");
        return MediaType.parseMediaType("video/mp4");
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable Long id) {
        Video video = videoService.getVideo(id);
        if (video.getThumbnailPath() == null) return ResponseEntity.notFound().build();
        File file = new File(video.getThumbnailPath());
        if (!file.exists()) return ResponseEntity.notFound().build();
        Resource resource = new FileSystemResource(file);
        MediaType mediaType = resolveThumbnailMediaType(file.getName().toLowerCase());
        return ResponseEntity.ok()
                .contentType(Objects.requireNonNull(mediaType))
                .body(resource);
    }

    private static MediaType resolveThumbnailMediaType(String name) {
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }
}
