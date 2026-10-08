package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Request sắp xếp lại thứ tự: gửi TOÀN BỘ danh sách id theo thứ tự mong muốn, không gửi từng cặp hoán đổi.
 * Dùng chung cho unit trong một giáo trình và lesson trong một unit.
 * <p>
 * Phải gửi cả danh sách vì order_index có UNIQUE (uk_unit_order, uk_lesson_unit_order): đổi chỗ hai bản ghi
 * bằng hai câu UPDATE thường sẽ vi phạm ràng buộc ngay ở câu đầu, nên service đánh số lại cả danh sách
 * trong một transaction.
 */
@Data
public class ReorderRequest {

    @NotEmpty(message = "Danh sách thứ tự không được để trống")
    private List<Long> orderedIds;
}
