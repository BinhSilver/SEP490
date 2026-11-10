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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
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
        byte[] original = ImageUploads.validate(file, MAX_SIZE, false);
        rateLimits.checkImageUpload("avatar:" + userId);
        // Shrink before the scan: the vision model, the stored file and every viewer get the small copy.
        Shrunk image = shrink(original, extension(file.getOriginalFilename()));
        byte[] bytes = image.bytes();
        String key = "u" + userId + "-" + UUID.randomUUID() + "." + image.extension();
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

    /** Avatars show at 150px at most; 512px stays sharp on 2x/3x screens. */
    static final int MAX_EDGE = 512;
    record Shrunk(byte[] bytes, String extension) {}

    /**
     * Scales JPEG/PNG avatars down to MAX_EDGE (PNG with transparency stays PNG, the rest becomes JPEG) and
     * drops their metadata. Keeps the original for WebP (ImageIO cannot read it), small images, JPEGs that
     * rely on an EXIF rotation, and whenever re-encoding fails or would not make the file smaller.
     */
    static Shrunk shrink(byte[] bytes, String extension) {
        Shrunk original = new Shrunk(bytes, extension);
        if ("webp".equals(extension) || hasExifRotation(bytes)) return original;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || Math.max(image.getWidth(), image.getHeight()) <= MAX_EDGE) return original;
            double scale = (double) MAX_EDGE / Math.max(image.getWidth(), image.getHeight());
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            boolean alpha = image.getColorModel().hasAlpha();
            // Halve step by step first: one big bicubic jump from 3000px to 512px looks jagged.
            while (image.getWidth() / 2 >= width && image.getHeight() / 2 >= height) {
                image = scaled(image, image.getWidth() / 2, image.getHeight() / 2, alpha);
            }
            image = scaled(image, width, height, alpha);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (alpha) {
                ImageIO.write(image, "png", out);
            } else {
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.85f);
                try (var output = ImageIO.createImageOutputStream(out)) {
                    writer.setOutput(output);
                    writer.write(null, new IIOImage(image, null, null), param);
                } finally { writer.dispose(); }
            }
            return out.size() > 0 && out.size() < bytes.length
                    ? new Shrunk(out.toByteArray(), alpha ? "png" : "jpg") : original;
        } catch (IOException | RuntimeException e) {
            return original; // validate() already accepted the original, so it is still safe to store
        }
    }

    private static BufferedImage scaled(BufferedImage source, int width, int height, boolean alpha) {
        BufferedImage target = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally { g.dispose(); }
        return target;
    }

    /**
     * True when a JPEG carries an EXIF orientation other than "normal". Browsers rotate such photos but
     * ImageIO does not, so re-encoding them would store a sideways avatar. Unreadable EXIF counts as rotated.
     * ponytail: PNG eXIf orientation is ignored (very rare for avatars); handle it if sideways PNGs show up.
     */
    static boolean hasExifRotation(byte[] b) {
        if (b.length < 4 || (b[0] & 255) != 0xFF || (b[1] & 255) != 0xD8) return false;
        try {
            int i = 2;
            while (i + 4 <= b.length && (b[i] & 255) == 0xFF) {
                int marker = b[i + 1] & 255;
                int length = u16(b, i + 2, false);
                if (marker == 0xDA) return false; // image data starts; no EXIF block before it
                if (marker == 0xE1 && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
                    int tiff = i + 10;
                    boolean little = b[tiff] == 'I';
                    int ifd = tiff + (int) u32(b, tiff + 4, little);
                    int entries = u16(b, ifd, little);
                    for (int k = 0; k < entries; k++) {
                        int entry = ifd + 2 + 12 * k;
                        if (u16(b, entry, little) == 0x0112) {
                            int orientation = u16(b, entry + 8, little);
                            return orientation > 1;
                        }
                    }
                    return false;
                }
                i += 2 + length;
            }
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static int u16(byte[] b, int at, boolean little) {
        return little ? (b[at] & 255) | (b[at + 1] & 255) << 8 : (b[at] & 255) << 8 | (b[at + 1] & 255);
    }

    private static long u32(byte[] b, int at, boolean little) {
        return little ? (long) u16(b, at + 2, true) << 16 | u16(b, at, true) : (long) u16(b, at, false) << 16 | u16(b, at + 2, false);
    }
    public static boolean hasImageSignature(byte[] bytes, String extension) {
        if (bytes == null || bytes.length < 12) return false;
        if (!"webp".equals(extension)) return ImageUploads.hasSignature(bytes, extension);
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }
}
