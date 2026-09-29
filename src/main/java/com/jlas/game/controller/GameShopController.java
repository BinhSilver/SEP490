package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.game.dto.ShopDtos.ShopView;
import com.jlas.game.service.GameShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cửa hàng Trò chơi: danh sách món + số CP, mua bằng CP chung, chọn món đang dùng. Base path: /api/games/shop */
@RestController
@RequestMapping("/api/games/shop")
@RequiredArgsConstructor
public class GameShopController {
    private final GameShopService shopService;

    @GetMapping
    public ResponseEntity<ApiResponse<ShopView>> shop(@AuthenticationPrincipal UserPrincipal user) {
        return ok(shopService.shop(user.userId()));
    }

    @PostMapping("/items/{code}/buy")
    public ResponseEntity<ApiResponse<ShopView>> buy(@PathVariable String code,
                                                     @AuthenticationPrincipal UserPrincipal user) {
        return ok(shopService.buy(user.userId(), code));
    }

    @PostMapping("/items/{code}/equip")
    public ResponseEntity<ApiResponse<ShopView>> equip(@PathVariable String code,
                                                       @AuthenticationPrincipal UserPrincipal user) {
        return ok(shopService.equip(user.userId(), code));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.ok("OK", data));
    }
}
