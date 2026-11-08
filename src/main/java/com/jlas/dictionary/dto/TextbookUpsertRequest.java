package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request tạo/sửa giáo trình. Trạng thái KHÔNG nằm ở đây — đổi trạng thái (kể cả lưu trữ)
 * đi qua PATCH /textbooks/{id}/status với {@link UpdateStatusRequest}.
 */
@Data
public class TextbookUpsertRequest {

    @NotBlank(message = "Tên giáo trình không được để trống")
    @Size(max = 255, message = "Tên giáo trình tối đa 255 ký tự")
    private String title;

    private String description;

    /**
     * Cấp độ JLPT, để trống cho giáo trình không theo cấp độ (bảng chữ cái, giáo trình theo chủ đề).
     * Cột jlpt_level là VARCHAR(10) và không có CHECK trong DB; giới hạn N1..N5 ở đây để dữ liệu sạch.
     */
    @Pattern(regexp = "^(N[1-5])?$", message = "Cấp độ chỉ nhận N1, N2, N3, N4 hoặc N5")
    private String jlptLevel;

    /** Chữ Hán trên thumbnail tự sinh; để trống = mặc định 日. Ảnh thumbnail đi qua API upload riêng. */
    @Size(max = 4, message = "Chữ Hán trên thumbnail tối đa 4 ký tự")
    private String thumbnailKanji;

    /** Màu nền thumbnail tự sinh, ghi đè màu tính theo tên; để trống = tự chọn. */
    @Pattern(regexp = "^(#[0-9A-Fa-f]{6})?$", message = "Màu chủ đạo phải có dạng #RRGGBB")
    private String themeColor;
}
