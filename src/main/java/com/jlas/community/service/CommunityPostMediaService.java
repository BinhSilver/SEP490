package com.jlas.community.service;

import com.jlas.auth.service.AvatarUploadService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.MediaDto;
import com.jlas.community.entity.CommunityPostMedia;
import com.jlas.community.handler.CommunityPostHandler;
import com.jlas.community.repository.CommunityPostMediaRepository;
import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.service.ContentModerationService;
import com.jlas.moderation.util.ImageUploads;
import com.jlas.moderation.util.UploadFiles;
import com.jlas.shadowing.dto.YoutubeAnalyzeResultDto;
import com.jlas.shadowing.service.DeepgramService;
import com.jlas.shadowing.service.FfmpegService;
import com.jlas.shadowing.service.ShadowingBlobStore;
import com.jlas.shadowing.service.YoutubeAnalyzeService;
import com.jlas.shadowing.util.JapaneseLanguageDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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
 * lưu lên Azure Blob và đọc lại kèm link có chữ ký. Tệp được kiểm tra, kiểm duyệt AI (cùng luật Shadowing, xem
 * {@link #screen}) và tải lên TRƯỚC khi lưu bài nên lỗi tệp / bài bị chặn không tạo bài dở dang.
 */
@Service
@RequiredArgsConstructor
@Slf4j
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
    private final ContentModerationService moderation;
    private final YoutubeAnalyzeService youtube;
    private final FfmpegService ffmpeg;
    private final DeepgramService deepgram;

    /** Một tệp đã lưu lên Blob (hoặc mã YouTube), chờ gắn vào bài. */
    public record Stored(String kind, String storageKey, String contentType, Long sizeBytes) {
    }

    /**
     * Kiểm tra rồi lưu tệp; trả về danh sách chờ gắn vào bài (theo thứ tự: ảnh, rồi video). Không có tệp/link → rỗng.
     * Video: tải lên HOẶC YouTube, không cả hai.
     */
    public List<Stored> prepare(String body, List<MultipartFile> images, MultipartFile video, String youtubeUrl) {
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

        Path videoTemp = null;
        try {
            if (hasVideo) videoTemp = writeTemp(video, videoExt);
            // Kiểm duyệt TRƯỚC khi tải bất kỳ tệp nào lên: bài bị chặn không để lại tệp mồ côi trên Blob.
            screen(body, imageBytes, imageExt, videoTemp, youtubeId);

            List<Stored> stored = new ArrayList<>();
            for (int i = 0; i < imageBytes.size(); i++) {
                String ext = "jpeg".equals(imageExt.get(i)) ? "jpg" : imageExt.get(i);
                String name = blobName(ext);
                upload(name, HttpRequest.BodyPublishers.ofByteArray(imageBytes.get(i)), IMAGE_TYPES.get(ext));
                stored.add(new Stored(CommunityPostMedia.IMAGE, name, IMAGE_TYPES.get(ext), (long) imageBytes.get(i).length));
            }
            if (videoTemp != null) stored.add(storeVideo(videoTemp, videoExt));
            if (youtubeId != null) stored.add(new Stored(CommunityPostMedia.YOUTUBE, youtubeId, null, null));
            return stored;
        } finally {
            deleteQuietly(videoTemp);
        }
    }

    /** Sửa bài: chỉ đổi chữ nên chỉ xét lại chữ (từ cấm + chủ đề tiếng Nhật), kèm loại media bài đang có làm ngữ cảnh. */
    public void screenEditedText(Long postId, String body) {
        List<String> context = new ArrayList<>();
        for (CommunityPostMedia m : media.findByPostIdInOrderByPostIdAscSortOrderAscIdAsc(List.of(postId))) {
            context.add(switch (m.getKind()) {
                case CommunityPostMedia.IMAGE -> "Một ảnh (đã được duyệt khi đăng)";
                case CommunityPostMedia.VIDEO -> "Một video tải lên (đã được duyệt khi đăng)";
                default -> "Một video YouTube (đã được duyệt khi đăng)";
            });
        }
        screenText(body, context);
    }

    // ── Kiểm duyệt nội dung (cùng luật với Shadowing) ─────────────────────────────────────────────────────────────

    /**
     * Luật giống Shadowing: chữ qua bộ lọc từ cấm; link YouTube đọc metadata bằng yt-dlp và chặn video không phải tiếng
     * Nhật (hoặc đã bị gỡ / giới hạn 18+); video tải lên nghe thử lời thoại (Deepgram) rồi nhận diện tiếng Nhật như phụ đề
     * Shadowing, và quét 3 khung hình bằng AI hình ảnh như video Shadowing; ảnh quét bằng AI hình ảnh. Cuối cùng AI xét cả
     * bài (chữ + mô tả media) có liên quan tới tiếng Nhật không. Vi phạm → 400 kèm lý do, bài không được lưu.
     */
    private void screen(String body, List<byte[]> images, List<String> imageExt, Path videoTemp, String youtubeId) {
        List<String> context = new ArrayList<>();
        if (youtubeId != null) context.add(screenYoutube(youtubeId));
        for (int i = 0; i < images.size(); i++) {
            String described = screenImage(images.get(i), imageExt.get(i), "Ảnh " + (i + 1));
            context.add(described);
        }
        if (videoTemp != null) context.addAll(screenVideo(videoTemp));
        screenText(body, context);
    }

    private void screenText(String body, List<String> mediaContext) {
        ModerationResult words = moderation.scanText(body);
        if (words.isBlocked()) {
            throw rejected("Bài viết có từ ngữ không phù hợp với cộng đồng, hãy sửa lại nội dung.");
        }
        if (words.needsReview()) {
            throw rejected("Bài viết có nội dung nhạy cảm hoặc quá nhiều liên kết, hãy sửa lại trước khi đăng.");
        }
        ModerationResult topic = moderation.scanJapaneseTopic(body, mediaContext);
        if (topic.isBlocked()) {
            throw rejected("Cộng đồng chỉ nhận bài liên quan tới tiếng Nhật. " + sentence(topic.getSummary()));
        }
    }

    /** Metadata YouTube như bước "phân tích link" của Shadowing; yt-dlp lỗi / không có → bỏ qua, để AI xét theo chữ. */
    private String screenYoutube(String youtubeId) {
        YoutubeAnalyzeResultDto meta;
        try {
            meta = youtube.analyze("https://www.youtube.com/watch?v=" + youtubeId);
        } catch (IllegalArgumentException e) {
            // Video đã bị gỡ khỏi Shadowing / đang phát trực tiếp / không đọc được: cùng thông báo với Shadowing.
            throw rejected(e.getMessage());
        } catch (Exception e) {
            log.warn("Community YouTube analyze failed id={}: {}", youtubeId, e.getMessage());
            return "Một video YouTube (không đọc được thông tin video)";
        }
        if (meta.isBlockedNonJapanese()) {
            throw rejected(meta.getLanguageMessage() != null ? meta.getLanguageMessage()
                    : "Video YouTube không phải tiếng Nhật. Cộng đồng chỉ nhận nội dung tiếng Nhật.");
        }
        if (meta.getYoutubeMinAge() != null && meta.getYoutubeMinAge() >= 18) {
            throw rejected("Video YouTube bị giới hạn độ tuổi nên không thể đăng lên Cộng đồng.");
        }
        if (StringUtils.hasText(meta.getTitle()) && moderation.scanText(meta.getTitle()).isBlocked()) {
            throw rejected("Tiêu đề video YouTube có nội dung không phù hợp.");
        }
        return "Video YouTube “" + meta.getTitle() + "”"
                + (StringUtils.hasText(meta.getChannel()) ? " của kênh " + meta.getChannel() : "")
                + ", ngôn ngữ: " + meta.getDetectedLanguageLabel();
    }

    /** Một ảnh: AI hình ảnh như ảnh/khung hình Shadowing; BLOCKED/REVIEW → chặn. Trả về dòng mô tả cho bước xét chủ đề. */
    private String screenImage(byte[] bytes, String ext, String label) {
        Path temp = null;
        try {
            temp = Files.createTempFile("community-image-", "." + ext);
            Files.write(temp, bytes);
            return label + ": " + reviewFrame(temp, label, "Ảnh");
        } catch (IOException e) {
            log.warn("Community image temp failed: {}", e.getMessage());
            return label;
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * Video tải lên: 3 khung hình (10% / 50% / 90%) qua AI hình ảnh như quick-scan của Shadowing, và lời thoại 60 giây
     * đầu qua Deepgram + {@link JapaneseLanguageDetector} như phụ đề Shadowing. Không có tiếng / STT lỗi → bỏ qua phần
     * lời thoại (AI vẫn xét chủ đề qua khung hình và chữ).
     */
    private List<String> screenVideo(Path videoTemp) {
        List<String> context = new ArrayList<>();
        String path = videoTemp.toString();
        double duration = 0;
        try {
            Double d = ffmpeg.getDuration(path);
            duration = d != null ? d : 0;
        } catch (Exception e) {
            log.debug("Community video duration failed: {}", e.getMessage());
        }
        List<Double> times = duration > 0.5 ? List.of(duration * 0.1, duration * 0.5, duration * 0.9) : List.of(0.0);
        int index = 0;
        for (double time : times) {
            index++;
            Path frame = null;
            try {
                frame = Files.createTempFile("community-frame-", ".jpg");
                ffmpeg.extractFrameAt(path, time, frame.toString(), 768);
                context.add("Khung hình video " + index + ": " + reviewFrame(frame, "Khung hình " + index, "Video"));
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Community video frame @{}s failed: {}", time, e.getMessage());
            } finally {
                deleteQuietly(frame);
            }
        }

        Path wav = null;
        try {
            wav = Files.createTempFile("community-audio-", ".wav");
            ffmpeg.extractSegmentToWav(path, 0, 60, wav.toString());
            String transcript = deepgram.transcribeWavFile(wav.toFile(), 0).getTranscript();
            JapaneseLanguageDetector.Result language = JapaneseLanguageDetector.inspect(transcript);
            if (language.nonJapanese()) {
                throw rejected("Lời thoại trong video không được nhận diện là tiếng Nhật. " + language.reason()
                        + " Cộng đồng chỉ nhận nội dung tiếng Nhật.");
            }
            if (StringUtils.hasText(transcript)) {
                context.add("Lời thoại video (60 giây đầu): " + CommunityPostHandler.cut(transcript.strip(), 300));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.info("Community video speech check skipped: {}", e.getMessage());
        } finally {
            deleteQuietly(wav);
        }
        return context;
    }

    /** Quét một ảnh/khung hình; chặn khi BLOCKED hoặc REVIEW (giống Shadowing không công khai nội dung cần xem lại). */
    private String reviewFrame(Path image, String label, String what) {
        ContentModerationService.CommunityImageReview review =
                moderation.reviewCommunityImage(image, image.getFileName().toString());
        ModerationResult result = review.result();
        if (result.isBlocked() || result.needsReview()) {
            throw rejected(what + " đính kèm có thể chứa nội dung không phù hợp (" + label.toLowerCase(Locale.ROOT)
                    + "). Hãy chọn " + what.toLowerCase(Locale.ROOT) + " khác.");
        }
        return review.description() != null ? review.description() : "(không có mô tả)";
    }

    private static ApiException rejected(String message) {
        return new ApiException(ErrorCode.BAD_REQUEST, message);
    }

    private static String sentence(String text) {
        if (!StringUtils.hasText(text)) return "";
        String value = text.strip();
        return value.endsWith(".") || value.endsWith("!") || value.endsWith("?") ? value : value + ".";
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // tệp tạm của hệ điều hành
        }
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

    /** Ghi video ra tệp tạm (để kiểm duyệt rồi gửi thẳng từ đĩa lên Blob, không giữ cả tệp trong RAM). */
    private static Path writeTemp(MultipartFile video, String ext) {
        Path temp = null;
        try {
            temp = Files.createTempFile("community-video-", "." + ext);
            video.transferTo(temp);
            return temp;
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không lưu được video, hãy thử lại");
        }
    }

    private Stored storeVideo(Path temp, String ext) {
        try {
            String name = blobName(ext);
            upload(name, HttpRequest.BodyPublishers.ofFile(temp), VIDEO_TYPES.get(ext));
            return new Stored(CommunityPostMedia.VIDEO, name, VIDEO_TYPES.get(ext), Files.size(temp));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không lưu được video, hãy thử lại");
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
