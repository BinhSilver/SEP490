package com.jlas.dictionary.handwriting;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.repository.KanjiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chỉ mục nét chuẩn trong bộ nhớ cho các Kanji đã có trong bảng {@code kanji}. Không tạo dữ liệu
 * mới: đọc lại các file KanjiVG mà {@code kanji.stroke_svg_uri} đang trỏ tới (dữ liệu đã import ở
 * JLAS2-19; chỉ lấy tên file, nên importer dùng tiền tố URI nào cũng được). Dựng một lần lúc ứng dụng sẵn sàng (hoặc ở request đầu tiên nếu chưa kịp).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KanjiStrokeIndex {

    /** Thư mục static chứa file KanjiVG (khôi phục bằng seed_bootstrap.py); luôn đọc từ đây. */
    private static final String ASSET_DIR = "classpath:/static/assets/kanjivg/kanji/";
    /** Chỉ chấp nhận tên file KanjiVG (vd. 04f5c.svg) nên URI trong DB không thể trỏ ra ngoài thư mục. */
    private static final Pattern SVG_FILE = Pattern.compile("[0-9a-f]{5}(?:-[A-Za-z0-9]+)?\\.svg");

    private final KanjiRepository kanjiRepository;
    private final ResourceLoader resourceLoader;

    private volatile List<StrokeMatcher.Ref> refs;

    @EventListener(ApplicationReadyEvent.class)
    void warmUp() {
        try {
            refs();
        } catch (RuntimeException e) {
            log.warn("Không dựng được chỉ mục nét KanjiVG lúc khởi động; sẽ thử lại ở request đầu tiên", e);
        }
    }

    public List<StrokeMatcher.Ref> refs() {
        List<StrokeMatcher.Ref> loaded = refs;
        if (loaded == null) {
            synchronized (this) {
                loaded = refs;
                if (loaded == null) {
                    loaded = load();
                    refs = loaded;
                }
            }
        }
        return loaded;
    }

    /**
     * Dựng chỉ mục từ mọi Kanji trong DB. Nếu thiếu file SVG (thư mục KanjiVG là dữ liệu được khôi phục
     * riêng, bị gitignore) và chỉ mục rỗng hoặc mất quá nửa thì ném lỗi 503 và KHÔNG cache, để lần gọi sau
     * dựng lại được sau khi sửa dữ liệu, thay vì trả 200 với danh sách rỗng suốt vòng đời process.
     */
    private List<StrokeMatcher.Ref> load() {
        List<StrokeMatcher.Ref> result = new ArrayList<>();
        List<Kanji> all = kanjiRepository.findAll();
        int skipped = 0;
        String firstProblem = null;
        for (Kanji kanji : all) {
            String uri = kanji.getStrokeSvgUri();
            String file = uri == null ? null : uri.substring(uri.lastIndexOf('/') + 1);
            if (file == null || !SVG_FILE.matcher(file).matches()) {
                skipped++;
                if (firstProblem == null) firstProblem = kanji.getCharacter() + ": stroke_svg_uri không hợp lệ (" + uri + ")";
                continue;
            }
            Resource resource = resourceLoader.getResource(ASSET_DIR + file);
            try (var in = resource.getInputStream()) {
                List<List<double[]>> strokes = KanjiVgPath.strokesOf(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (strokes.isEmpty()) throw new IllegalStateException("không có nét nào trong SVG");
                result.add(StrokeMatcher.Ref.of(kanji.getCharacter(), strokes));
            } catch (Exception e) {
                skipped++;
                if (firstProblem == null) firstProblem = kanji.getCharacter() + " (" + uri + "): " + e;
            }
        }
        if (skipped > 0) {
            log.warn("Chỉ mục nét KanjiVG: bỏ qua {}/{} Kanji; lỗi đầu tiên: {}", skipped, all.size(), firstProblem);
        }
        if (result.isEmpty() || result.size() * 2 < all.size()) {
            log.error("Chỉ mục nét KanjiVG không dùng được ({}/{} Kanji). Đã khôi phục thư mục {} chưa? "
                    + "Xem data/dictionary-seed/README.md", result.size(), all.size(), ASSET_DIR);
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Tính năng viết tay tạm thời chưa sẵn sàng.");
        }
        log.info("Đã dựng chỉ mục nét KanjiVG: {} Kanji", result.size());
        return List.copyOf(result);
    }
}
