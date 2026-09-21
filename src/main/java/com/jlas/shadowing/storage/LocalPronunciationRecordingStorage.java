package com.jlas.shadowing.storage;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "pronunciation.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalPronunciationRecordingStorage implements PronunciationRecordingStorage {
    private final Path root;

    public LocalPronunciationRecordingStorage(
            @Value("${pronunciation.storage.local.root:storage/pronunciation}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() throws IOException {
        Files.createDirectories(root);
    }

    @Override
    public StoredRecording store(Long userId, Long videoId, Long subtitleId, MultipartFile audio) {
        String contentType = normalizedContentType(audio.getContentType());
        String key = "users/" + userId + "/videos/" + videoId + "/subtitles/" + subtitleId
                + "/" + UUID.randomUUID() + extension(contentType);
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(audio.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredRecording(key, contentType, Files.size(target));
        } catch (IOException exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không thể lưu bản thu trên máy chủ");
        }
    }

    @Override
    public Resource load(String storageKey) {
        Path path = resolve(storageKey);
        if (!Files.isRegularFile(path)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Bản thu không còn tồn tại");
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return;
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException ignored) {
            // Retention cleanup can retry later; never expose a filesystem path to clients.
        }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Storage key không hợp lệ");
        }
        Path resolved = root.resolve(key.replace('\\', '/')).normalize();
        if (!resolved.startsWith(root)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Storage key không hợp lệ");
        }
        return resolved;
    }

    private static String normalizedContentType(String value) {
        if (value == null) return "audio/webm";
        return value.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "audio/ogg" -> ".ogg";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            default -> ".webm";
        };
    }
}
