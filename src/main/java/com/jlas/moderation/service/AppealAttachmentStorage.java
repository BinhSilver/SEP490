package com.jlas.moderation.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.util.ImageUploads;
import com.jlas.moderation.util.UploadFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Appeal evidence remains private, including evidence of violating content. */
@Service
public class AppealAttachmentStorage {
    public static final long MAX_SIZE = 5L * 1024 * 1024;
    private final Path directory;
    public record Stored(String key, String name, String contentType, Long size) {}
    public AppealAttachmentStorage(@Value("${moderation.appeal.storage:storage/moderation/appeals}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }
    public Stored store(MultipartFile file) {
        byte[] bytes = UploadFiles.read(file, MAX_SIZE);
        String extension = UploadFiles.extension(file.getOriginalFilename());
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if ("pdf".equals(extension)) {
            String start = new String(bytes, 0, Math.min(8, bytes.length), StandardCharsets.US_ASCII);
            String tail = new String(bytes, Math.max(0, bytes.length - 1024), Math.min(1024, bytes.length), StandardCharsets.US_ASCII);
            if (!"application/pdf".equals(type) || !start.startsWith("%PDF-") || !tail.contains("%%EOF")) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "File PDF không hợp lệ");
            }
        } else {
            // Shares the actual image decoding checks with avatar uploads.
            bytes = ImageUploads.validate(file, MAX_SIZE, false);
        }
        String key = UUID.randomUUID() + "." + extension;
        UploadFiles.write(path(key), bytes);
        return new Stored(key, UploadFiles.displayName(file.getOriginalFilename()), type, (long) bytes.length);
    }
    public Path path(String key) {
        Path target = directory.resolve(key).normalize();
        if (!target.startsWith(directory) || !target.getParent().equals(directory)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Đường dẫn file không hợp lệ");
        }
        return target;
    }
}
