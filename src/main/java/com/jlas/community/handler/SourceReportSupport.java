package com.jlas.community.handler;

import com.jlas.community.enums.ReportReason;

/**
 * Loại nội dung đã có luồng báo cáo riêng ở nguồn (ngưỡng ẩn, hàng chờ admin): báo cáo NỘI DUNG từ Community
 * chuyển thẳng sang đó, không tạo community_report, để mỗi nội dung chỉ có một mục trong hàng chờ.
 * Báo cáo bình luận cộng đồng vẫn theo luồng Community.
 */
public interface SourceReportSupport {

    /** Gửi báo cáo của người dùng hiện tại; trả true nếu báo cáo này làm nội dung bị ẩn ở nguồn. */
    boolean report(Long contentId, ReportReason reason, String note);

    /** Thông báo cho người báo cáo sau khi gửi (không hứa ẩn ngay). */
    default String reportedMessage() {
        return "Đã gửi báo cáo, nội dung sẽ được xem xét";
    }
}
