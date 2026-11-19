package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.FeedItem;
import com.jlas.community.entity.CommunityContent;
import com.jlas.community.entity.CommunityPost;
import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.enums.CommunityStatus;
import com.jlas.community.repository.CommunityContentRepository;
import com.jlas.community.repository.CommunityPostRepository;
import com.jlas.community.service.CommunityAccessService.Target;
import com.jlas.moderation.service.UserActionPolicyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

/**
 * Bài viết cộng đồng (POST): người học đăng thẳng PUBLISHED, chỉ tác giả sửa/xoá (xoá mềm). Bình luận, báo cáo, tự
 * ẩn khi đủ báo cáo và hàng chờ kiểm duyệt đi qua cơ chế chung của Community (community_content là dòng trạng thái).
 */
@Service
@RequiredArgsConstructor
public class CommunityPostService {
    private final CommunityPostRepository posts;
    private final CommunityContentRepository contents;
    private final CommunityAccessService access;
    private final CommunityFeedService feed;
    private final UserActionPolicyPort actionPolicy;

    /** Đăng bài; trả về mục feed để FE chèn ngay đầu danh sách. */
    @Transactional
    public FeedItem create(Long viewerId, String body, String jlptLevel) {
        return create(viewerId, body, jlptLevel, null);
    }

    /** Đăng bài; {@code afterSave} (có thể null) chạy trong cùng transaction với id bài mới, vd. gắn ảnh/video. */
    @Transactional
    public FeedItem create(Long viewerId, String body, String jlptLevel, java.util.function.LongConsumer afterSave) {
        String text = clean(body);
        String level = level(jlptLevel);
        actionPolicy.assertCanAct(viewerId);
        CommunityPost post = posts.saveAndFlush(CommunityPost.builder()
                .authorId(viewerId).body(text).jlptLevel(level).build());
        // Dòng trạng thái tạo ngay khi đăng (bộ đếm = 0) để bình luận/báo cáo dùng chung cơ chế khoá dòng.
        contents.ensureRow(CommunityContentType.POST, post.getId());
        if (afterSave != null) afterSave.accept(post.getId());
        return feed.item(access.load(CommunityContentType.POST, post.getId()), viewerId);
    }

    @Transactional(readOnly = true)
    public FeedItem view(Long postId, Long viewerId) {
        return feed.item(access.requireViewable(CommunityContentType.POST, postId, viewerId), viewerId);
    }

    @Transactional
    public FeedItem update(Long postId, Long viewerId, String body, String jlptLevel) {
        String text = clean(body);
        String level = level(jlptLevel);
        actionPolicy.assertCanAct(viewerId);
        CommunityPost post = requireOwn(postId, viewerId, "sửa");
        Target target = access.load(CommunityContentType.POST, postId);
        if (CommunityStatus.REMOVED.name().equals(target.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Bài viết đã bị gỡ nên không thể sửa");
        }
        post.setBody(text);
        post.setJlptLevel(level);
        post.setEditedAt(LocalDateTime.now());
        posts.saveAndFlush(post);
        return feed.item(access.load(CommunityContentType.POST, postId), viewerId);
    }

    /**
     * Xoá mềm: bài biến mất khỏi feed và trang chi tiết (handler bỏ qua bài có deleted_at). Bài đang tạm ẩn chờ kiểm duyệt
     * (đủ báo cáo) thì chưa cho xoá: xoá xong hàng chờ mất nội dung lẫn người đăng, kiểm duyệt viên không xử lý / áp chế
     * tài được. Trạng thái đọc trên dòng community_content đã khoá + đọc lại (khoá bài trước rồi tới dòng nội dung).
     */
    @Transactional
    public void delete(Long postId, Long viewerId) {
        CommunityPost post = requireOwn(postId, viewerId, "xoá");
        String status = contents.lockBySource(CommunityContentType.POST, postId).map(access::fresh)
                .map(CommunityContent::getStatus).orElse(CommunityStatus.PUBLISHED.name());
        if (CommunityStatus.TEMPORARILY_HIDDEN.name().equals(status)) {
            throw new ApiException(ErrorCode.CONFLICT, "Bài viết đang tạm ẩn chờ kiểm duyệt nên chưa thể xoá");
        }
        post.setDeletedAt(LocalDateTime.now());
        posts.saveAndFlush(post);
    }

    /** Bài chưa xoá của chính người xem; người khác: 403 nếu họ xem được bài, 404 nếu không (không lộ bài ẩn). */
    private CommunityPost requireOwn(Long postId, Long viewerId, String verb) {
        CommunityPost post = posts.lockById(postId).map(access::fresh).filter(p -> p.getDeletedAt() == null)
                .orElseThrow(CommunityAccessService::notFound);
        if (!Objects.equals(post.getAuthorId(), viewerId)) {
            Target target = access.load(CommunityContentType.POST, postId);
            if (!access.canView(target, viewerId, CommunityAccessService.isModerator())) {
                throw CommunityAccessService.notFound();
            }
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn chỉ có thể " + verb + " bài viết của mình");
        }
        return post;
    }

    /** Giữ nguyên xuống dòng (chuẩn hoá về \n), bỏ khoảng trắng hai đầu; 1-5000 ký tự. */
    public static String clean(String body) {
        String text = body == null ? "" : body.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Nội dung bài viết không được để trống");
        if (text.length() > CommunityPost.MAX_LENGTH) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Bài viết tối đa " + CommunityPost.MAX_LENGTH + " ký tự");
        }
        return text;
    }

    /** Rỗng / "GENERAL" = Chung (null); còn lại phải là N5..N1. */
    static String level(String value) {
        if (!StringUtils.hasText(value) || "GENERAL".equalsIgnoreCase(value.trim())) return null;
        String level = value.trim().toUpperCase(Locale.ROOT);
        if (!CommunityFeedService.LEVELS.contains(level)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Cấp độ JLPT không hợp lệ");
        }
        return level;
    }
}
