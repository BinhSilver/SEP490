package com.jlas.game.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.repository.AvatarFrameCatalogRepository;
import com.jlas.game.util.AvatarFrameCatalog;
import com.jlas.game.util.AvatarFrameCatalog.Frame;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Danh mục khung avatar (bảng avatar_frame). Đọc rất thường xuyên (mỗi avatar trong bình luận, bảng xếp hạng…)
 * nên giữ bản trong bộ nhớ và làm mới sau mỗi lần admin sửa. DB chưa có bảng thì dùng 4 khung gốc.
 *
 * Quy tắc: khung ẩn không bán nữa nhưng người đã mua vẫn đeo được; chỉ xoá được khung chưa ai mua;
 * đổi giá không ảnh hưởng người đã mua (sổ CP đã ghi price_paid lúc mua).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarFrameCatalogService {
    /** Ảnh khung: ảnh gốc trong /images/avatar-frames hoặc ảnh admin tải lên /uploads/avatar-frames. */
    private static final String IMAGE_PATTERN = "/(images|uploads)/avatar-frames/[A-Za-z0-9._-]+\\.(png|webp)";

    private final AvatarFrameCatalogRepository repository;
    private volatile List<Frame> cache;

    public record FrameInput(@NotBlank @Pattern(regexp = "frame-[a-z0-9][a-z0-9-]{1,33}") String code,
                             @NotBlank @Size(max = 100) String name, @Size(max = 500) String description,
                             @Min(0) @Max(1000000) int price, @NotBlank @Size(max = 1024) String image,
                             @NotBlank String theme, @Min(0) @Max(9999) int sortOrder, boolean active) {
    }

    /** Mọi khung chưa xoá (kể cả đã ẩn: người đã mua vẫn đeo). */
    public List<Frame> all() {
        return loaded().stream().filter(frame -> !frame.deleted()).toList();
    }

    /** Cho trang admin: kể cả khung đã xoá. */
    public List<Frame> adminAll() {
        return loaded();
    }

    public Optional<Frame> find(String code) {
        return all().stream().filter(frame -> frame.code().equals(code)).findFirst();
    }

    public Frame require(String code) {
        return find(code).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy khung avatar này"));
    }

    /** Khung chưa xoá mở màu tên này; nhiều khung có thể dùng chung một màu. */
    public List<Frame> unlocking(String nameStyle) {
        return all().stream().filter(frame -> frame.nameStyle().equals(nameStyle)).toList();
    }

    @Transactional
    public Frame create(FrameInput input) {
        Frame frame = validated(input, null);
        try {
            repository.create(frame);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "Mã khung đã tồn tại");
        }
        cache = null;
        return require(frame.code());
    }

    @Transactional
    public Frame update(String code, FrameInput input) {
        Frame current = require(code);
        repository.update(validated(input, current));
        cache = null;
        return require(code);
    }

    /** Chỉ xoá khung chưa ai mua; đã có người mua thì phải ẩn để không lấy mất món người dùng đã trả CP. */
    @Transactional
    public void delete(String code) {
        Frame frame = require(code);
        if (repository.owners(frame.code()) > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Khung đã có người mua nên không xoá được. Hãy ẩn khung thay vì xoá.");
        }
        repository.delete(frame.code());
        cache = null;
    }

    private Frame validated(FrameInput input, Frame current) {
        if (current != null && !current.code().equals(input.code())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Không đổi mã của khung đã tạo");
        }
        String label = AvatarFrameCatalog.THEMES.get(input.theme());
        if (label == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chủ đề hiệu ứng không hợp lệ");
        String image = input.image().trim();
        if (!image.matches(IMAGE_PATTERN)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn ảnh khung có sẵn hoặc tải ảnh PNG lên");
        }
        // Toạ độ đá được đo trên đúng ảnh gốc: đổi ảnh thì bỏ, khung mới vẫn có mọi hiệu ứng khác.
        String gems = current != null && current.image().equals(image) ? current.gemsJson() : null;
        return new Frame(current == null ? 0 : current.id(), input.code(), input.name().trim(),
                input.description() == null ? "" : input.description().trim(), input.price(), image,
                input.theme(), input.theme(), label, gems, input.sortOrder(), input.active(), false);
    }

    private List<Frame> loaded() {
        List<Frame> frames = cache;
        if (frames != null) return frames;
        try {
            frames = List.copyOf(repository.all());
        } catch (DataAccessException ex) {
            log.warn("Chưa đọc được bảng avatar_frame (chạy migration 050?), dùng 4 khung gốc: {}", ex.getMessage());
            return AvatarFrameCatalog.seeds();
        }
        cache = frames;
        return frames;
    }
}
