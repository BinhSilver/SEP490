package com.jlas.config;

import com.jlas.dictionary.repository.ContentAudioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Kiểm tra kho audio giáo trình lúc ứng dụng sẵn sàng. Kho mặc định là data/content-audio/ (không nằm trong git:
 * tải gói audio trên Google Drive của nhóm rồi giải nén vào đó); nếu thiếu hoặc
 * thư mục sai (chạy app ngoài thư mục gốc, CONTENT_AUDIO_PATH cũ còn sót, lồng thêm một lớp thư mục...) thì
 * audio trả 404 mà không có lỗi nào. Lớp này đối chiếu content_audio.audio_url với file trên đĩa và ghi cảnh báo rõ ràng.
 * Chỉ ghi log, không chặn khởi động.
 */
@Slf4j
@Component
public class ContentAudioStartupCheck {

    private static final int SAMPLE_LIMIT = 5;
    /** Số file tối đa đọc thử để phát hiện pointer Git LFS. */
    static final int LFS_SAMPLE_LIMIT = 50;
    private static final long LFS_POINTER_MAX_BYTES = 1024;
    private static final byte[] LFS_POINTER_PREFIX =
            "version https://git-lfs.github.com/spec/v1".getBytes(StandardCharsets.US_ASCII);

    private final ContentAudioRepository contentAudioRepository;
    private final String contentAudioDirectory;
    private final boolean enabled;

    public ContentAudioStartupCheck(ContentAudioRepository contentAudioRepository,
                                    @Value("${content.audio.path:data/content-audio/}") String contentAudioDirectory,
                                    @Value("${content.audio.startup-check:true}") boolean enabled) {
        this.contentAudioRepository = contentAudioRepository;
        this.contentAudioDirectory = contentAudioDirectory;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void checkOnStartup() {
        if (!enabled) return;
        try {
            check();
        } catch (RuntimeException e) {
            log.warn("Không kiểm tra được kho audio giáo trình lúc khởi động", e);
        }
    }

    /** Kết quả kiểm tra; trả về để test được mà không cần đọc log. */
    Result check() {
        Path root = Paths.get(contentAudioDirectory).toAbsolutePath().normalize();
        List<String> urls = contentAudioRepository.findDistinctAudioUrls();
        if (urls.isEmpty()) {
            log.info("Kho audio giáo trình: bảng content_audio trống, bỏ qua kiểm tra ({})", root);
            return new Result(root, 0, List.of(), null, 0);
        }
        if (!Files.isDirectory(root)) {
            log.warn("Kho audio giáo trình KHÔNG tồn tại: {} — {} file audio sẽ trả 404. Audio không nằm trong git: "
                    + "tải gói audio trên Google Drive của nhóm, giải nén vào data/content-audio/, chạy app từ thư mục "
                    + "gốc project và xoá CONTENT_AUDIO_PATH khỏi application-local.yaml nếu có.", root, urls.size());
            return new Result(root, urls.size(), urls.stream().limit(SAMPLE_LIMIT).toList(), null, 0);
        }

        List<String> missing = urls.stream().filter(url -> !exists(root, url)).toList();
        int pointers = countLfsPointers(root, urls.stream().filter(url -> exists(root, url)).toList());
        if (pointers > 0) {
            log.warn("Audio là pointer Git LFS cũ, không phải file thật — tải gói audio trên Google Drive của nhóm "
                    + "và giải nén đè vào data/content-audio/ ({}/{} file kiểm tra mẫu tại {} chỉ là pointer LFS, "
                    + "audio sẽ không phát được).",
                    pointers, Math.min(LFS_SAMPLE_LIMIT, urls.size() - missing.size()), root);
        }
        if (missing.isEmpty()) {
            if (pointers == 0) log.info("Kho audio giáo trình: đủ {} file tại {}", urls.size(), root);
            return new Result(root, 0, List.of(), null, pointers);
        }

        List<String> samples = missing.stream().limit(SAMPLE_LIMIT).toList();
        Path nestedRoot = findNestedRoot(root, missing.getFirst()).orElse(null);
        if (nestedRoot != null) {
            log.warn("Kho audio giáo trình bị lồng thêm một lớp thư mục: file nằm trong {} thay vì {}. "
                    + "Chuyển nội dung lên một cấp hoặc đặt CONTENT_AUDIO_PATH={} — hiện thiếu {}/{} file.",
                    nestedRoot, root, nestedRoot, missing.size(), urls.size());
        } else {
            log.warn("Kho audio giáo trình thiếu {}/{} file tại {} (gói audio trên Google Drive cũ hơn seed, hoặc giải nén chưa đủ). "
                    + "Ví dụ: {}", missing.size(), urls.size(), root, samples);
        }
        return new Result(root, missing.size(), samples, nestedRoot, pointers);
    }

    /**
     * Pull khi chưa cài Git LFS để lại file text ~130 byte thay cho audio; file vẫn "tồn tại" nên kiểm tra
     * trên không bắt được. Chỉ đọc một mẫu rải đều (tối đa LFS_SAMPLE_LIMIT file) để không đọc cả kho lúc khởi động.
     */
    private static int countLfsPointers(Path root, List<String> existing) {
        if (existing.isEmpty()) return 0;
        int sample = Math.min(LFS_SAMPLE_LIMIT, existing.size());
        int count = 0;
        for (int i = 0; i < sample; i++) {
            String url = existing.get((int) ((long) i * existing.size() / sample));
            if (isLfsPointer(root.resolve(url.replaceFirst("^/+", "")).normalize())) count++;
        }
        return count;
    }

    private static boolean isLfsPointer(Path file) {
        try {
            if (Files.size(file) >= LFS_POINTER_MAX_BYTES) return false;
            try (InputStream in = Files.newInputStream(file)) {
                byte[] head = in.readNBytes(LFS_POINTER_PREFIX.length);
                return Arrays.equals(head, LFS_POINTER_PREFIX);
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** audio_url là đường dẫn tương đối; bỏ "/" đầu (nếu có) và không cho trỏ ra ngoài kho. */
    private static boolean exists(Path root, String url) {
        if (url == null || url.isBlank()) return false;
        Path file = root.resolve(url.replaceFirst("^/+", "")).normalize();
        return file.startsWith(root) && Files.isRegularFile(file);
    }

    /** Lỗi giải nén hay gặp: kho/X/vnjpclub/... thay vì kho/vnjpclub/... — tìm thư mục con X chứa đúng file. */
    private static Optional<Path> findNestedRoot(Path root, String sampleUrl) {
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .filter(child -> exists(child, sampleUrl))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    record Result(Path root, int missingCount, List<String> missingSamples, Path nestedRoot, int lfsPointerSamples) {}
}
