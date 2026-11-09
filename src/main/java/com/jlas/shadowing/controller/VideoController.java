package com.jlas.shadowing.controller;

import com.jlas.common.exception.ApiException;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.shadowing.dto.*;
import com.jlas.shadowing.model.ShadowingGenre;
import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.service.ShadowingBlobStore;
import com.jlas.shadowing.service.VideoService;
import com.jlas.shadowing.util.youtube.YoutubeUrlValidator;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    @Autowired private VideoService videoService;
    @Autowired private ShadowingBlobStore blobStore;

    @GetMapping
    public ResponseEntity<List<VideoSummaryDto>> getAll(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false, defaultValue = "popular") String sort,
            @RequestParam(required = false) Boolean favoritesOnly,
            @RequestParam(required = false, defaultValue = "public") String scope,
            @RequestParam(required = false) String level) {
        return ResponseEntity.ok(videoService.search(q, tag, category, clientId, sort, favoritesOnly, scope, level));
    }

    @GetMapping("/library")
    public ResponseEntity<ApiResponse<LibraryPageDto<VideoSummaryDto>>> library(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "popular") String sort,
            @RequestParam(defaultValue = "public") String scope,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean favoritesOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(ApiResponse.ok("Thư viện video", videoService.searchPage(q, category, genre, level,
                sort, scope, status, favoritesOnly, page, size)));
    }

    @GetMapping("/genres")
    public ResponseEntity<List<GenreOption>> getGenres() {
        return ResponseEntity.ok(Arrays.stream(ShadowingGenre.values()).map(GenreOption::of).toList());
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
            @RequestParam(value = "genres", required = false) List<String> genres,
            @RequestParam(value = "targetLanguage", defaultValue = "vi") String targetLanguage,
            @RequestParam(value = "generateFurigana", required = false, defaultValue = "false") Boolean generateFurigana,
            @RequestParam(value = "minAge", required = false) Integer minAge) {
        try {
            Video video = videoService.uploadVideo(file, thumbnail, title, category, tags, targetLanguage,
                    generateFurigana, genres, minAge);
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

    /** Trang luyện vừa mở video này: ghi vào Lịch sử của người đang đăng nhập. */
    @PostMapping("/{id}/visit")
    public ResponseEntity<Void> visit(@PathVariable Long id) {
        videoService.recordVisit(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Void> removeHistory(@PathVariable Long id) {
        videoService.removeHistory(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> clearHistory() {
        videoService.clearHistory();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Video> getVideo(@PathVariable Long id) {
        return ResponseEntity.ok(videoService.getVideoForViewing(id));
    }

    public record AgeRatingRequest(Integer minAge) { }

    /** Owner or moderator sets the age rating from the player page: 13, 16 or 18; 0 or null = all ages. */
    @PutMapping("/{id}/age-rating")
    public ResponseEntity<Video> setAgeRating(@PathVariable Long id, @RequestBody AgeRatingRequest request) {
        return ResponseEntity.ok(videoService.setAgeRating(id, request.minAge()));
    }

    /** Lets a lesson page check the age rating before it loads: 204, or the error (BIRTH_DATE_REQUIRED…). */
    @GetMapping("/{id}/access")
    public ResponseEntity<Void> access(@PathVariable Long id) {
        videoService.getVideoForViewing(id);
        return ResponseEntity.noContent().build();
    }

    /** Library card data for one video (level, tags, counts, rating), for the practice page header. */
    @GetMapping("/{id}/summary")
    public ResponseEntity<VideoSummaryDto> getSummary(@PathVariable Long id,
                                                      @RequestParam(required = false) String clientId) {
        return ResponseEntity.ok(videoService.getSummary(id, clientId));
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

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        videoService.cancelProcessing(id);
        return ResponseEntity.ok(Map.of("message", "Đã dừng xử lý bài học."));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable Long id) {
        videoService.resumeProcessing(id);
        return ResponseEntity.ok(Map.of("message", "Đang tiếp tục xử lý từ bước đã lưu."));
    }

    @PostMapping("/{id}/abandon")
    public ResponseEntity<?> abandon(@PathVariable Long id) {
        videoService.abandonProcessing(id);
        return ResponseEntity.ok(Map.of("message", "Đã bỏ tiến trình. Bản nháp vẫn được giữ lại để tạo lại từ đầu."));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        videoService.deleteVideo(id);
        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }

    @GetMapping("/{id}/stream")
    public ResponseEntity<Resource> streamVideo(@PathVariable Long id) {
        Video video = videoService.getVideoForViewing(id);
        if (video.getVideoPath() == null || video.getVideoPath().isBlank() || video.isYoutubeEmbed()) {
            return ResponseEntity.notFound().build();
        }
        if (ShadowingBlobStore.isBlob(video.getVideoPath())) {
            // No link (Azure not configured here): the 404 makes the player switch to YouTube (youtube-media.js).
            return redirectTo(blobStore.signedUrl(video.getVideoPath()), org.springframework.http.CacheControl.noStore());
        }
        File file = new File(video.getVideoPath());
        if (!file.exists()) return ResponseEntity.notFound().build();
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
                .contentType(Objects.requireNonNull(resolveStreamMediaType(file.getName())))
                .body(resource);
    }

    private static ResponseEntity<Resource> redirectTo(java.util.Optional<java.net.URI> target,
                                                       org.springframework.http.CacheControl cache) {
        return target
                .map(url -> ResponseEntity.status(HttpStatus.FOUND).location(url).cacheControl(cache).<Resource>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
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
        if (ShadowingBlobStore.isBlob(video.getThumbnailPath())) {
            // Without Azure on this machine a YouTube video still shows YouTube's own thumbnail.
            return redirectTo(blobStore.signedUrl(video.getThumbnailPath()).or(() ->
                            YoutubeUrlValidator.extractVideoId(video.getSourceUrl())
                                    .map(youtubeId -> java.net.URI.create("https://i.ytimg.com/vi/" + youtubeId + "/hqdefault.jpg"))),
                    org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate());
        }
        File file = new File(video.getThumbnailPath());
        if (!file.exists()) return ResponseEntity.notFound().build();
        Resource resource = new FileSystemResource(file);
        MediaType mediaType = resolveThumbnailMediaType(file.getName().toLowerCase());
        // Thư viện hiển thị nhiều thumbnail mỗi trang; cho trình duyệt giữ lại thay vì tải lại mỗi lần đổi tab.
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
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
