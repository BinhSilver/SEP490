package com.jlas.shadowing.controller;

import com.jlas.shadowing.dto.YoutubeAnalyzeResultDto;
import com.jlas.shadowing.dto.YoutubeImportResultDto;
import com.jlas.shadowing.dto.YoutubeImportStatusDto;
import com.jlas.shadowing.dto.YoutubeUrlRequest;
import com.jlas.auth.security.CurrentUser;
import com.jlas.shadowing.service.ShadowingCreateQuotaService;
import com.jlas.shadowing.service.YoutubeImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/youtube")
public class YoutubeController {

    @Autowired private YoutubeImportService youtubeImportService;
    @Autowired private ShadowingCreateQuotaService createQuota;

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

    /**
     * Video tính quá số phút miễn phí còn lại trong tuần thì trả 402 SHADOWING_CREATE_QUOTA_EXCEEDED; trang báo giá
     * rồi gửi lại với {@code useCredit=true} để trả phần vượt bằng CP. Không bắt đầu được thì hoàn phút và CP.
     */
    @PostMapping("/import")
    public ResponseEntity<?> importVideo(@RequestBody YoutubeUrlRequest request) {
        if (request.getUrl() == null || request.getUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Vui lòng nhập link YouTube."));
        }
        try {
            YoutubeImportResultDto result = youtubeImportService.startImport(
                    request.getUrl().trim(),
                    request.getCategory(),
                    request.getTargetLanguage(),
                    request.getGenerateFurigana(),
                    request.getGenres(),
                    request.getMinAge(),
                    request.isUseCredit());
            return ResponseEntity.accepted().body(result);
        } catch (com.jlas.common.exception.ApiException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Import thất bại."));
        }
    }

    /** Phút tạo video tuần này, giá CP mỗi phút vượt và số dư, cho dòng hạn mức và popup báo giá. */
    @GetMapping("/create-quota")
    public ShadowingCreateQuotaService.Status createQuota() {
        return createQuota.status(CurrentUser.requireUserId());
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
