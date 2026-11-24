package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.AuthorDto;
import com.jlas.community.dto.CommunityDtos.CommentDto;
import com.jlas.community.dto.CommunityDtos.FeedItem;
import com.jlas.community.dto.CommunityDtos.MediaDto;
import com.jlas.community.dto.CommunityDtos.Meta;
import com.jlas.community.dto.CommunityDtos.Option;
import com.jlas.community.dto.CommunityDtos.PageResult;
import com.jlas.community.entity.CommunityComment;
import com.jlas.community.entity.CommunityContent;
import com.jlas.community.entity.CommunityPost;
import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.enums.CommunityStatus;
import com.jlas.community.enums.ReportReason;
import com.jlas.community.handler.CommunityContentTypeHandler;
import com.jlas.community.handler.CommunitySource;
import com.jlas.community.repository.CommunityPostRepository;
import com.jlas.community.repository.CommunitySavedPostRepository;
import com.jlas.community.service.CommunityAccessService.Target;
import com.jlas.moderation.repository.ModerationAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Feed cộng đồng: UNION ALL các nội dung đang công khai ở nguồn (mỗi handler một nhánh) rồi LEFT JOIN
 * community_content. Không có dòng trạng thái = PUBLISHED, bộ đếm = 0. Chỉ hiện nội dung PUBLISHED.
 */
@Service
@RequiredArgsConstructor
public class CommunityFeedService {
    public static final int DEFAULT_PAGE_SIZE = 12;
    public static final int MAX_PAGE_SIZE = 50;
    static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    /** Số bình luận mới nhất hiện ngay dưới mỗi mục feed. */
    public static final int INLINE_COMMENTS = 2;
    /** Đánh giá lấy ở nguồn (ext_*, vd. video Shadowing) nếu có, không thì từ community_content. */
    private static final String RATING_AVG = "COALESCE(f.ext_rating_avg, cc.rating_avg, 0)";
    private static final String RATING_COUNT = "COALESCE(f.ext_rating_count, cc.rating_count, 0)";

    private final CommunityAccessService access;
    private final CommunityPostRepository posts;
    private final ModerationAuditLogRepository moderationAudit;
    private final NamedParameterJdbcTemplate jdbc;
    private final CommunityPostMediaService postMedia;
    private final CommunitySavedPostRepository saved;

    public enum Sort {
        NEWEST("Mới nhất", "f.created_at DESC, f.content_id DESC"),
        TOP_RATED("Đánh giá cao", "rating_avg DESC, rating_count DESC, f.created_at DESC, f.content_id DESC"),
        MOST_COMMENTED("Nhiều bình luận", "comment_count DESC, f.created_at DESC, f.content_id DESC");

        private final String label;
        private final String orderBy;

        Sort(String label, String orderBy) {
            this.label = label;
            this.orderBy = orderBy;
        }

        /** Nhận newest | topRated | mostCommented (không phân biệt hoa thường, chấp nhận cả top_rated). */
        static Sort parse(String value) {
            if (!StringUtils.hasText(value)) return NEWEST;
            String key = value.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            for (Sort sort : values()) {
                if (sort.name().replace("_", "").toLowerCase(Locale.ROOT).equals(key)) return sort;
            }
            throw new ApiException(ErrorCode.BAD_REQUEST, "Kiểu sắp xếp không hợp lệ: " + value);
        }

        String param() {
            String[] parts = name().toLowerCase(Locale.ROOT).split("_");
            return parts[0] + Arrays.stream(parts).skip(1)
                    .map(p -> Character.toUpperCase(p.charAt(0)) + p.substring(1)).collect(Collectors.joining());
        }
    }

    /**
     * {@code rating} = đúng số sao (1-5): điểm trung bình làm tròn bằng N, tức trong [N-0.5, N+0.5), chỉ bài đã có
     * đánh giá. {@code minRating} (link cũ "4★ trở lên") vẫn nhận: trung bình ≥ giá trị.
     */
    public record Query(String keyword, String contentType, String jlptLevel, Double minRating, Integer rating,
                        boolean saved, String sort, Integer page, Integer size) {
        public Query(String keyword, String contentType, String jlptLevel, Double minRating, String sort,
                     Integer page, Integer size) {
            this(keyword, contentType, jlptLevel, minRating, null, false, sort, page, size);
        }
    }

    @Transactional(readOnly = true)
    public PageResult<FeedItem> search(Query query, Long viewerId) {
        int page = query.page() == null ? 0 : Math.max(0, query.page());
        int size = query.size() == null || query.size() <= 0 ? DEFAULT_PAGE_SIZE
                : Math.min(query.size(), MAX_PAGE_SIZE);
        Sort sort = Sort.parse(query.sort());

        List<CommunityContentTypeHandler> branches = access.handlers();
        if (StringUtils.hasText(query.contentType())) {
            CommunityContentTypeHandler handler = access.handler(query.contentType().trim())
                    .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST, "Loại nội dung không hợp lệ"));
            branches = List.of(handler);
        }
        if (branches.isEmpty()) return new PageResult<>(List.of(), page, size, 0, 0);

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("viewerId", viewerId);
        StringBuilder where = new StringBuilder(" WHERE COALESCE(cc.status, 'PUBLISHED') = 'PUBLISHED'");
        if (StringUtils.hasText(query.keyword())) {
            where.append(" AND (f.title LIKE :kw ESCAPE '!' OR f.description LIKE :kw ESCAPE '!')");
            params.addValue("kw", "%" + escapeLike(query.keyword().trim()) + "%");
        }
        if (StringUtils.hasText(query.jlptLevel())) {
            String level = query.jlptLevel().trim().toUpperCase(Locale.ROOT);
            if (!LEVELS.contains(level)) throw new ApiException(ErrorCode.BAD_REQUEST, "Cấp độ JLPT không hợp lệ");
            // Loại không có cấp độ (jlpt_level NULL) tự bị loại khi đang lọc cấp độ.
            where.append(" AND f.jlpt_level = :level");
            params.addValue("level", level);
        }
        if (query.minRating() != null) {
            if (query.minRating() < 0 || query.minRating() > 5) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Điểm đánh giá tối thiểu phải từ 0 đến 5");
            }
            where.append(" AND ").append(RATING_AVG).append(" >= :minRating");
            params.addValue("minRating", query.minRating());
        }
        if (query.rating() != null) {
            if (query.rating() < 1 || query.rating() > 5) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Số sao phải từ 1 đến 5");
            }
            where.append(" AND ").append(RATING_COUNT).append(" > 0 AND ").append(RATING_AVG)
                    .append(" >= :ratingFrom AND ").append(RATING_AVG).append(" < :ratingTo");
            params.addValue("ratingFrom", query.rating() - 0.5).addValue("ratingTo", query.rating() + 0.5);
        }

        String union = branches.stream().map(h -> "(" + h.feedSelectSql() + ")")
                .collect(Collectors.joining(" UNION ALL "));
        // "Đã lưu": chỉ mục người xem đã lưu (INNER JOIN); còn lại LEFT JOIN để biết cờ saved của từng mục.
        String from = " FROM (" + union + ") f LEFT JOIN community_content cc "
                + "ON cc.content_type = f.content_type AND cc.content_id = f.content_id"
                + (query.saved() ? " JOIN" : " LEFT JOIN") + " community_saved_post sp ON sp.user_id = :viewerId "
                + "AND sp.content_type = f.content_type AND sp.content_id = f.content_id";

        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, params, Long.class);
        long totalElements = total == null ? 0 : total;

        params.addValue("limit", size).addValue("offset", (long) page * size);
        String sql = "SELECT f.content_type, f.content_id, f.title, f.description, f.owner_id, f.thumbnail_url, "
                + "f.jlpt_level, f.created_at, f.link, " + RATING_AVG + " AS rating_avg, "
                + RATING_COUNT + " AS rating_count, COALESCE(cc.comment_count, 0) AS comment_count, "
                + "u.full_name, u.username, u.avatar_url, COALESCE(f.ext_my_rating, my.score) AS my_score, "
                + "cc.id AS community_id, sp.id AS saved_id"
                + from
                + " LEFT JOIN users u ON u.id = f.owner_id"
                + " LEFT JOIN community_rating my ON my.community_content_id = cc.id AND my.user_id = :viewerId"
                + where + " ORDER BY " + (query.saved() && sort == Sort.NEWEST ? "sp.created_at DESC, sp.id DESC"
                : sort.orderBy) + " LIMIT :limit OFFSET :offset";

        List<Row> rows = jdbc.query(sql, params, (rs, i) -> {
            Long ownerId = rs.getObject("owner_id") == null ? null : rs.getLong("owner_id");
            AuthorDto author = ownerId == null ? null : new AuthorDto(ownerId,
                    CommunityAccessService.displayName(ownerId, rs.getString("full_name"), rs.getString("username")),
                    rs.getString("avatar_url"));
            BigDecimal avg = rs.getBigDecimal("rating_avg");
            Timestamp created = rs.getTimestamp("created_at");
            Object myScore = rs.getObject("my_score");
            Object communityId = rs.getObject("community_id");
            return new Row(rs.getString("content_type"), rs.getLong("content_id"), rs.getString("title"),
                    rs.getString("description"), rs.getString("thumbnail_url"), rs.getString("jlpt_level"),
                    rs.getString("link"), ownerId, author, avg == null ? 0 : avg.doubleValue(),
                    rs.getInt("rating_count"), rs.getInt("comment_count"),
                    myScore == null ? null : ((Number) myScore).intValue(),
                    created == null ? null : created.toLocalDateTime(),
                    communityId == null ? null : ((Number) communityId).longValue(), rs.getObject("saved_id") != null);
        });
        // Truy vấn gộp cho cả trang (không N+1): cờ "đã chỉnh sửa" + ảnh/video của bài viết + vài bình luận mới nhất.
        List<Long> postIds = rows.stream()
                .filter(r -> CommunityContentType.POST.equals(r.contentType())).map(Row::contentId).toList();
        Set<Long> editedPosts = editedPosts(postIds);
        Map<Long, List<MediaDto>> media = postMedia.forPosts(postIds);
        Map<Long, List<CommentDto>> recent = recentComments(rows.stream().map(Row::communityId)
                .filter(Objects::nonNull).toList(), viewerId, true);   // feed chỉ có nội dung công khai
        List<FeedItem> items = rows.stream().map(r -> toItem(r, CommunityStatus.PUBLISHED.name(), true,
                CommunityContentType.POST.equals(r.contentType()) && editedPosts.contains(r.contentId()),
                viewerId, r.communityId() == null ? List.of() : recent.getOrDefault(r.communityId(), List.of()),
                media.getOrDefault(r.contentId(), List.of())))
                .toList();
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PageResult<>(items, page, size, totalElements, totalPages);
    }

    /**
     * Một mục feed dựng từ nội dung đã kiểm tra quyền xem (trang bài viết, kết quả đăng/sửa bài). Khác feed: có thể là
     * nội dung đang tạm ẩn (chủ bài / moderator vẫn xem được) nên các thao tác tắt khi không công khai.
     */
    @Transactional(readOnly = true)
    public FeedItem item(Target target, Long viewerId) {
        CommunitySource source = target.source();
        CommunityContent row = target.row();
        boolean edited = source.extra() != null && Boolean.TRUE.equals(source.extra().get("edited"));
        // Cờ thao tác trên bình luận theo đúng trạng thái nội dung (như API danh sách bình luận): bài đang ẩn / đã gỡ thì
        // không hiện "Báo cáo" / "Sửa" trên bình luận.
        List<CommentDto> recent = row == null ? List.of()
                : recentComments(List.of(row.getId()), viewerId, target.publicNow()).getOrDefault(row.getId(), List.of());
        Row r = new Row(source.contentType(), source.contentId(), source.title(), source.description(),
                source.thumbnailUrl(), source.jlptLevel(), source.link(), source.ownerId(),
                access.author(source.ownerId()), row == null ? 0 : row.getRatingAvg().doubleValue(),
                row == null ? 0 : row.getRatingCount(), row == null ? 0 : row.getCommentCount(), null,
                source.createdAt(), row == null ? null : row.getId(), viewerId != null
                && saved.existsByUserIdAndContentTypeAndContentId(viewerId, source.contentType(), source.contentId()));
        List<MediaDto> media = CommunityContentType.POST.equals(source.contentType())
                ? postMedia.forPosts(List.of(source.contentId())).getOrDefault(source.contentId(), List.of()) : List.of();
        FeedItem item = toItem(r, target.status(), target.publicNow(), edited, viewerId, recent, media);
        // Bài đã bị gỡ: chủ bài / kiểm duyệt viên thấy lý do (lần chuyển sang REMOVED gần nhất trong nhật ký kiểm duyệt,
        // gồm cả gỡ trực tiếp lẫn xử lý báo cáo).
        if (row != null && CommunityStatus.REMOVED.name().equals(target.status())) {
            String note = moderationAudit.findFirstByTargetTypeAndTargetIdAndToStateOrderByIdDesc(
                            CommunityReportedContentPort.CONTENT, row.getId(), CommunityStatus.REMOVED.name())
                    .map(a -> a.getReason()).orElse(null);
            item = withNote(item, note);
        }
        return item;
    }

    private static FeedItem withNote(FeedItem i, String note) {
        return new FeedItem(i.contentType(), i.contentTypeLabel(), i.contentId(), i.title(), i.description(),
                i.thumbnailUrl(), i.jlptLevel(), i.link(), i.author(), i.ratingAvg(), i.ratingCount(), i.commentCount(),
                i.myRating(), i.createdAt(), i.status(), i.mine(), i.edited(), i.canRate(), i.canComment(),
                i.canReport(), i.canEdit(), i.recentComments(), i.canModerate(), note, i.media(), i.saved());
    }

    /** Một dòng feed trước khi gắn quyền + bình luận. */
    private record Row(String contentType, Long contentId, String title, String description, String thumbnailUrl,
                       String jlptLevel, String link, Long ownerId, AuthorDto author, double ratingAvg,
                       int ratingCount, int commentCount, Integer myRating, LocalDateTime createdAt,
                       Long communityId, boolean saved) {
    }

    private FeedItem toItem(Row r, String status, boolean active, boolean edited, Long viewerId,
                            List<CommentDto> recent, List<MediaDto> media) {
        boolean mine = viewerId != null && Objects.equals(r.ownerId(), viewerId);
        boolean rateable = access.handler(r.contentType()).map(CommunityContentTypeHandler::supportsRating)
                .orElse(false);
        boolean post = CommunityContentType.POST.equals(r.contentType());
        return new FeedItem(r.contentType(), CommunityContentType.label(r.contentType()), r.contentId(), r.title(),
                r.description(), r.thumbnailUrl(), r.jlptLevel(), r.link(), r.author(), r.ratingAvg(),
                r.ratingCount(), r.commentCount(), r.myRating(), r.createdAt(), status, mine, edited,
                active && rateable && !mine, active, active && !mine,
                post && mine && !CommunityStatus.REMOVED.name().equals(status), recent,
                post && CommunityAccessService.isModerator() && !CommunityStatus.REMOVED.name().equals(status), null, media,
                r.saved());
    }

    private Set<Long> editedPosts(List<Long> postIds) {
        if (postIds.isEmpty()) return Set.of();
        Set<Long> result = new HashSet<>();
        for (CommunityPost post : posts.findByIdIn(postIds)) {
            if (post.getEditedAt() != null) result.add(post.getId());
        }
        return result;
    }

    /**
     * Vài bình luận mới nhất của mỗi nội dung, MỘT truy vấn cho cả trang (ROW_NUMBER theo nội dung). Cùng quy tắc với
     * danh sách bình luận: PUBLISHED, cộng bình luận đang ẩn của chính người xem. Trả cũ → mới như một cuộc trò chuyện.
     */
    private Map<Long, List<CommentDto>> recentComments(Collection<Long> communityIds, Long viewerId,
                                                       boolean contentActive) {
        if (communityIds.isEmpty()) return Map.of();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ids", communityIds)
                .addValue("viewerId", viewerId).addValue("n", INLINE_COMMENTS);
        String sql = "SELECT x.id, x.community_content_id, x.user_id, x.body, x.status, x.report_count, "
                + "x.created_at, x.updated_at FROM (SELECT c.id, c.community_content_id, c.user_id, c.body, "
                + "c.status, c.report_count, c.created_at, c.updated_at, ROW_NUMBER() OVER ("
                + "PARTITION BY c.community_content_id ORDER BY c.created_at DESC, c.id DESC) AS rn "
                + "FROM community_comment c WHERE c.community_content_id IN (:ids) AND c.deleted_at IS NULL "
                + "AND (c.status = 'PUBLISHED' OR (c.status = 'TEMPORARILY_HIDDEN' AND c.user_id = :viewerId))) x "
                + "WHERE x.rn <= :n ORDER BY x.community_content_id, x.created_at ASC, x.id ASC";
        List<CommunityComment> found = jdbc.query(sql, params, (rs, i) -> CommunityComment.builder()
                .id(rs.getLong("id")).communityContentId(rs.getLong("community_content_id"))
                .userId(rs.getLong("user_id")).body(rs.getString("body")).status(rs.getString("status"))
                .reportCount(rs.getInt("report_count"))
                .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                .updatedAt(rs.getTimestamp("updated_at").toLocalDateTime()).build());
        if (found.isEmpty()) return Map.of();
        Map<Long, AuthorDto> authors = access.authors(found.stream().map(CommunityComment::getUserId).toList());
        boolean moderator = CommunityAccessService.isModerator();
        Map<Long, List<CommentDto>> result = new HashMap<>();
        for (CommunityComment c : found) {
            result.computeIfAbsent(c.getCommunityContentId(), k -> new ArrayList<>())
                    .add(CommunityCommentService.toDto(c, authors.get(c.getUserId()), viewerId, moderator, contentActive));
        }
        return result;
    }

    /** Danh sách lựa chọn cho bộ lọc FE: chỉ các loại nội dung đã có handler. */
    public Meta meta() {
        List<Option> types = access.handlers().stream()
                .map(h -> new Option(h.contentType(), CommunityContentType.label(h.contentType()))).toList();
        List<Option> reasons = Arrays.stream(ReportReason.values())
                .map(r -> new Option(r.name(), r.label())).toList();
        List<Option> sorts = Arrays.stream(Sort.values()).map(s -> new Option(s.param(), s.label)).toList();
        return new Meta(types, reasons, LEVELS, sorts, MAX_PAGE_SIZE);
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
