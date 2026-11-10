package com.jlas.dictionary.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.dictionary.dto.LessonReadingImagesResponse;
import com.jlas.dictionary.dto.ReadingImageResponse;
import com.jlas.dictionary.service.ReadingImageService;
import com.jlas.dictionary.service.TextbookAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * [JLAS2-17] Ảnh đoạn đọc trên trang học. GET công khai (nằm trong GET /api/dictionary/** permitAll) để người học thấy
 * ảnh đã thay; ghi/xoá chặn bằng @PreAuthorize("hasRole('CONTENT_CREATOR')") — cùng quyền với mọi API quản trị nội dung
 * JLAS2-17 (DictionaryController /manage/**, JlptAdminController): chưa đăng nhập → 401 (anyRequest().authenticated()), sai vai → 403.
 */
@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
public class ReadingImageController {

    private final ReadingImageService readingImageService;
    private final TextbookAccessPolicy accessPolicy;

    /** GET /api/dictionary/lessons/{lessonId}/reading-images — ảnh đã chỉnh của các bài đọc trong bài học. */
    @GetMapping("/lessons/{lessonId}/reading-images")
    public ResponseEntity<ApiResponse<LessonReadingImagesResponse>> list(@PathVariable Long lessonId) {
        accessPolicy.requireLessonApiAccess(lessonId);   // khách → 401; sách bị khóa → 403 PREMIUM_REQUIRED
        return ResponseEntity.ok(ApiResponse.ok("OK", readingImageService.listForLesson(lessonId)));
    }

    /** POST /api/dictionary/reading-images/{stableCode} — thêm / thay ảnh (multipart, field "file", JPG/PNG/WEBP ≤ 5MB). */
    @PostMapping(value = "/reading-images/{stableCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<ReadingImageResponse>> upload(@PathVariable String stableCode,
                                                                    @RequestParam("file") MultipartFile file,
                                                                    @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật ảnh bài đọc",
                readingImageService.upload(stableCode, file, userId(user))));
    }

    /** PUT /api/dictionary/reading-images/{stableCode}/hidden — ẩn ảnh gốc của seed, không thay ảnh khác. */
    @PutMapping("/reading-images/{stableCode}/hidden")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<ReadingImageResponse>> hide(@PathVariable String stableCode,
                                                                  @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Đã ẩn ảnh gốc của bài đọc",
                readingImageService.hideSeedImage(stableCode, userId(user))));
    }

    /** DELETE /api/dictionary/reading-images/{stableCode} — gỡ chỉnh sửa, bài đọc quay về ảnh seed. */
    @DeleteMapping("/reading-images/{stableCode}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<Void>> remove(@PathVariable String stableCode,
                                                    @AuthenticationPrincipal UserPrincipal user) {
        readingImageService.remove(stableCode, userId(user));
        return ResponseEntity.ok(ApiResponse.ok("Đã gỡ ảnh bài đọc", null));
    }

    private static Long userId(UserPrincipal user) {
        return user == null ? null : user.userId();
    }
}
