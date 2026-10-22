package com.jlas.dictionary.service.impl;

import com.jlas.auth.service.AvatarUploadService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.LessonReadingImagesResponse;
import com.jlas.dictionary.dto.ReadingImageResponse;
import com.jlas.dictionary.service.ReadingImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * [JLAS2-17] Ảnh đoạn đọc. Dùng JdbcTemplate (không phải @Entity) có chủ đích: với ddl-auto=validate, một entity mới sẽ
 * làm app không khởi động được trên DB chưa chạy migration 022. Ở đây bảng thiếu thì chức năng tự tắt
 * (isAvailable = false), trang học vẫn dùng ảnh seed.
 */
@Slf4j
@Service
public class ReadingImageServiceImpl implements ReadingImageService {

    /** Khớp resource handler trong WebMvcConfig. */
    public static final String URL_PREFIX = "/uploads/reading-images/";
    public static final long MAX_SIZE = 5L * 1024 * 1024;
    /** Mã ổn định của seed chỉ gồm chữ, số, '.', '_', '-' — chặn luôn ../ và ký tự lạ trước khi chạm DB. */
    private static final Pattern STABLE_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$");
    private static final long RECHECK_MS = 60_000;

    private final JdbcTemplate jdbcTemplate;
    private final Path directory;
    private volatile Boolean available;
    private volatile long checkedAt;

    public ReadingImageServiceImpl(JdbcTemplate jdbcTemplate,
                                   @Value("${upload.path.reading-image:uploads/reading-images}") String uploadPath) {
        this.jdbcTemplate = jdbcTemplate;
        this.directory = Paths.get(uploadPath).toAbsolutePath().normalize();
    }

    @Override
    public boolean isAvailable() {
        Boolean known = available;
        if (Boolean.TRUE.equals(known)) return true;
        long now = System.currentTimeMillis();
        if (known != null && now - checkedAt < RECHECK_MS) return false;
        boolean found;
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'reading_image_override'", Integer.class);
            found = count != null && count > 0;
        } catch (DataAccessException exception) {
            found = false;
        }
        if (!found && known == null) log.info("[reading-image] Chưa có bảng reading_image_override (migration 022) — tắt chức năng ảnh bài đọc.");
        available = found;
        checkedAt = now;
        return found;
    }

    @Override
    public LessonReadingImagesResponse listForLesson(Long lessonId) {
        if (!isAvailable()) return LessonReadingImagesResponse.builder().available(false).items(List.of()).build();
        List<ReadingImageResponse> items = jdbcTemplate.query(
                "SELECT o.stable_code, o.image_url, o.hide_seed_image, o.updated_at FROM reading_image_override o "
                        + "WHERE o.stable_code IN (SELECT cs.stable_code FROM content_source cs "
                        + "JOIN content_item ci ON cs.entity_type = 'CONTENT_ITEM' AND cs.entity_id = ci.id "
                        + "JOIN lesson_section s ON s.id = ci.section_id "
                        + "WHERE s.lesson_id = ? AND ci.content_type = 'READING')",
                (rs, i) -> toResponse(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getTimestamp(4)),
                lessonId);
        return LessonReadingImagesResponse.builder().available(true).items(items).build();
    }

    @Override
    public ReadingImageResponse upload(String stableCode, MultipartFile file, Long userId) {
        requireAvailable();
        requireReadingCode(stableCode);
        String extension = validateImage(file);

        // Tên file chỉ gồm UUID + đuôi trong whitelist nên không thể chứa ../; vẫn kiểm startsWith cho chắc.
        String filename = UUID.randomUUID() + "." + extension;
        Path target = directory.resolve(filename).normalize();
        if (!target.startsWith(directory)) throw new ApiException(ErrorCode.BAD_REQUEST, "Đường dẫn lưu ảnh không hợp lệ.");
        try {
            Files.createDirectories(directory);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không lưu được ảnh, vui lòng thử lại.");
        }

        String newUrl = URL_PREFIX + filename;
        String oldUrl;
        try {
            oldUrl = currentImageUrl(stableCode);
            save(stableCode, newUrl, userId);
        } catch (RuntimeException exception) {
            deleteOwnedFile(newUrl);
            throw exception;
        }
        deleteOwnedFile(oldUrl);
        log.info("[reading-image] user={} {} ảnh bài đọc {} -> {}", userId, oldUrl == null ? "thêm" : "thay", stableCode, newUrl);
        return find(stableCode);
    }

    @Override
    public ReadingImageResponse hideSeedImage(String stableCode, Long userId) {
        requireAvailable();
        requireReadingCode(stableCode);
        String oldUrl = currentImageUrl(stableCode);
        save(stableCode, null, userId);
        deleteOwnedFile(oldUrl);
        log.info("[reading-image] user={} ẩn ảnh gốc bài đọc {}", userId, stableCode);
        return find(stableCode);
    }

    @Override
    public void remove(String stableCode, Long userId) {
        requireAvailable();
        requireReadingCode(stableCode);
        String oldUrl = currentImageUrl(stableCode);
        jdbcTemplate.update("DELETE FROM reading_image_override WHERE stable_code = ?", stableCode);
        deleteOwnedFile(oldUrl);
        log.info("[reading-image] user={} gỡ chỉnh sửa ảnh bài đọc {} (xoá {})", userId, stableCode, oldUrl);
    }

    // ── nội bộ ───────────────────────────────────────────────────────────────

    private void requireAvailable() {
        if (!isAvailable()) throw new ApiException(ErrorCode.READING_IMAGE_UNAVAILABLE);
    }

    private void requireReadingCode(String stableCode) {
        if (stableCode == null || !STABLE_CODE.matcher(stableCode).matches() || stableCode.contains("..")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Mã bài đọc không hợp lệ.");
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_source cs JOIN content_item ci "
                        + "ON cs.entity_type = 'CONTENT_ITEM' AND cs.entity_id = ci.id "
                        + "WHERE cs.stable_code = ? AND ci.content_type = 'READING'", Integer.class, stableCode);
        if (count == null || count == 0) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy bài đọc.");
    }

    private String currentImageUrl(String stableCode) {
        List<String> urls = jdbcTemplate.query("SELECT image_url FROM reading_image_override WHERE stable_code = ?",
                (rs, i) -> rs.getString(1), stableCode);
        return urls.isEmpty() ? null : urls.get(0);
    }

    /** Ghi đè dòng của mã (UPDATE, chưa có thì INSERT). Luôn ẩn ảnh seed: đã chỉnh thì ảnh seed không hiện nữa. */
    private void save(String stableCode, String imageUrl, Long userId) {
        int updated = jdbcTemplate.update(
                "UPDATE reading_image_override SET image_url = ?, hide_seed_image = 1, updated_by = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6) WHERE stable_code = ?", imageUrl, userId, stableCode);
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO reading_image_override (stable_code, image_url, hide_seed_image, updated_by) "
                    + "VALUES (?, ?, 1, ?)", stableCode, imageUrl, userId);
        }
    }

    private ReadingImageResponse find(String stableCode) {
        List<ReadingImageResponse> rows = jdbcTemplate.query(
                "SELECT stable_code, image_url, hide_seed_image, updated_at FROM reading_image_override WHERE stable_code = ?",
                (rs, i) -> toResponse(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getTimestamp(4)), stableCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static ReadingImageResponse toResponse(String code, String url, boolean hide, Timestamp updatedAt) {
        return ReadingImageResponse.builder().stableCode(code).imageUrl(url).hideSeedImage(hide)
                .updatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime()).build();
    }

    /**
     * Kiểm file ảnh, trả về đuôi đã chuẩn hoá. Dùng lại whitelist và kiểm magic bytes của avatar (AvatarUploadService):
     * PNG 89 50 4E 47, JPEG FF D8 FF, WEBP RIFF....WEBP — content-type của trình duyệt chỉ là điều kiện phụ.
     */
    static String validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Vui lòng chọn một ảnh.");
        if (file.getSize() > MAX_SIZE) throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Ảnh đoạn đọc tối đa 5MB.");
        String extension = AvatarUploadService.extension(file.getOriginalFilename());
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!AvatarUploadService.EXTENSIONS.contains(extension) || !AvatarUploadService.MIME_TYPES.contains(contentType)) {
            throw new ApiException(ErrorCode.INVALID_IMAGE);
        }
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException exception) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không đọc được file ảnh.");
        }
        if (read < head.length || !AvatarUploadService.hasImageSignature(head, extension)) {
            throw new ApiException(ErrorCode.INVALID_IMAGE, "Nội dung file không phải ảnh JPG, PNG hoặc WEBP hợp lệ.");
        }
        return "jpeg".equals(extension) ? "jpg" : extension;
    }

    /**
     * Chỉ xoá file do chính tính năng này tạo: URL bắt đầu bằng /uploads/reading-images/, tên file không chứa dấu phân
     * cách thư mục, và đường dẫn sau normalize vẫn nằm trong thư mục upload. Ảnh seed (đường dẫn tương đối trong
     * content.audio.path) không bao giờ khớp nên không thể bị xoá.
     */
    void deleteOwnedFile(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) return;
        String filename = url.substring(URL_PREFIX.length());
        if (filename.isEmpty() || filename.contains("/") || filename.contains("\\") || filename.contains("..")) return;
        Path target = directory.resolve(filename).normalize();
        if (!target.startsWith(directory) || !target.getParent().equals(directory)) return;
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            log.warn("[reading-image] Không xoá được ảnh cũ {}: {}", target, exception.getMessage());
        }
    }
}
