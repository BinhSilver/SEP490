package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.dto.AvatarFrameDtos.AdminFrame;
import com.jlas.game.repository.AvatarFrameCatalogRepository;
import com.jlas.game.service.AvatarFrameCatalogService;
import com.jlas.game.service.AvatarFramePackService;
import com.jlas.game.service.AvatarFrameCatalogService.FrameInput;
import com.jlas.game.util.AvatarFrameCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Admin quản lý khung avatar (chỉ ADMIN, xem SecurityConfig). */
@RestController
@RequestMapping("/api/admin/avatar-frames")
@RequiredArgsConstructor
public class AvatarFrameAdminController {

    private final AvatarFrameCatalogService catalog;
    private final AvatarFrameCatalogRepository repository;
    private final AvatarFramePackService packs;

    @GetMapping
    public ApiResponse<List<AdminFrame>> all() {
        Map<String, Integer> owners = repository.ownerCounts();
        return ApiResponse.ok("OK", catalog.adminAll().stream()
                .map(frame -> AdminFrame.of(frame, owners.getOrDefault(frame.code(), 0))).toList());
    }

    /** Chủ đề hiệu ứng có sẵn (mã → nhãn màu tên) cho ô chọn ở form. */
    @GetMapping("/themes")
    public ApiResponse<Map<String, String>> themes() {
        return ApiResponse.ok("OK", AvatarFrameCatalog.THEMES);
    }

    @PostMapping
    public ApiResponse<AdminFrame> create(@Valid @RequestBody FrameInput input) {
        return ApiResponse.ok("Đã thêm khung", AdminFrame.of(catalog.create(input), 0));
    }

    @PutMapping("/{code}")
    public ApiResponse<AdminFrame> update(@PathVariable String code, @Valid @RequestBody FrameInput input) {
        return ApiResponse.ok("Đã lưu khung", AdminFrame.of(catalog.update(code, input), repository.owners(code)));
    }

    @DeleteMapping("/{code}")
    public ApiResponse<Void> delete(@PathVariable String code) {
        catalog.delete(code);
        return ApiResponse.ok("Đã xoá khung");
    }

    /** Chỉ ảnh khung (PNG nền trong suốt hoặc WebP, vuông, ≤ 2048 px, ≤ 5 MB); không có hiệu ứng riêng. */
    @PostMapping("/image")
    public ApiResponse<Map<String, String>> upload(@RequestParam MultipartFile file) {
        return ApiResponse.ok("Đã tải ảnh", Map.of("url", packs.uploadImage(read(file))));
    }

    /** Gói khung (.zip): ảnh + manifest.json ghi vị trí đá chớp sáng, quả cầu, giọt treo. */
    @PostMapping("/pack")
    public ApiResponse<AvatarFramePackService.FramePack> uploadPack(@RequestParam MultipartFile file) {
        return ApiResponse.ok("Đã tải gói khung", packs.upload(read(file)));
    }

    /** Gói mẫu dựng từ một khung có sẵn. */
    @GetMapping("/pack-template/{code}")
    public ResponseEntity<byte[]> packTemplate(@PathVariable String code) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"goi-khung-" + code + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(packs.template(catalog.require(code)));
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Không đọc được tệp. Hãy thử lại.");
        }
    }

    /** Kiểm tra ảnh PNG khung (dùng chung với gói khung). */
    static void validate(byte[] bytes) throws IOException {
        AvatarFramePackService.validatePng(bytes);
    }
}
