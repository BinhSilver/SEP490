package com.jlas.community.handler;

import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.enums.ReportReason;
import com.jlas.shadowing.dto.RatingSummaryDto;
import com.jlas.shadowing.repository.ShadowingLibraryCatalog;
import com.jlas.shadowing.service.InteractionService;
import com.jlas.shadowing.service.ShadowingReportService;
import com.jlas.shadowing.util.ShadowingModerationState;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * SHADOWING_VIDEO: đọc bảng video của module Shadowing, không sửa module đó.
 * <ul>
 *   <li>Công khai ở nguồn = đúng {@code VideoService.isPublicReady} / {@code ShadowingLibraryCatalog.Entry.publicReady}.</li>
 *   <li>Đánh giá dùng chung {@code video_user_state.rating} qua {@link InteractionService}.</li>
 *   <li>Báo cáo nội dung chuyển sang {@link ShadowingReportService} (ngưỡng ẩn và hàng chờ admin của Shadowing).</li>
 *   <li>Video không có mô tả; video bị xoá là xoá cứng nên {@link #resolve} trả empty.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ShadowingVideoCommunityHandler implements CommunityContentTypeHandler, SourceRatingSupport,
        SourceReportSupport {

    /**
     * Bản SQL của {@code VideoService.isPublicReady} và {@code ShadowingLibraryCatalog.Entry.publicReady}:
     * READY, visibility PUBLIC hoặc NULL, moderation_status không thuộc REVIEW/BLOCKED/REPORTED/REMOVED.
     * Shadowing đổi điều kiện đó thì phải đổi cùng ở đây (resolve() gọi thẳng Entry.publicReady()).
     */
    private static final String PUBLIC_AT_SOURCE = "v.status = 'READY' "
            + "AND (v.visibility IS NULL OR v.visibility = 'PUBLIC') "
            + "AND (v.moderation_status IS NULL "
            + "OR UPPER(v.moderation_status) NOT IN ('REVIEW','BLOCKED','REPORTED','REMOVED'))";

    /** Cùng thứ tự với ShadowingLibraryCatalog: tag N1-N5, rồi selected_jlpt_level, rồi suggested_jlpt_level. */
    private static final String LEVEL = "COALESCE((SELECT MIN(UPPER(TRIM(t.name))) FROM video_tag vt "
            + "JOIN tag t ON t.id = vt.tag_id WHERE vt.video_id = v.id "
            + "AND UPPER(TRIM(t.name)) IN ('N1','N2','N3','N4','N5')), "
            + "(SELECT COALESCE(NULLIF(p.selected_jlpt_level, ''), p.suggested_jlpt_level) "
            + "FROM shadowing_preparation_job p WHERE p.video_id = v.id))";

    /** thumbnail_url là đường dẫn file trên server; ảnh được phục vụ qua API có sẵn của Shadowing. */
    private static final String THUMBNAIL = "CASE WHEN v.thumbnail_url IS NULL OR v.thumbnail_url = '' THEN NULL "
            + "ELSE CONCAT('/api/videos/', v.id, '/thumbnail') END";

    private static final String LINK = "CONCAT('/shadowing/practice/', v.id)";

    private final NamedParameterJdbcTemplate jdbc;
    private final InteractionService interactions;
    private final ShadowingReportService shadowingReports;

    @Override
    public String contentType() {
        return CommunityContentType.SHADOWING_VIDEO;
    }

    @Override
    public Optional<CommunitySource> resolve(Long contentId) {
        if (contentId == null) return Optional.empty();
        String sql = "SELECT v.id, v.title, v.owner_id, v.status, v.visibility, v.moderation_status, "
                + "v.duration_seconds, v.created_at, " + THUMBNAIL + " AS thumbnail, " + LEVEL + " AS jlpt_level, "
                + LINK + " AS link FROM video v WHERE v.id = :id";
        List<CommunitySource> rows = jdbc.query(sql, new MapSqlParameterSource("id", contentId), (rs, i) -> {
            Timestamp createdTs = rs.getTimestamp("created_at");
            LocalDateTime created = createdTs == null ? null : createdTs.toLocalDateTime();
            Long ownerId = rs.getObject("owner_id") == null ? null : rs.getLong("owner_id");
            String moderation = rs.getString("moderation_status");
            boolean publicReady = new ShadowingLibraryCatalog.Entry(rs.getLong("id"), rs.getString("title"), null,
                    created, ownerId, rs.getString("status"), rs.getString("visibility"), moderation, List.of(),
                    null, null, Set.of()).publicReady();
            Map<String, Object> extra = new HashMap<>();
            Object duration = rs.getObject("duration_seconds");
            if (duration != null) extra.put("durationSeconds", ((Number) duration).doubleValue());
            return new CommunitySource(contentType(), rs.getLong("id"), rs.getString("title"), null, ownerId,
                    rs.getString("thumbnail"), rs.getString("link"), rs.getString("jlpt_level"), created,
                    publicReady, extra, sourceState(rs.getString("status"), rs.getString("visibility"), moderation));
        });
        return rows.stream().findFirst();
    }

    @Override
    public String feedSelectSql() {
        return "SELECT '" + CommunityContentType.SHADOWING_VIDEO + "' AS content_type, v.id AS content_id, "
                + "v.title AS title, NULL AS description, v.owner_id AS owner_id, " + THUMBNAIL + " AS thumbnail_url, "
                + LEVEL + " AS jlpt_level, v.created_at AS created_at, " + LINK + " AS link, "
                + "(SELECT ROUND(AVG(us.rating), 2) FROM video_user_state us "
                + "WHERE us.video_id = v.id AND us.rating BETWEEN 1 AND 5) AS ext_rating_avg, "
                + "(SELECT COUNT(*) FROM video_user_state us "
                + "WHERE us.video_id = v.id AND us.rating BETWEEN 1 AND 5) AS ext_rating_count, "
                + "(SELECT us.rating FROM video_user_state us "
                + "WHERE us.video_id = v.id AND us.user_id = :viewerId) AS ext_my_rating "
                + "FROM video v WHERE " + PUBLIC_AT_SOURCE;
    }

    /**
     * Theo {@code VideoService.requireReadable}: REMOVED chỉ MODERATOR/ADMIN (quy tắc Community), REPORTED owner +
     * MODERATOR/ADMIN, còn lại (PRIVATE, chưa READY, REVIEW/BLOCKED) chỉ owner. Video không có owner chỉ xem được
     * khi công khai.
     */
    @Override
    public boolean canViewNonPublic(CommunitySource source, Long viewerId, boolean moderator) {
        boolean owner = viewerId != null && Objects.equals(source.ownerId(), viewerId);
        String state = source.sourceState();
        if (ShadowingModerationState.REMOVED.equals(state)) return moderator;
        if (ShadowingModerationState.REPORTED.equals(state)) return owner || moderator;
        // Nguồn vẫn công khai nhưng trạng thái cộng đồng bị ẩn: quy tắc chung của Community.
        if (source.publicAtSource()) return owner || moderator;
        return owner;
    }

    @Override
    public String sourceNotice(CommunitySource source, boolean owner) {
        String state = source.sourceState();
        if (ShadowingModerationState.REMOVED.equals(state)) return "Video đã bị gỡ do báo cáo vi phạm";
        if (ShadowingModerationState.REPORTED.equals(state)) {
            return "Video đang tạm ẩn để kiểm duyệt viên xem xét báo cáo";
        }
        if ("REVIEW".equals(state) || "BLOCKED".equals(state)) {
            return "Video chưa qua kiểm duyệt nội dung nên không hiển thị trong cộng đồng";
        }
        if ("NOT_READY".equals(state)) return "Video chưa xử lý xong nên chưa hiển thị trong cộng đồng";
        return null;
    }

    @Override
    public RatingSnapshot ratingOf(Long contentId, Long viewerId) {
        return snapshot(interactions.getRatingSummary(null, contentId));
    }

    @Override
    public RatingSnapshot rate(Long contentId, int score) {
        return snapshot(interactions.saveRating(null, contentId, score));
    }

    @Override
    public boolean report(Long contentId, ReportReason reason, String note) {
        String detail = StringUtils.hasText(note) ? note.trim() : "";
        String code = switch (reason) {
            case COPYRIGHT -> "COPYRIGHT";
            case INAPPROPRIATE -> "INAPPROPRIATE";
            default -> "OTHER";
        };
        // Shadowing chỉ có COPYRIGHT/INAPPROPRIATE/OTHER: lý do khác gửi OTHER, giữ tên lý do trong ghi chú.
        if (reason == ReportReason.SPAM || reason == ReportReason.WRONG_CONTENT) {
            detail = detail.isEmpty() ? reason.label() : reason.label() + ": " + detail;
        }
        return shadowingReports.report(contentId, code, detail);
    }

    private static RatingSnapshot snapshot(RatingSummaryDto dto) {
        return new RatingSnapshot(dto.getAvgRating() == null ? 0 : dto.getAvgRating(), dto.getRatingCount(),
                dto.getUserRating());
    }

    /** REMOVED / REPORTED / REVIEW / BLOCKED / NOT_READY / PRIVATE, null nếu công khai bình thường. */
    private static String sourceState(String status, String visibility, String moderation) {
        String ms = moderation == null ? "" : moderation.toUpperCase(Locale.ROOT);
        if (ShadowingModerationState.REMOVED.equals(ms) || ShadowingModerationState.REPORTED.equals(ms)) return ms;
        if ("REVIEW".equals(ms) || "BLOCKED".equals(ms)) return ms;
        if (!"READY".equals(status)) return "NOT_READY";
        if (visibility != null && !"PUBLIC".equals(visibility)) return "PRIVATE";
        return null;
    }
}
