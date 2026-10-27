package com.jlas.game.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.dto.AvatarFrameDtos.AdminFrame;
import com.jlas.game.repository.AvatarFrameCatalogRepository;
import com.jlas.game.service.AvatarFrameCatalogService;
import com.jlas.game.service.AvatarFrameCatalogService.FrameInput;
import com.jlas.game.util.AvatarFrameCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin quản lý khung avatar (chỉ ADMIN, xem SecurityConfig). */
@RestController
@RequestMapping("/api/admin/avatar-frames")
@RequiredArgsConstructor
public class AvatarFrameAdminController {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_SIZE = 2048;
    /** Alpha tối đa coi là trong suốt (0–255). */
    private static final int TRANSPARENT = 16;

    private final AvatarFrameCatalogService catalog;
    private final AvatarFrameCatalogRepository repository;

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

    /** PNG nền trong suốt, hình vuông, tối đa 2048 px và 5 MB; lưu vào uploads/avatar-frames/. */
    @PostMapping("/image")
    public ApiResponse<Map<String, String>> upload(@RequestParam MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ảnh khung tối đa 5 MB");
        }
        try {
            byte[] bytes = file.getBytes();
            validate(bytes);
            Path directory = Path.of("uploads", "avatar-frames").toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String name = UUID.randomUUID() + ".png";
            Files.write(directory.resolve(name), bytes);
            return ApiResponse.ok("Đã tải ảnh", Map.of("url", "/uploads/avatar-frames/" + name));
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Không lưu được ảnh. Hãy thử lại.");
        }
    }

    /** Ném lỗi dễ hiểu nếu ảnh không dùng làm khung được. */
    static void validate(byte[] bytes) throws IOException {
        String format;
        BufferedImage image;
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn ảnh PNG");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                format = reader.getFormatName();
                if (!"png".equalsIgnoreCase(format)) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ảnh khung phải là PNG (cần nền trong suốt)");
                }
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width > MAX_SIZE || height > MAX_SIZE) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Kích thước ảnh tối đa 2048 × 2048");
                }
                if (width != height) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ảnh khung phải là hình vuông");
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        if (image == null) throw new IOException("Invalid image");
        if (!image.getColorModel().hasAlpha()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ảnh khung cần nền trong suốt (PNG có kênh alpha)");
        }
        // Góc ảnh là nền, giữa ảnh là lỗ để lộ avatar: cả năm điểm phải trong suốt.
        int last = image.getWidth() - 1, middle = image.getWidth() / 2;
        int[][] points = {{0, 0}, {last, 0}, {0, last}, {last, last}, {middle, middle}};
        for (int[] point : points) {
            if ((image.getRGB(point[0], point[1]) >>> 24) > TRANSPARENT) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Nền và lỗ tròn ở giữa khung phải trong suốt để lộ avatar");
            }
        }
    }
}
