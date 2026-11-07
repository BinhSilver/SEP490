package com.jlas.community.handler;

/**
 * Loại nội dung đã có đánh giá 1-5 sao ở nguồn: Community đọc/ghi qua đây và không lưu community_rating.
 * Quyền (xem, công khai, owner không tự chấm) đã được Community kiểm tra trước khi gọi.
 */
public interface SourceRatingSupport {

    record RatingSnapshot(double ratingAvg, int ratingCount, Integer myRating) {
    }

    RatingSnapshot ratingOf(Long contentId, Long viewerId);

    /** Ghi điểm của người dùng hiện tại (upsert) và trả lại tổng hợp mới. */
    RatingSnapshot rate(Long contentId, int score);

    /** Nguồn có cho người dùng gỡ đánh giá không. */
    default boolean supportsUnrate() {
        return false;
    }
}
