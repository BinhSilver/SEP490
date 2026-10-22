package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.game.dto.AvatarFrameDtos.FrameEffect;
import com.jlas.game.service.AvatarFrameCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Danh mục hiển thị khung avatar cho avatar-frame.js. Công khai vì khung hiện cả ở trang không cần đăng nhập
 * (bảng xếp hạng, bình luận); gồm khung đã ẩn vì người đã mua vẫn đeo.
 */
@RestController
@RequestMapping("/api/avatar-frames")
@RequiredArgsConstructor
public class AvatarFrameCatalogController {
    private final AvatarFrameCatalogService catalog;

    @GetMapping
    public ApiResponse<List<FrameEffect>> frames() {
        return ApiResponse.ok("OK", catalog.all().stream().map(FrameEffect::of).toList());
    }
}
