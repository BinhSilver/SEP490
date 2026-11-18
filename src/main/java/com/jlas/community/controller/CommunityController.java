package com.jlas.community.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.CurrentUser;
import com.jlas.community.dto.CommunityDtos.CommentDto;
import com.jlas.community.dto.CommunityDtos.CommentRequest;
import com.jlas.community.dto.CommunityDtos.ContentDetail;
import com.jlas.community.dto.CommunityDtos.FeedItem;
import com.jlas.community.dto.CommunityDtos.Meta;
import com.jlas.community.dto.CommunityDtos.PageResult;
import com.jlas.community.dto.CommunityDtos.PostRequest;
import com.jlas.community.dto.CommunityDtos.RatingRequest;
import com.jlas.community.dto.CommunityDtos.RatingResult;
import com.jlas.community.dto.CommunityDtos.ReportRequest;
import com.jlas.community.dto.CommunityDtos.RemoveRequest;
import com.jlas.community.dto.CommunityDtos.ReportResult;
import com.jlas.community.service.CommunityCommentService;
import com.jlas.community.service.CommunityContentService;
import com.jlas.community.service.CommunityFeedService;
import com.jlas.community.service.CommunityModerationService;
import com.jlas.community.service.CommunityPostMediaService;
import com.jlas.community.service.CommunityPostService;
import com.jlas.community.service.CommunityReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Community Hub. Mọi endpoint yêu cầu đăng nhập (mặc định của SecurityConfig); quyền xem/sở hữu
 * được kiểm tra ở service. Nội dung được định danh bằng loại + id gốc: /contents/{contentType}/{contentId}.
 */
@RestController
@RequestMapping("/api/community")
@RequiredArgsConstructor
public class CommunityController {
    private final CommunityFeedService feed;
    private final CommunityContentService contents;
    private final CommunityCommentService comments;
    private final CommunityReportService reports;
    private final CommunityPostService posts;
    private final CommunityModerationService moderation;
    private final CommunityPostMediaService postMedia;

    @GetMapping("/meta")
    public ResponseEntity<ApiResponse<Meta>> meta() {
        return ResponseEntity.ok(ApiResponse.ok("OK", feed.meta()));
    }

    @GetMapping("/contents")
    public ResponseEntity<ApiResponse<PageResult<FeedItem>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) String jlptLevel,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        var query = new CommunityFeedService.Query(keyword, contentType, jlptLevel, minRating, sort, page, size);
        return ResponseEntity.ok(ApiResponse.ok("OK", feed.search(query, CurrentUser.requireUserId())));
    }

    @GetMapping("/contents/{contentType}/{contentId}")
    public ResponseEntity<ApiResponse<ContentDetail>> detail(@PathVariable String contentType,
                                                             @PathVariable Long contentId) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                contents.detail(contentType, contentId, CurrentUser.requireUserId())));
    }

    @PutMapping("/contents/{contentType}/{contentId}/rating")
    public ResponseEntity<ApiResponse<RatingResult>> rate(@PathVariable String contentType,
                                                          @PathVariable Long contentId,
                                                          @Valid @RequestBody RatingRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu đánh giá",
                contents.rate(contentType, contentId, CurrentUser.requireUserId(), request.score())));
    }

    @DeleteMapping("/contents/{contentType}/{contentId}/rating")
    public ResponseEntity<ApiResponse<RatingResult>> unrate(@PathVariable String contentType,
                                                            @PathVariable Long contentId) {
        return ResponseEntity.ok(ApiResponse.ok("Đã gỡ đánh giá",
                contents.unrate(contentType, contentId, CurrentUser.requireUserId())));
    }

    @GetMapping("/contents/{contentType}/{contentId}/comments")
    public ResponseEntity<ApiResponse<PageResult<CommentDto>>> comments(@PathVariable String contentType,
                                                                        @PathVariable Long contentId,
                                                                        @RequestParam(required = false) Integer page,
                                                                        @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                comments.list(contentType, contentId, CurrentUser.requireUserId(), page, size)));
    }

    @PostMapping("/contents/{contentType}/{contentId}/comments")
    public ResponseEntity<ApiResponse<CommentDto>> comment(@PathVariable String contentType,
                                                           @PathVariable Long contentId,
                                                           @Valid @RequestBody CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Đã đăng bình luận",
                comments.create(contentType, contentId, CurrentUser.requireUserId(), request.body())));
    }

    @PutMapping("/comments/{commentId}")
    public ResponseEntity<ApiResponse<CommentDto>> editComment(@PathVariable Long commentId,
                                                               @Valid @RequestBody CommentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật bình luận",
                comments.update(commentId, CurrentUser.requireUserId(), request.body())));
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<ApiResponse<Void>> deleteComment(@PathVariable Long commentId) {
        comments.delete(commentId, CurrentUser.requireUserId());
        return ResponseEntity.ok(ApiResponse.ok("Đã xoá bình luận"));
    }

    // ── Bài viết (POST). Bình luận / báo cáo bài viết dùng chung endpoint ở trên với contentType = POST. ──
    @PostMapping("/posts")
    public ResponseEntity<ApiResponse<FeedItem>> createPost(@Valid @RequestBody PostRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Đã đăng bài viết",
                posts.create(CurrentUser.requireUserId(), request.body(), request.jlptLevel())));
    }

    /**
     * Đăng bài kèm ảnh (≤ 4, JPG/PNG/WebP ≤ 5MB) và/hoặc một video (MP4/WebM ≤ 50MB hoặc link YouTube). Cùng đường dẫn
     * với bản JSON, phân biệt bằng Content-Type. Kiểm tra chữ + tệp rồi mới tải tệp lên, sau đó lưu bài và gắn media.
     */
    @PostMapping(value = "/posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FeedItem>> createPostWithMedia(
            @RequestParam("body") String body,
            @RequestParam(value = "jlptLevel", required = false) String jlptLevel,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "video", required = false) MultipartFile video,
            @RequestParam(value = "youtubeUrl", required = false) String youtubeUrl) {
        Long userId = CurrentUser.requireUserId();
        CommunityPostService.clean(body);
        List<CommunityPostMediaService.Stored> stored = postMedia.prepare(images, video, youtubeUrl);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Đã đăng bài viết",
                posts.create(userId, body, jlptLevel, postId -> postMedia.attach(postId, stored))));
    }

    @GetMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<FeedItem>> post(@PathVariable Long postId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", posts.view(postId, CurrentUser.requireUserId())));
    }

    @PutMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<FeedItem>> editPost(@PathVariable Long postId,
                                                          @Valid @RequestBody PostRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật bài viết",
                posts.update(postId, CurrentUser.requireUserId(), request.body(), request.jlptLevel())));
    }

    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<Void>> deletePost(@PathVariable Long postId) {
        posts.delete(postId, CurrentUser.requireUserId());
        return ResponseEntity.ok(ApiResponse.ok("Đã xoá bài viết"));
    }

    // ── Gỡ trực tiếp (MODERATOR/ADMIN; người khác 403, kiểm tra ở service) ──
    @PostMapping("/posts/{postId}/remove")
    public ResponseEntity<ApiResponse<FeedItem>> removePost(@PathVariable Long postId,
                                                            @Valid @RequestBody RemoveRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã gỡ bài viết",
                moderation.removePost(postId, CurrentUser.requireUserId(), request.reason())));
    }

    @PostMapping("/comments/{commentId}/remove")
    public ResponseEntity<ApiResponse<Void>> removeComment(@PathVariable Long commentId,
                                                           @Valid @RequestBody RemoveRequest request) {
        moderation.removeComment(commentId, CurrentUser.requireUserId(), request.reason());
        return ResponseEntity.ok(ApiResponse.ok("Đã gỡ bình luận"));
    }

    @PostMapping("/reports")
    public ResponseEntity<ApiResponse<ReportResult>> report(@Valid @RequestBody ReportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Đã gửi báo cáo",
                reports.report(request, CurrentUser.requireUserId())));
    }
}
