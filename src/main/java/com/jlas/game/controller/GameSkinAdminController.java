package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.SkinTier;
import com.jlas.game.service.GameSkinCatalogService;
import com.jlas.game.service.GameSkinPackService;
import com.jlas.game.service.GameSkinCatalogService.SkinInput;
import com.jlas.game.util.GameShopCatalog;
import com.jlas.game.util.GameShopCatalog.ShopItem;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/game-skins")
@RequiredArgsConstructor
public class GameSkinAdminController {
    private final GameSkinCatalogService catalog;
    private final GameSkinPackService packs;

    /** Gói skin (.zip): kiểm tra định dạng rồi lưu; trả đường dẫn gói và ảnh đại diện để gắn vào trang phục. */
    @PostMapping("/pack")
    public ResponseEntity<ApiResponse<GameSkinPackService.PackInfo>> uploadPack(@RequestParam GameType gameType,
                                                                                @RequestParam MultipartFile file) {
        if (file.getSize() > 25L * 1024 * 1024) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Gói skin tối đa 25 MB");
        try {
            return ok(packs.upload(gameType, file.getBytes()));
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Không đọc được tệp. Hãy thử lại.");
        }
    }

    /** Skin gốc có gói mẫu, và các bộ vũ khí máy bay có sẵn. */
    @GetMapping("/pack-options")
    public ResponseEntity<ApiResponse<Map<String, Object>>> packOptions() {
        return ok(Map.of("templates", Map.of(GameType.NEKO_CATCH.name(), packs.templates(GameType.NEKO_CATCH),
                        GameType.AIR_DEFENSE.name(), packs.templates(GameType.AIR_DEFENSE)),
                "weaponThemes", GameSkinPackService.WEAPON_THEMES));
    }

    /** Gói mẫu (.zip) dựng từ một skin gốc, để hoạ sĩ làm theo đúng định dạng. */
    @GetMapping("/pack-template/{code}")
    public ResponseEntity<byte[]> packTemplate(@PathVariable String code) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"goi-mau-" + code + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(packs.template(code));
    }

    @GetMapping public ResponseEntity<ApiResponse<List<ShopItem>>> all() { return ok(catalog.all()); }
    /** Bậc trang phục cho form admin: mã, tên hiển thị và giá CP đi kèm. */
    public record TierView(String code, String label, int price) { }
    @GetMapping("/tiers") public ResponseEntity<ApiResponse<List<TierView>>> tiers() {
        return ok(java.util.Arrays.stream(SkinTier.values()).map(tier -> new TierView(tier.name(), tier.label(), tier.price())).toList());
    }
    @GetMapping("/renderers") public ResponseEntity<ApiResponse<List<ShopItem>>> renderers() {
        return ok(GameShopCatalog.SHOP_GAMES.stream().flatMap(game -> GameShopCatalog.items(game).stream()).toList());
    }
    @PostMapping public ResponseEntity<ApiResponse<ShopItem>> create(@Valid @RequestBody SkinInput input) { return ok(catalog.create(input)); }
    @PutMapping("/{code}") public ResponseEntity<ApiResponse<ShopItem>> update(@PathVariable String code, @Valid @RequestBody SkinInput input) { return ok(catalog.update(code, input)); }
    @PutMapping("/{code}/starter") public ResponseEntity<ApiResponse<ShopItem>> setStarter(@PathVariable String code) { return ok(catalog.setStarter(code)); }
    @DeleteMapping("/{code}") public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String code) { catalog.delete(code); return ok(null); }

    @PostMapping("/image")
    public ResponseEntity<ApiResponse<Map<String, String>>> upload(@RequestParam MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ảnh tối đa 5 MB");
        try {
            byte[] bytes = file.getBytes();
            String extension;
            try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn ảnh PNG hoặc JPG");
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    String format = reader.getFormatName();
                    if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg"))
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn ảnh PNG hoặc JPG");
                    if (reader.getWidth(0) > 2048 || reader.getHeight(0) > 2048)
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Kích thước ảnh tối đa 2048 × 2048");
                    if (reader.read(0) == null) throw new IOException("Invalid image");
                    extension = format.equalsIgnoreCase("png") ? ".png" : ".jpg";
                } finally { reader.dispose(); }
            }
            Path directory = Path.of("uploads", "game-skins").toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String name = UUID.randomUUID() + extension;
            Files.write(directory.resolve(name), bytes);
            return ok(Map.of("url", "/uploads/game-skins/" + name));
        } catch (IOException ex) { throw new ApiException(ErrorCode.VALIDATION_FAILED, "Không lưu được ảnh. Hãy thử lại."); }
    }
    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) { return ResponseEntity.ok(ApiResponse.ok("OK", data)); }
}
