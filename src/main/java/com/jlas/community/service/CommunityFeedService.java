package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.AuthorDto;
import com.jlas.community.dto.CommunityDtos.FeedItem;
import com.jlas.community.dto.CommunityDtos.Meta;
import com.jlas.community.dto.CommunityDtos.Option;
import com.jlas.community.dto.CommunityDtos.PageResult;
import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.enums.ReportReason;
import com.jlas.community.handler.CommunityContentTypeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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

    private final CommunityAccessService access;
    private final NamedParameterJdbcTemplate jdbc;

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

    public record Query(String keyword, String contentType, String jlptLevel, Double minRating, String sort,
                        Integer page, Integer size) {
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
            where.append(" AND COALESCE(cc.rating_avg, 0) >= :minRating");
            params.addValue("minRating", query.minRating());
        }

        String union = branches.stream().map(h -> "(" + h.feedSelectSql() + ")")
                .collect(Collectors.joining(" UNION ALL "));
        String from = " FROM (" + union + ") f LEFT JOIN community_content cc "
                + "ON cc.content_type = f.content_type AND cc.content_id = f.content_id";

        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, params, Long.class);
        long totalElements = total == null ? 0 : total;

        params.addValue("limit", size).addValue("offset", (long) page * size);
        String sql = "SELECT f.content_type, f.content_id, f.title, f.description, f.owner_id, f.thumbnail_url, "
                + "f.jlpt_level, f.created_at, f.link, COALESCE(cc.rating_avg, 0) AS rating_avg, "
                + "COALESCE(cc.rating_count, 0) AS rating_count, COALESCE(cc.comment_count, 0) AS comment_count, "
                + "u.full_name, u.username, u.avatar_url, my.score AS my_score"
                + from
                + " LEFT JOIN users u ON u.id = f.owner_id"
                + " LEFT JOIN community_rating my ON my.community_content_id = cc.id AND my.user_id = :viewerId"
                + where + " ORDER BY " + sort.orderBy + " LIMIT :limit OFFSET :offset";

        List<FeedItem> items = jdbc.query(sql, params, (rs, i) -> {
            Long ownerId = rs.getObject("owner_id") == null ? null : rs.getLong("owner_id");
            AuthorDto author = ownerId == null ? null : new AuthorDto(ownerId,
                    CommunityAccessService.displayName(ownerId, rs.getString("full_name"), rs.getString("username")),
                    rs.getString("avatar_url"));
            BigDecimal avg = rs.getBigDecimal("rating_avg");
            Timestamp created = rs.getTimestamp("created_at");
            Object myScore = rs.getObject("my_score");
            String type = rs.getString("content_type");
            return new FeedItem(type, CommunityContentType.label(type), rs.getLong("content_id"),
                    rs.getString("title"), rs.getString("description"), rs.getString("thumbnail_url"),
                    rs.getString("jlpt_level"), rs.getString("link"), author,
                    avg == null ? 0 : avg.doubleValue(), rs.getInt("rating_count"), rs.getInt("comment_count"),
                    myScore == null ? null : ((Number) myScore).intValue(),
                    created == null ? null : created.toLocalDateTime());
        });
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PageResult<>(items, page, size, totalElements, totalPages);
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
