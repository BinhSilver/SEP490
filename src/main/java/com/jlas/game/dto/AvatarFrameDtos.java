package com.jlas.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
}
