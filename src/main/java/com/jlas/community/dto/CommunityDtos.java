package com.jlas.community.dto;

import com.jlas.community.entity.CommunityComment;
import com.jlas.community.entity.CommunityPost;
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

    /**
     * Một mục trên feed (bài viết POST hoặc nội dung chia sẻ). Bài viết: {@code description} là toàn bộ nội dung,
     * {@code title} chỉ là đoạn trích. Các cờ can* đã tính theo quyền người xem; {@code recentComments} là vài bình
     * luận mới nhất (cũ → mới) hiện ngay dưới bài, nạp một lần cho cả trang.
     */
    public record FeedItem(String contentType, String contentTypeLabel, Long contentId, String title,
                           String description, String thumbnailUrl, String jlptLevel, String link,
                           AuthorDto author, double ratingAvg, int ratingCount, int commentCount,
                           Integer myRating, LocalDateTime createdAt, String status, boolean mine,
                           boolean edited, boolean canRate, boolean canComment, boolean canReport,
                           boolean canEdit, List<CommentDto> recentComments, boolean canModerate,
                           String moderationNote, List<MediaDto> media) {
    }

    /**
     * Ảnh / video của bài viết. {@code kind}: IMAGE | VIDEO | YOUTUBE. IMAGE/VIDEO: {@code url} là link đọc có chữ ký
     * (cố định trong một giờ); YOUTUBE: {@code url} là link nhúng youtube-nocookie, {@code youtubeId} là mã video.
     */
    public record MediaDto(String kind, String url, String contentType, String youtubeId) {
    }

    /** Kiểm duyệt viên gỡ trực tiếp bài viết / bình luận: lý do bắt buộc, 1-500 ký tự. */
    public record RemoveRequest(
            @NotBlank(message = "Vui lòng nhập lý do gỡ")
            @Size(max = 500, message = "Lý do tối đa 500 ký tự")
            String reason) {
    }

    /** Đăng/sửa bài viết. {@code jlptLevel}: N5..N1, rỗng = Chung. Độ dài kiểm tra lại ở service (sau khi trim). */
    public record PostRequest(
            @NotBlank(message = "Nội dung bài viết không được để trống")
            @Size(max = CommunityPost.MAX_LENGTH, message = "Bài viết tối đa 5000 ký tự")
            String body,
            @Size(max = 10, message = "Cấp độ không hợp lệ")
            String jlptLevel) {
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

    /** {@code canRemove}: người xem là MODERATOR/ADMIN và bình luận chưa bị gỡ (nút "Gỡ" trực tiếp). */
    public record CommentDto(Long id, AuthorDto author, String body, String status, boolean hidden,
                             boolean mine, boolean canEdit, boolean canDelete, boolean canReport,
                             boolean edited, LocalDateTime createdAt, LocalDateTime updatedAt, boolean canRemove) {
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
