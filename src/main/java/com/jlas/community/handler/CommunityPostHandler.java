package com.jlas.community.handler;

import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.repository.CommunityPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * POST: bài viết người học tự đăng (bảng community_post). Không có tiêu đề nên "title" là đoạn đầu của bài (dùng
 * cho thông báo, hàng chờ báo cáo, tìm kiếm); "description" là toàn bộ nội dung. Bài chưa xoá luôn công khai ở
 * nguồn, việc ẩn/gỡ do trạng thái cộng đồng (báo cáo) quyết định. Không có đánh giá sao.
 * Order 0: "Bài viết" đứng đầu bộ lọc loại nội dung.
 */
@Component
@Order(0)
@RequiredArgsConstructor
public class CommunityPostHandler implements CommunityContentTypeHandler {
    /** Độ dài đoạn trích làm "tiêu đề". */
    public static final int TITLE_LENGTH = 80;

    private final CommunityPostRepository posts;

    @Override
    public String contentType() {
        return CommunityContentType.POST;
    }

    @Override
    public Optional<CommunitySource> resolve(Long contentId) {
        if (contentId == null) return Optional.empty();
        return posts.findById(contentId).filter(p -> p.getDeletedAt() == null).map(p -> {
            Map<String, Object> extra = new HashMap<>();
            extra.put("edited", p.getEditedAt() != null);
            return new CommunitySource(contentType(), p.getId(), excerpt(p.getBody()), p.getBody(), p.getAuthorId(),
                    null, CommunityContentType.pageUrl(contentType(), p.getId()), p.getJlptLevel(),
                    p.getCreatedAt(), true, extra, null);
        });
    }

    @Override
    public String feedSelectSql() {
        return "SELECT '" + CommunityContentType.POST + "' AS content_type, p.id AS content_id, "
                + "LEFT(p.body, " + TITLE_LENGTH + ") AS title, p.body AS description, p.author_id AS owner_id, "
                + "NULL AS thumbnail_url, p.jlpt_level AS jlpt_level, p.created_at AS created_at, "
                + "CONCAT('/community/posts/', p.id) AS link, NULL AS ext_rating_avg, NULL AS ext_rating_count, "
                + "NULL AS ext_my_rating FROM community_post p WHERE p.deleted_at IS NULL";
    }

    @Override
    public boolean supportsRating() {
        return false;
    }

    /** Tác giả thấy bài của mình đã bị gỡ (kèm lý do); người khác 404. */
    @Override
    public boolean ownerSeesRemoved() {
        return true;
    }

    /** Một dòng, tối đa TITLE_LENGTH ký tự, dùng thay tiêu đề. */
    public static String excerpt(String body) {
        return cut(body == null ? "" : body.replaceAll("\\s+", " ").trim(), TITLE_LENGTH);
    }

    /**
     * Cắt tối đa {@code max} đơn vị UTF-16 rồi thêm "…", không bao giờ cắt đôi một ký tự ngoài BMP (emoji, chữ Hán hiếm
     * như 𠮟): nửa cặp surrogate lẻ bị driver ghi thành '?' và trình duyệt hiện thành �.
     */
    public static String cut(String text, int max) {
        if (text.length() <= max) return text;
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end) + "…";
    }
}
