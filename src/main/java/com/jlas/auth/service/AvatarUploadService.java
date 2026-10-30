package com.jlas.auth.service;

import com.jlas.auth.entity.AvatarSubmission;
import com.jlas.auth.repository.AvatarSubmissionRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.dto.ModerationTargetSnapshot;
import com.jlas.moderation.service.ContentModerationPort;
import com.jlas.moderation.service.ModerationRateLimitService;
import com.jlas.moderation.service.ModerationTargetHandler;
import com.jlas.moderation.service.UserActionPolicyPort;
import com.jlas.moderation.model.ModerationVerdict;
import com.jlas.moderation.util.ImageUploads;
import com.jlas.moderation.util.UploadFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AvatarUploadService implements ModerationTargetHandler {
    public static final long MAX_SIZE = 10L * 1024 * 1024;
    public static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    public static final Set<String> MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    public record UploadResult(Long id, String avatar, String moderationStatus) {}
    private final Path publicDirectory;
    private final Path privateDirectory;
    private final AvatarSubmissionRepository submissions;
    private final UserRepository users;
    private final ContentModerationPort moderation;
    private final ModerationRateLimitService rateLimits;
    private final UserActionPolicyPort actionPolicy;

    public AvatarUploadService(@Value("${upload.path.avatar:uploads/avatar}") String publicDirectory,
            @Value("${moderation.avatar.storage:storage/moderation/avatar}") String privateDirectory,
            AvatarSubmissionRepository submissions, UserRepository users,
            ContentModerationPort moderation, ModerationRateLimitService rateLimits,
            UserActionPolicyPort actionPolicy) {
        this.publicDirectory = Path.of(publicDirectory).toAbsolutePath().normalize();
        this.privateDirectory = Path.of(privateDirectory).toAbsolutePath().normalize();
        this.submissions = submissions; this.users = users; this.moderation = moderation; this.rateLimits = rateLimits; this.actionPolicy = actionPolicy;
    }
    @Transactional
    public UploadResult store(Long userId, MultipartFile file) {
        actionPolicy.assertCanAct(userId);
        byte[] bytes = ImageUploads.validate(file, MAX_SIZE, false);
        rateLimits.checkImageUpload("avatar:" + userId);
        String key = "u" + userId + "-" + UUID.randomUUID() + "." + extension(file.getOriginalFilename());
        Path target = privateDirectory.resolve(key);
        UploadFiles.write(target, bytes);
        var result = moderation.scanImage(target, file.getOriginalFilename());
        // Acquire the lock after the external scan; reviewer decisions use this same lock.
        var user = users.findForProfileUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        // Skipped deep scans require a reviewer; errors also remain private.
        boolean safe = result.isSafe() && !"vision-skipped".equals(result.getModelName());
        if (!safe && !result.isBlocked()) result.setVerdict(ModerationVerdict.REVIEW);
        String status = safe ? "SAFE" : result.isBlocked() ? "BLOCKED" : "REVIEW";
        var submission = submissions.save(AvatarSubmission.builder().userId(userId).storageKey(key)
                .originalName(UploadFiles.displayName(file.getOriginalFilename())).status(status).build());
        Long logId = moderation.persistLog("USER_AVATAR", submission.getId(), userId, "UPLOAD", key, result);
        if (safe) {
            UploadFiles.write(publicDirectory.resolve(key), bytes);
            user.setAvatarUrl(url(submission)); users.save(user);
        } else {
            moderation.enqueueReview(logId, "USER_AVATAR", submission.getId(), userId,
                    "Ảnh đại diện: " + submission.getOriginalName(), result.getSummary());
        }
        return new UploadResult(submission.getId(), safe ? url(submission) : null, status);
    }
    public void assertApproved(Long userId, String url) {
        String prefix = "/uploads/avatar/";
        if (!url.startsWith(prefix) || !submissions.existsByUserIdAndStorageKeyAndStatus(
                userId, url.substring(prefix.length()), "SAFE")) {
            throw new ApiException(ErrorCode.CONTENT_PENDING_REVIEW, "Hãy tải ảnh đại diện lên và chờ kiểm duyệt");
        }
    }
    public Path preview(Long id) { return privateDirectory.resolve(find(id).getStorageKey()); }
    @Override public boolean supports(String type) { return "USER_AVATAR".equals(type); }
    @Override public ModerationTargetSnapshot snapshot(String type, Long id) {
        return submissions.findById(id)
                .map(submission -> new ModerationTargetSnapshot(submission.getOriginalName(),
                        "/api/admin/moderation/avatars/" + id + "/file"))
                .orElseGet(ModerationTargetSnapshot::empty);
    }
    @Override @Transactional
    public void resolve(String type, Long id, boolean approve, String note) {
        var submission = find(id);
        var user = users.findForProfileUpdate(submission.getUserId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (approve) {
            var latest = submissions.findFirstByUserIdOrderByIdDesc(submission.getUserId());
            if (latest.isEmpty() || !id.equals(latest.get().getId())) {
                throw new ApiException(ErrorCode.CONFLICT, "Người dùng đã tải ảnh mới hơn; hãy từ chối mục cũ");
            }
            try { UploadFiles.write(publicDirectory.resolve(submission.getStorageKey()), Files.readAllBytes(preview(id))); }
            catch (IOException e) { throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không đọc được ảnh đại diện"); }
            user.setAvatarUrl(url(submission));
        } else {
            if (url(submission).equals(user.getAvatarUrl())) user.setAvatarUrl(null);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { UploadFiles.delete(publicDirectory.resolve(submission.getStorageKey())); }
            });
        }
        submission.setStatus(approve ? "SAFE" : "BLOCKED");
        submissions.save(submission); users.save(user);
    }
    private AvatarSubmission find(Long id) {
        return submissions.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy ảnh"));
    }
    private String url(AvatarSubmission submission) { return "/uploads/avatar/" + submission.getStorageKey(); }
    public static String extension(String filename) { return UploadFiles.extension(filename); }
    public static boolean hasImageSignature(byte[] bytes, String extension) {
        if (bytes == null || bytes.length < 12) return false;
        if (!"webp".equals(extension)) return ImageUploads.hasSignature(bytes, extension);
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }
}
