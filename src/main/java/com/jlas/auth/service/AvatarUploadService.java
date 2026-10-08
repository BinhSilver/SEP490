package com.jlas.auth.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AvatarUploadService {
    // Dùng chung với thumbnail giáo trình (ContentAdminServiceImpl): cùng giới hạn, cùng định dạng ảnh.
    public static final long MAX_SIZE = 2L * 1024 * 1024;
    public static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    public static final Set<String> MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final Path uploadDirectory;

    public AvatarUploadService(@Value("${upload.path.avatar:uploads/avatar}") String uploadDirectory) {
        this.uploadDirectory = Paths.get(uploadDirectory).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() throws IOException {
        Files.createDirectories(uploadDirectory);
    }

    public String store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw badRequest("File is empty");
        if (file.getSize() > MAX_SIZE) throw badRequest("File is too large (maximum 2 MB)");

        String extension = extension(file.getOriginalFilename());
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension) || !MIME_TYPES.contains(contentType)) {
            throw badRequest("Only JPG, PNG and WEBP images are supported");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read avatar", exception);
        }
        if (!hasImageSignature(content, extension)) throw badRequest("Invalid image content");

        String filename = "u" + userId + "-" + UUID.randomUUID() + "." + extension;
        Path target = uploadDirectory.resolve(filename).normalize();
        if (!target.startsWith(uploadDirectory)) throw badRequest("Invalid upload path");
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save avatar", exception);
        }
        return "/uploads/avatar/" + filename;
    }

    public static String extension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /** Magic bytes phải khớp đuôi file (JPEG FF D8 FF, PNG 89 'PNG', WEBP 'RIFF'....'WEBP'). */
    public static boolean hasImageSignature(byte[] bytes, String extension) {
        if (bytes == null || bytes.length < 12) return false;
        if (extension.equals("jpg") || extension.equals("jpeg")) {
            return (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        }
        if (extension.equals("png")) {
            return (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        }
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
