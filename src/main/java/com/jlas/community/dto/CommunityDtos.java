package com.jlas.community.dto;

import com.jlas.community.entity.CommunityComment;
import com.jlas.community.enums.ReportReason;
import com.jlas.community.enums.ReportTargetType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** DTO của Community Hub. Thông tin user chỉ gồm id, tên hiển thị, avatar (không email). */
public final class CommunityDtos {
    private CommunityDtos() {
    }

    public record AuthorDto(Long id, String displayName, String avatarUrl) {
    }

    public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    }

    public record FeedItem(String contentType, String contentTypeLabel, Long contentId, String title,
                           String description, String thumbnailUrl, String jlptLevel, String link,
                           AuthorDto author, double ratingAvg, int ratingCount, int commentCount,
                           Integer myRating, LocalDateTime createdAt) {
    }

    /**
     * Chi tiết nội dung. {@code status} là trạng thái cộng đồng; {@code notice} là banner cho owner/moderator
     * khi nội dung không công khai. Các cờ can* đã tính sẵn theo quyền người xem.
     */
    public record ContentDetail(String contentType, String contentTypeLabel, Long contentId, String title,
                                String description, String thumbnailUrl, String jlptLevel, String link,
                                AuthorDto author, LocalDateTime createdAt, Map<String, Object> extra,
                                String status, boolean publicAtSource, double ratingAvg, int ratingCount,
                                int commentCount, Integer myRating, boolean owner, boolean moderator,
                                boolean canRate, boolean canUnrate, boolean canComment, boolean canReport,
                                String notice) {
    }

    public record RatingRequest(
            @NotNull(message = "Vui lòng chọn số sao")
            @Min(value = 1, message = "Điểm đánh giá phải từ 1 đến 5")
            @Max(value = 5, message = "Điểm đánh giá phải từ 1 đến 5")
            Integer score) {
    }

    public record RatingResult(double ratingAvg, int ratingCount, Integer myRating) {
    }

    public record CommentRequest(
            @NotBlank(message = "Nội dung bình luận không được để trống")
            @Size(max = CommunityComment.MAX_LENGTH, message = "Bình luận tối đa 1000 ký tự")
            String body) {
    }

    public record CommentDto(Long id, AuthorDto author, String body, String status, boolean hidden,
                             boolean mine, boolean canEdit, boolean canDelete, boolean canReport,
                             boolean edited, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /**
     * CONTENT: gửi contentType + contentId (nội dung có thể chưa có dòng community_content).
     * COMMENT: gửi targetId là id bình luận.
     */
    public record ReportRequest(
            @NotNull(message = "Thiếu loại đối tượng báo cáo") ReportTargetType targetType,
            Long targetId,
            String contentType,
            Long contentId,
            @NotNull(message = "Vui lòng chọn lý do báo cáo") ReportReason reason,
            @Size(max = 500, message = "Ghi chú tối đa 500 ký tự") String note) {
    }

    /** {@code reportId} null khi báo cáo được chuyển sang luồng báo cáo của nguồn (vd. video Shadowing). */
    public record ReportResult(Long reportId, boolean targetHidden, String message) {
    }

    public record Option(String value, String label) {
    }

    public record Meta(List<Option> contentTypes, List<Option> reasons, List<String> jlptLevels,
                       List<Option> sorts, int maxPageSize) {
    }
}
