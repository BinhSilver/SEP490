package com.jlas.community.handler;

import java.util.Objects;
import java.util.Optional;

/**
 * Cầu nối giữa Community và một loại nội dung gốc. Thêm loại mới chỉ cần thêm một {@code @Component} cài
 * interface này; core Community không phải sửa. Loại nào có sẵn đánh giá/báo cáo riêng ở nguồn thì cài thêm
 * {@link SourceRatingSupport} / {@link SourceReportSupport} để Community dùng chung thay vì lưu bản thứ hai.
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
     * content_type, content_id, title, description, owner_id, thumbnail_url, jlpt_level, created_at, link,
     * ext_rating_avg, ext_rating_count, ext_my_rating. Ba cột ext_* là đánh giá lấy từ nguồn (NULL nếu loại này
     * dùng community_rating); câu SQL được dùng tham số {@code :viewerId}. Feed ghép các câu bằng UNION ALL rồi
     * LEFT JOIN community_content.
     */
    String feedSelectSql();

    /**
     * Quyền xem khi nội dung KHÔNG công khai (nguồn không public hoặc trạng thái cộng đồng không PUBLISHED, trừ
     * REMOVED do core xử lý). Mặc định: owner và MODERATOR/ADMIN.
     */
    default boolean canViewNonPublic(CommunitySource source, Long viewerId, boolean moderator) {
        return moderator || (viewerId != null && Objects.equals(source.ownerId(), viewerId));
    }

    /** Banner riêng của nguồn khi nội dung không công khai; null để dùng thông báo chung của Community. */
    default String sourceNotice(CommunitySource source, boolean owner) {
        return null;
    }
}
