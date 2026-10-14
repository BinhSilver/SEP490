package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.service.GameSkinCatalogService;
import com.jlas.game.service.GameSkinCatalogService.SkinInput;
import com.jlas.game.util.GameShopCatalog;
import com.jlas.game.util.GameShopCatalog.ShopItem;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @GetMapping public ResponseEntity<ApiResponse<List<ShopItem>>> all() { return ok(catalog.all()); }
    @GetMapping("/renderers") public ResponseEntity<ApiResponse<List<ShopItem>>> renderers() {
        return ok(GameShopCatalog.SHOP_GAMES.stream().flatMap(game -> GameShopCatalog.items(game).stream()).toList());
    }
    @PostMapping public ResponseEntity<ApiResponse<ShopItem>> create(@Valid @RequestBody SkinInput input) { return ok(catalog.create(input)); }
    @PutMapping("/{code}") public ResponseEntity<ApiResponse<ShopItem>> update(@PathVariable String code, @Valid @RequestBody SkinInput input) { return ok(catalog.update(code, input)); }
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
