package com.jlas.dictionary.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Các nét người dùng đã vẽ trên canvas, toạ độ theo pixel canvas (gốc ở góc trên trái). */
@Data
public class HandwritingRequest {

    public static final int MAX_STROKES = 30;
    public static final int MAX_POINTS_PER_STROKE = 500;

    @NotNull(message = "Thiếu chiều rộng canvas")
    @Min(value = 1, message = "Kích thước canvas không hợp lệ")
    @Max(value = 4096, message = "Kích thước canvas không hợp lệ")
    private Integer width;

    @NotNull(message = "Thiếu chiều cao canvas")
    @Min(value = 1, message = "Kích thước canvas không hợp lệ")
    @Max(value = 4096, message = "Kích thước canvas không hợp lệ")
    private Integer height;

    /** Mỗi nét là danh sách điểm [x, y], theo thứ tự người dùng vẽ. */
    @NotEmpty(message = "Chưa có nét vẽ nào")
    @Size(max = MAX_STROKES, message = "Tối đa 30 nét")
    private List<@NotNull @NotEmpty @Size(max = MAX_POINTS_PER_STROKE, message = "Nét vẽ quá dài")
            List<double @NotNull @Size(min = 2, max = 2, message = "Điểm vẽ không hợp lệ") []>> strokes;
}
