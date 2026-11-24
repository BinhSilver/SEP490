package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.PageResult;
import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.enums.CommunityStatus;
import com.jlas.community.enums.ReportReason;
import com.jlas.community.enums.ReportStatus;
import com.jlas.community.enums.ReportTargetType;
import com.jlas.community.repository.CommunityReportRepository;
import com.jlas.moderation.report.ReportDecision;
import com.jlas.moderation.report.ReportedTarget;
import com.jlas.moderation.service.ModerationAuditService;
import com.jlas.moderation.service.ModerationNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Trang "Báo cáo cộng đồng" của người biên soạn (content admin): mọi báo cáo bài viết / nội dung chia sẻ / bình luận,
 * kể cả khi mới có 1 báo cáo (hàng chờ /admin/moderation/reports của kiểm duyệt viên chỉ nhận nội dung đã tạm ẩn).
 * Báo cáo được gom theo đối tượng. Gỡ = báo cáo đúng (REMOVED, báo cáo RESOLVED, báo tác giả); giữ lại = báo cáo sai
 * (PUBLISHED, báo cáo REJECTED, nội dung đang tạm ẩn thì hiện lại và báo tác giả). Báo cáo video Shadowing đi theo
 * luồng riêng của Shadowing nên không có ở đây.
 */
@Service
@RequiredArgsConstructor
public class CommunityReportAdminService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;
    static final String ACTION_DISMISS = "REPORT_INVALID";

    private final CommunityReportedContentPort port;
    private final CommunityModerationService moderation;
    private final CommunityReportRepository reports;
    private final CommunityAccessService access;
    private final ModerationAuditService audit;
    private final ModerationNotifier notifier;
    private final NamedParameterJdbcTemplate jdbc;

    /** Một lượt báo cáo (không nêu người báo cáo). */
    public record ReportLine(String reason, String note, LocalDateTime createdAt) {
    }

    /**
     * Một đối tượng bị báo cáo. {@code state}: PUBLISHED / TEMPORARILY_HIDDEN / REMOVED / DELETED (tác giả đã xoá).
     * {@code outcome} (chỉ ở tab đã xử lý): RESOLVED = đã gỡ, REJECTED = báo cáo sai, giữ nội dung,
     * AUTHOR_DELETED = tự đóng vì tác giả đã xoá.
     */
    public record ReportItem(String targetType, Long targetId, String kindKey, String kind, String state, Long ownerId,
                             String ownerName, String preview, String targetUrl, int reportCount,
                             List<ReportLine> reports, LocalDateTime firstReportedAt, LocalDateTime lastReportedAt,
                             String outcome, String resolutionNote, String resolvedByName, LocalDateTime resolvedAt) {
    }

    /** Một tab loại: {@code key} = COMMENT hoặc loại nội dung (POST, FLASHCARD_SET…), kèm số đối tượng trong tab. */
    public record KindCount(String key, String label, long count) {
    }

    /** Ghi chú khi báo cáo tự đóng vì tác giả đã xoá nội dung (resolved_by = null). */
    static final String AUTHOR_DELETED_NOTE = "Tự đóng: tác giả đã xoá nội dung";
    private static final String COMMENT_KIND = "COMMENT";
    /** Thứ tự tab: bài viết, bình luận, rồi các loại nội dung chia sẻ. */
    private static final List<String> KIND_ORDER = List.of(CommunityContentType.POST, COMMENT_KIND,
            CommunityContentType.FLASHCARD_SET, CommunityContentType.SHADOWING_VIDEO);
    /** Báo cáo + loại đối tượng: COMMENT cho bình luận, content_type của community_content cho nội dung. */
    private static final String REPORTS = "SELECT r.*, CASE WHEN r.target_type = 'COMMENT' THEN 'COMMENT' "
            + "ELSE cc.content_type END AS kind FROM community_report r LEFT JOIN community_content cc "
            + "ON r.target_type = 'CONTENT' AND cc.id = r.target_id";

    /** Số đối tượng theo từng loại ở trạng thái {@code status} (OPEN / CLOSED), cho hàng tab. */
    @Transactional
    public List<KindCount> kinds(String status) {
        boolean open = open(status);
        if (open) closeAuthorDeleted();
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("SELECT kind, COUNT(*) AS cnt FROM (SELECT kind FROM (" + REPORTS + ") x WHERE " + statusWhere(open)
                        + " GROUP BY " + groupColumns(open) + ") g GROUP BY kind", new MapSqlParameterSource(),
                rs -> {
                    counts.put(rs.getString("kind"), rs.getLong("cnt"));
                });
        List<KindCount> result = new ArrayList<>();
        for (String kind : KIND_ORDER) {
            if (counts.containsKey(kind)) result.add(new KindCount(kind, kindLabel(kind), counts.remove(kind)));
        }
        counts.forEach((kind, count) -> result.add(new KindCount(kind, kindLabel(kind), count)));
        return result;
    }

    /**
     * {@code status}: OPEN (mặc định, chờ xử lý) hoặc CLOSED (đã xử lý, mới nhất trước). {@code kind} (tuỳ chọn): một
     * key của {@link #kinds}. Mở tab chờ xử lý cũng tự đóng báo cáo của bài viết / bình luận tác giả đã xoá.
     */
    @Transactional
    public PageResult<ReportItem> list(String status, String kind, Integer page, Integer size) {
        boolean open = open(status);
        if (open) closeAuthorDeleted();
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", s).addValue("offset", (long) p * s);
        String where = statusWhere(open);
        if (kind != null && !kind.isBlank()) {
            where += " AND kind = :kind";
            params.addValue("kind", kind.trim().toUpperCase(Locale.ROOT));
        }
        String group = "SELECT target_type, target_id, kind, " + (open
                ? "NULL AS status, NULL AS resolved_by, NULL AS resolved_at, NULL AS note, "
                : "status, resolved_by, resolved_at, MAX(resolution_note) AS note, ")
                + "COUNT(*) AS cnt, MIN(created_at) AS first_at, MAX(created_at) AS last_at FROM (" + REPORTS + ") x WHERE "
                + where + " GROUP BY " + groupColumns(open);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + group + ") g", params, Long.class);
        long totalElements = total == null ? 0 : total;
        String order = open ? " ORDER BY cnt DESC, last_at DESC" : " ORDER BY resolved_at DESC, last_at DESC";
        List<ReportItem> items = jdbc.query(group + order + " LIMIT :limit OFFSET :offset", params,
                (rs, i) -> toItem(rs.getString("target_type"), rs.getLong("target_id"), rs.getString("kind"),
                        rs.getString("status"),
                        rs.getObject("resolved_by", Long.class), time(rs.getTimestamp("resolved_at")),
                        rs.getString("note"), rs.getInt("cnt"), time(rs.getTimestamp("first_at")),
                        time(rs.getTimestamp("last_at"))));
        return new PageResult<>(items, p, s, totalElements, (int) Math.ceil((double) totalElements / s));
    }

    /**
     * Báo cáo còn mở của bài viết / bình luận mà tác giả đã tự xoá (xoá mềm) không còn gì để xử lý: đóng RESOLVED,
     * resolved_by = null, ghi chú {@link #AUTHOR_DELETED_NOTE}. Trả về số báo cáo đã đóng.
     */
    @Transactional
    public int closeAuthorDeleted() {
        return jdbc.update("UPDATE community_report SET status = 'RESOLVED', resolved_at = :now, resolution_note = :note "
                + "WHERE status = 'OPEN' AND ((target_type = 'COMMENT' AND target_id IN "
                + "(SELECT id FROM community_comment WHERE deleted_at IS NOT NULL)) OR (target_type = 'CONTENT' AND "
                + "target_id IN (SELECT cc.id FROM community_content cc JOIN community_post p "
                + "ON cc.content_type = 'POST' AND p.id = cc.content_id WHERE p.deleted_at IS NOT NULL)))",
                new MapSqlParameterSource().addValue("now", Timestamp.valueOf(LocalDateTime.now()))
                        .addValue("note", AUTHOR_DELETED_NOTE));
    }

    private static boolean open(String status) {
        boolean open = status == null || status.isBlank() || "OPEN".equalsIgnoreCase(status.trim());
        if (!open && !"CLOSED".equalsIgnoreCase(status.trim())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Trạng thái báo cáo không hợp lệ");
        }
        return open;
    }

    private static String statusWhere(boolean open) {
        return open ? "status = 'OPEN'" : "status IN ('RESOLVED', 'REJECTED')";
    }

    /** Chờ xử lý: một dòng mỗi đối tượng; đã xử lý: một dòng mỗi lần xử lý. */
    private static String groupColumns(boolean open) {
        return open ? "target_type, target_id, kind" : "target_type, target_id, kind, status, resolved_by, resolved_at";
    }

    static String kindLabel(String kind) {
        return COMMENT_KIND.equals(kind) ? "Bình luận" : kind == null ? "Khác" : CommunityContentType.label(kind);
    }

    /** Báo cáo đúng: gỡ nội dung / bình luận (cùng luồng với nút "Gỡ" trong Cộng đồng). */
    @Transactional
    public void remove(String targetType, Long targetId, Long actorId, String reason) {
        if (CommunityReportedContentPort.COMMENT.equals(targetType)) {
            moderation.removeComment(targetId, actorId, reason);
        } else if (CommunityReportedContentPort.CONTENT.equals(targetType)) {
            moderation.removeContent(targetId, actorId, reason);
        } else {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Loại nội dung không hợp lệ");
        }
    }

    /** Báo cáo sai: giữ nội dung, đóng các báo cáo đang mở; nội dung đang tạm ẩn thì hiện lại và báo tác giả. */
    @Transactional
    public void dismiss(String targetType, Long targetId, Long actorId, String reason) {
        if (!CommunityAccessService.isModerator()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không có quyền xử lý báo cáo");
        }
        String why = CommunityModerationService.reason(reason);
        if (!port.supports(targetType)) throw new ApiException(ErrorCode.BAD_REQUEST, "Loại nội dung không hợp lệ");
        ReportedTarget target = port.find(targetType, targetId).orElseThrow(CommunityAccessService::notFound);
        if (CommunityStatus.REMOVED.name().equals(target.state())) {
            throw new ApiException(ErrorCode.CONFLICT, "Nội dung đã bị gỡ");
        }
        if (reports.countByTargetTypeAndTargetIdAndStatus(reportType(targetType).name(), targetId,
                ReportStatus.OPEN.name()) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Không còn báo cáo nào chờ xử lý cho nội dung này");
        }
        String published = CommunityStatus.PUBLISHED.name();
        port.resolve(targetType, targetId, new ReportDecision(false, published, actorId, why, LocalDateTime.now()));
        audit.record(actorId, new ModerationAuditService.Entry(ACTION_DISMISS, targetType, targetId, target.state(),
                published, why, null));
        if (CommunityStatus.TEMPORARILY_HIDDEN.name().equals(target.state())) {
            notifier.contentRestored(target.ownerUserId(), targetType, targetId, target.targetUrl());
        }
    }

    private ReportItem toItem(String reportType, Long targetId, String kindKey, String status, Long resolvedBy,
                              LocalDateTime resolvedAt, String note, int count, LocalDateTime first,
                              LocalDateTime last) {
        boolean comment = ReportTargetType.COMMENT.name().equals(reportType);
        String type = comment ? CommunityReportedContentPort.COMMENT : CommunityReportedContentPort.CONTENT;
        Optional<ReportedTarget> found = port.find(type, targetId);
        String kind = kindLabel(kindKey);
        String preview = found.map(t -> comment ? t.preview() : stripKind(t.preview())).orElse("(Nội dung đã bị tác giả xoá)");
        String resolvedByName = resolvedBy == null ? null : access.author(resolvedBy).displayName();
        return new ReportItem(type, targetId, kindKey, kind, found.map(ReportedTarget::state).orElse("DELETED"),
                found.map(ReportedTarget::ownerUserId).orElse(null), found.map(ReportedTarget::ownerName).orElse(null),
                preview, found.map(ReportedTarget::targetUrl).orElse(null), count,
                lines(reportType, targetId, status, resolvedAt), first, last, outcome(status, resolvedBy), note,
                resolvedByName, resolvedAt);
    }

    /** RESOLVED không người xử lý = tự đóng vì tác giả đã xoá nội dung. */
    private static String outcome(String status, Long resolvedBy) {
        return ReportStatus.RESOLVED.name().equals(status) && resolvedBy == null ? "AUTHOR_DELETED" : status;
    }

    private List<ReportLine> lines(String reportType, Long targetId, String status, LocalDateTime resolvedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("type", reportType)
                .addValue("id", targetId).addValue("status", status == null ? ReportStatus.OPEN.name() : status);
        String sql = "SELECT reason, note, created_at FROM community_report WHERE target_type = :type "
                + "AND target_id = :id AND status = :status";
        if (resolvedAt != null) {
            sql += " AND resolved_at = :resolvedAt";
            params.addValue("resolvedAt", Timestamp.valueOf(resolvedAt));
        }
        return jdbc.query(sql + " ORDER BY created_at, id", params,
                (rs, i) -> new ReportLine(ReportReason.labelOf(rs.getString("reason")), rs.getString("note"),
                        time(rs.getTimestamp("created_at"))));
    }

    private static ReportTargetType reportType(String targetType) {
        return CommunityReportedContentPort.COMMENT.equals(targetType) ? ReportTargetType.COMMENT : ReportTargetType.CONTENT;
    }

    /** Port ghi preview nội dung dạng "Bài viết: …"; tách nhãn loại ra cột riêng. */
    private static String stripKind(String preview) {
        int colon = preview == null ? -1 : preview.indexOf(':');
        return colon < 0 ? preview : preview.substring(colon + 1).strip();
    }

    private static LocalDateTime time(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
