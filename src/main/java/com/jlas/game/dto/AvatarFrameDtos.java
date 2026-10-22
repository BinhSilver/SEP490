package com.jlas.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.jlas.game.util.AvatarFrameCatalog.Frame;
import jakarta.validation.constraints.Size;

import java.util.List;

/** DTO của khung avatar + màu tên (Cửa hàng và trang Hồ sơ). */
public final class AvatarFrameDtos {

    private AvatarFrameDtos() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FrameView(String code, String name, String description, int price, String image,
                            String nameStyle, String nameStyleLabel, boolean owned, boolean equipped) {
    }

    /** {@code code} null = màu mặc định (luôn có). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NameStyleView(String code, String label, boolean owned, boolean equipped) {
    }

    /** {@code frameCode}/{@code nameStyle} null = không dùng khung / màu tên mặc định. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CosmeticsView(long balance, String frameCode, String nameStyle, List<FrameView> frames,
                                List<NameStyleView> nameStyles) {
    }

    /** Chọn khung và màu tên; null = bỏ khung / dùng màu mặc định. */
    public record EquipRequest(@Size(max = 40) String frameCode, @Size(max = 40) String nameStyle) {
    }

    /**
     * Phần hiển thị của một khung cho avatar-frame.js (API công khai): ảnh, chủ đề hiệu ứng và toạ độ đá chớp sáng
     * ({@code {"big":[[x,y]...],"gems":...,"orbs":...,"drips":...}}, % khung; null với khung admin thêm).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FrameEffect(String code, String image, String theme, @JsonRawValue String gems) {
        public static FrameEffect of(Frame frame) {
            return new FrameEffect(frame.code(), frame.image(), frame.theme(), frame.gemsJson());
        }
    }

    /** Một dòng ở trang admin "Khung avatar": dữ liệu khung + số người đã mua (quyết định được xoá hay chỉ ẩn). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminFrame(long id, String code, String name, String description, int price, String image,
                             String theme, String nameStyle, String nameStyleLabel, @JsonRawValue String gems,
                             int sortOrder, boolean active, boolean deleted, int owners) {
        public static AdminFrame of(Frame frame, int owners) {
            return new AdminFrame(frame.id(), frame.code(), frame.name(), frame.description(), frame.price(),
                    frame.image(), frame.theme(), frame.nameStyle(), frame.nameStyleLabel(), frame.gemsJson(),
                    frame.sortOrder(), frame.active(), frame.deleted(), owners);
        }
    }
}
