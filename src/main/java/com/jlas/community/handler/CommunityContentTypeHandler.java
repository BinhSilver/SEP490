package com.jlas.community.handler;

import java.util.Optional;

/**
 * Cầu nối giữa Community và một loại nội dung gốc. Thêm loại mới (vd. SHADOWING_VIDEO ở S4-04) chỉ cần
 * thêm một {@code @Component} cài interface này; core Community không phải sửa.
 */
public interface CommunityContentTypeHandler {

    /** Giá trị content_type, vd. {@code CommunityContentType.FLASHCARD_SET}. */
    String contentType();

    default boolean supports(String type) {
        return contentType().equals(type);
    }

    /** Nội dung gốc còn tồn tại (chưa xoá); empty nếu không có. Không lọc quyền xem, service tự kiểm tra. */
    Optional<CommunitySource> resolve(Long contentId);

    default boolean exists(Long contentId) {
        return resolve(contentId).isPresent();
    }

    /**
     * Một câu SELECT (không có ORDER BY/LIMIT) trả các nội dung đang công khai ở nguồn, đúng các cột:
     * content_type, content_id, title, description, owner_id, thumbnail_url, jlpt_level, created_at, link.
     * Feed ghép các câu này bằng UNION ALL rồi LEFT JOIN community_content.
     */
    String feedSelectSql();
}
