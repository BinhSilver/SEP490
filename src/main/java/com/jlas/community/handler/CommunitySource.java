package com.jlas.community.handler;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Tóm tắt nội dung gốc do handler đọc từ bảng nguồn.
 *
 * @param publicAtSource nguồn đang công khai: PUBLIC, không draft, chưa xoá, moderation gốc không ẩn/gỡ
 * @param jlptLevel      N5..N1, null nếu loại nội dung không có cấp độ
 * @param link           đường dẫn tới trang gốc của nội dung
 * @param extra          thông tin riêng của từng loại (vd. số thẻ, thời lượng), trả cho FE
 * @param sourceState    trạng thái kiểm duyệt ở nguồn cho handler tự xét quyền xem; không trả cho FE
 */
public record CommunitySource(String contentType, Long contentId, String title, String description,
                              Long ownerId, String thumbnailUrl, String link, String jlptLevel,
                              LocalDateTime createdAt, boolean publicAtSource, Map<String, Object> extra,
                              String sourceState) {
}
