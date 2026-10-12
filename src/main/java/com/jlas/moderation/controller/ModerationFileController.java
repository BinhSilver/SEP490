package com.jlas.moderation.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.auth.service.AvatarUploadService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.service.AppealAttachmentStorage;
import com.jlas.moderation.service.AppealService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ModerationFileController {
    private static final Set<String> REVIEWERS = Set.of("ROLE_MODERATOR", "ROLE_ADMIN", "ROLE_SUPER_ADMIN");
    private final AvatarUploadService avatars;
    private final AppealService appeals;
    private final AppealAttachmentStorage attachments;

    @GetMapping("/api/admin/moderation/avatars/{id}/file")
    public ResponseEntity<Resource> avatar(@PathVariable Long id) {
        Path file = avatars.preview(id);
        String extension = AvatarUploadService.extension(file.getFileName().toString());
        String type = "png".equals(extension) ? "image/png" : "webp".equals(extension) ? "image/webp" : "image/jpeg";
        return response(file, file.getFileName().toString(), type, true);
    }
    @GetMapping("/api/appeals/{id}/file")
    public ResponseEntity<Resource> evidence(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal user) {
        if (user == null) throw new ApiException(ErrorCode.UNAUTHORIZED);
        boolean reviewer = user.getAuthorities().stream().anyMatch(a -> REVIEWERS.contains(a.getAuthority()));
        var appeal = appeals.attachment(id, user.userId(), reviewer);
        return response(attachments.path(appeal.getEvidenceFileKey()), appeal.getEvidenceFileName(),
                appeal.getEvidenceFileType(), false);
    }
    private ResponseEntity<Resource> response(Path file, String name, String type, boolean inline) {
        if (!Files.isRegularFile(file)) throw new ApiException(ErrorCode.NOT_FOUND, "File không còn tồn tại");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }
}
