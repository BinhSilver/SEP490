package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request tạo/sửa unit. Giáo trình cha nằm trên URL (POST /textbooks/{textbookId}/units)
 * chứ không nằm trong body, nên unit không thể bị chuyển sang giáo trình khác bằng PUT.
 * <p>
 * order_index cũng không có ở đây: tạo mới thì service tự đặt vào cuối, đổi thứ tự đi qua
 * endpoint sắp xếp riêng vì cột này có UNIQUE (textbook_id, order_index).
 */
@Data
public class UnitUpsertRequest {

    @NotBlank(message = "Tên phần không được để trống")
    @Size(max = 255, message = "Tên phần tối đa 255 ký tự")
    private String title;

    private String description;
}
