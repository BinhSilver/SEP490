package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.game.dto.AvatarFrameDtos.CosmeticsView;
import com.jlas.game.dto.AvatarFrameDtos.EquipRequest;
import com.jlas.game.service.AvatarFrameService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Khung avatar + màu tên: danh sách, mua bằng CP chung, chọn món đang dùng. Base path: /api/profile/cosmetics */
@RestController
@RequestMapping("/api/profile/cosmetics")
@RequiredArgsConstructor
public class AvatarFrameController {
    private final AvatarFrameService frameService;

    @GetMapping
    public ResponseEntity<ApiResponse<CosmeticsView>> view(@AuthenticationPrincipal UserPrincipal user) {
        return ok(frameService.view(user.userId()));
    }

    @PostMapping("/frames/{code}/buy")
    public ResponseEntity<ApiResponse<CosmeticsView>> buy(@PathVariable String code,
                                                          @AuthenticationPrincipal UserPrincipal user) {
        return ok(frameService.buy(user.userId(), code));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<CosmeticsView>> equip(@Valid @RequestBody EquipRequest request,
                                                            @AuthenticationPrincipal UserPrincipal user) {
        return ok(frameService.equip(user.userId(), request.frameCode(), request.nameStyle()));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.ok("OK", data));
    }
}
