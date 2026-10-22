package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Kết quả công bố / ẩn hàng loạt (chỉ trả khi cả lô thành công — lỗi thì cả lô không đổi gì). */
@Data
@Builder
public class BulkStatusResponse {

    private String kind;
    /** Trạng thái đích: PUBLISHED / DRAFT. */
    private String status;
    /** Số id gửi lên (đã bỏ trùng). */
    private int requested;
    /** Số mục đã đổi trạng thái. */
    private int updated;
    /** Số mục vốn đã ở trạng thái đích (không cần đổi). */
    private int unchanged;
    /** Id đã xử lý (đổi + giữ nguyên), để giao diện cập nhật danh sách không cần tải lại. */
    private List<Long> ids;
}
