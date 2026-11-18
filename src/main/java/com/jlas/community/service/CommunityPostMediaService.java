package com.jlas.community.service;

import com.jlas.auth.service.AvatarUploadService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.MediaDto;
import com.jlas.community.entity.CommunityPostMedia;
import com.jlas.community.repository.CommunityPostMediaRepository;
import com.jlas.moderation.util.ImageUploads;
import com.jlas.moderation.util.UploadFiles;
import com.jlas.shadowing.service.ShadowingBlobStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ảnh / video đính kèm bài viết Cộng đồng: kiểm tra (số lượng, định dạng, dung lượng, link YouTube), thu nhỏ ảnh,
 * lưu lên Azure Blob và đọc lại kèm link có chữ ký. Tệp được kiểm tra và tải lên TRƯỚC khi lưu bài nên lỗi tệp không
 * tạo bài dở dang; nội dung xấu xử lý qua báo cáo + kiểm duyệt viên gỡ bài như mọi bài viết.
 */
@Service
@RequiredArgsConstructor
public class CommunityPostMediaService {
    public static final int MAX_IMAGES = 4;
    public static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;
    public static final long MAX_VIDEO_SIZE = 50L * 1024 * 1024;
    /** Ảnh hiển thị tối đa ~800px trong feed; 1600px vẫn nét trên màn 2x. */
    static final int IMAGE_MAX_EDGE = 1600;
    static final Map<String, String> VIDEO_TYPES = Map.of("mp4", "video/mp4", "webm", "video/webm");
    private static final Map<String, String> IMAGE_TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg",
            "png", "image/png", "webp", "image/webp");
    private static final Pattern YOUTUBE = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{11})(?:[?&#/].*)?$");

    private final CommunityPostMediaRepository media;
    private final ShadowingBlobStore blobs;

    /** Một tệp đã lưu lên Blob (hoặc mã YouTube), chờ gắn vào bài. */
    public record Stored(String kind, String storageKey, String contentType, Long sizeBytes) {
    }

    /**
     * Kiểm tra rồi lưu tệp; trả về danh sách chờ gắn vào bài (theo thứ tự: ảnh, rồi video). Không có tệp/link → rỗng.
     * Video: tải lên HOẶC YouTube, không cả hai.
     */
    public List<Stored> prepare(List<MultipartFile> images, MultipartFile video, String youtubeUrl) {
        List<MultipartFile> files = images == null ? List.of()
                : images.stream().filter(f -> f != null && !f.isEmpty()).toList();
        boolean hasVideo = video != null && !video.isEmpty();
        String youtubeId = youtubeUrl == null || youtubeUrl.isBlank() ? null : youtubeId(youtubeUrl);
        if (youtubeUrl != null && !youtubeUrl.isBlank() && youtubeId == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Link YouTube không hợp lệ");
        }
        if (files.size() > MAX_IMAGES) throw new ApiException(ErrorCode.BAD_REQUEST, "Mỗi bài tối đa " + MAX_IMAGES + " ảnh");
        if (hasVideo && youtubeId != null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Mỗi bài chỉ đính kèm một video: tải lên hoặc link YouTube");
        }
        // Kiểm tra hết trước khi tải lên bất kỳ tệp nào.
        List<byte[]> imageBytes = new ArrayList<>();
        List<String> imageExt = new ArrayList<>();
        for (MultipartFile file : files) {
            byte[] bytes = ImageUploads.validate(file, MAX_IMAGE_SIZE, false);
            String ext = UploadFiles.extension(file.getOriginalFilename());
            AvatarUploadService.Shrunk shrunk = AvatarUploadService.shrink(bytes, ext, IMAGE_MAX_EDGE);
            imageBytes.add(shrunk.bytes());
            imageExt.add(shrunk.extension());
        }
        String videoExt = hasVideo ? validateVideo(video) : null;
        if ((!files.isEmpty() || hasVideo) && !blobs.configured()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Máy chủ chưa cấu hình nơi lưu ảnh/video, hãy thử lại sau");
        }

        List<Stored> stored = new ArrayList<>();
        for (int i = 0; i < imageBytes.size(); i++) {
            String ext = "jpeg".equals(imageExt.get(i)) ? "jpg" : imageExt.get(i);
            String name = blobName(ext);
            upload(name, HttpRequest.BodyPublishers.ofByteArray(imageBytes.get(i)), IMAGE_TYPES.get(ext));
            stored.add(new Stored(CommunityPostMedia.IMAGE, name, IMAGE_TYPES.get(ext), (long) imageBytes.get(i).length));
        }
        if (hasVideo) stored.add(storeVideo(video, videoExt));
        if (youtubeId != null) stored.add(new Stored(CommunityPostMedia.YOUTUBE, youtubeId, null, null));
        return stored;
    }

    /** Gắn media đã lưu vào bài (gọi trong transaction tạo bài). */
    public void attach(Long postId, List<Stored> stored) {
        int order = 0;
        for (Stored s : stored) {
            media.save(CommunityPostMedia.builder().postId(postId).kind(s.kind()).storageKey(s.storageKey())
                    .contentType(s.contentType()).sizeBytes(s.sizeBytes()).sortOrder(order++).build());
        }
    }

    /** Media của nhiều bài (một truy vấn cho cả trang feed); bài không có media không có trong map. */
    public Map<Long, List<MediaDto>> forPosts(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Map.of();
        Map<Long, List<MediaDto>> result = new HashMap<>();
        for (CommunityPostMedia m : media.findByPostIdInOrderByPostIdAscSortOrderAscIdAsc(postIds)) {
            MediaDto dto = toDto(m);
            if (dto != null) result.computeIfAbsent(m.getPostId(), k -> new ArrayList<>()).add(dto);
        }
        return result;
    }

    private MediaDto toDto(CommunityPostMedia m) {
        if (CommunityPostMedia.YOUTUBE.equals(m.getKind())) {
            return new MediaDto(m.getKind(), "https://www.youtube-nocookie.com/embed/" + m.getStorageKey(), null,
                    m.getStorageKey());
        }
        // Chưa cấu hình Blob ở máy này (vd. local) → bỏ qua tệp thay vì trả link hỏng.
        return blobs.signedMediaUrl(m.getStorageKey()).map(URI::toString)
                .map(url -> new MediaDto(m.getKind(), url, m.getContentType(), null)).orElse(null);
    }

    /** Mã video 11 ký tự từ link youtube.com/watch?v=, youtu.be/, /shorts/, /embed/, /live/; không khớp → null. */
    static String youtubeId(String url) {
        Matcher m = YOUTUBE.matcher(url.trim());
        return m.matches() ? m.group(1) : null;
    }

    /** MP4 (hộp "ftyp" ở byte 4-7) hoặc WebM (chữ ký EBML 1A45DFA3), ≤ 50MB, MIME khớp đuôi. Trả về đuôi tệp. */
    static String validateVideo(MultipartFile video) {
        String ext = UploadFiles.extension(video.getOriginalFilename());
        String mime = video.getContentType() == null ? "" : video.getContentType().toLowerCase(Locale.ROOT);
        if (!VIDEO_TYPES.containsKey(ext) || !mime.equals(VIDEO_TYPES.get(ext))) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Video chỉ nhận MP4 hoặc WebM");
        }
        if (video.getSize() > MAX_VIDEO_SIZE) throw new ApiException(ErrorCode.BAD_REQUEST, "Video tối đa 50MB");
        byte[] head = new byte[12];
        try (InputStream in = video.getInputStream()) {
            if (in.readNBytes(head, 0, head.length) < head.length) throw new IOException("short");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không đọc được tệp video");
        }
        boolean ok = "mp4".equals(ext)
                ? head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p'
                : (head[0] & 255) == 0x1A && (head[1] & 255) == 0x45 && (head[2] & 255) == 0xDF && (head[3] & 255) == 0xA3;
        if (!ok) throw new ApiException(ErrorCode.BAD_REQUEST, "Nội dung tệp video không hợp lệ");
        return ext;
    }

    /** Ghi video ra tệp tạm rồi gửi thẳng từ đĩa lên Blob (không giữ cả tệp trong RAM). */
    private Stored storeVideo(MultipartFile video, String ext) {
        Path temp = null;
        try {
            temp = Files.createTempFile("community-video-", "." + ext);
            video.transferTo(temp);
            String name = blobName(ext);
            upload(name, HttpRequest.BodyPublishers.ofFile(temp), VIDEO_TYPES.get(ext));
            return new Stored(CommunityPostMedia.VIDEO, name, VIDEO_TYPES.get(ext), Files.size(temp));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không lưu được video, hãy thử lại");
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* tệp tạm của hệ điều hành */ }
        }
    }

    private void upload(String name, HttpRequest.BodyPublisher body, String contentType) {
        try {
            blobs.upload(name, body, contentType);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không lưu được ảnh/video, hãy thử lại");
        }
    }

    /** Tên blob duy nhất (không ghi đè, cache dài được): community/yyyy/MM/<uuid>.<đuôi>. */
    private static String blobName(String ext) {
        LocalDate today = LocalDate.now();
        return String.format("community/%d/%02d/%s.%s", today.getYear(), today.getMonthValue(), UUID.randomUUID(), ext);
    }
}
