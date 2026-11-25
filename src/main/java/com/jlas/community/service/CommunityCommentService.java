package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.AuthorDto;
import com.jlas.community.dto.CommunityDtos.CommentDto;
import com.jlas.community.dto.CommunityDtos.PageResult;
import com.jlas.community.entity.CommunityComment;
import com.jlas.community.entity.CommunityContent;
import com.jlas.community.enums.CommunityStatus;
import com.jlas.community.repository.CommunityCommentRepository;
import com.jlas.community.repository.CommunityContentRepository;
import com.jlas.community.service.CommunityAccessService.Target;
import com.jlas.moderation.service.UserActionPolicyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bình luận cộng đồng: đăng thẳng PUBLISHED (không qua duyệt), chỉ tác giả sửa, tác giả hoặc
 * MODERATOR/ADMIN xoá (xoá mềm). Người thường không thấy bình luận ẩn/đã gỡ, trừ bình luận ẩn của chính họ.
 */
@Service
@RequiredArgsConstructor
public class CommunityCommentService {
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 50;

    private final CommunityAccessService access;
    private final CommunityContentRepository contents;
    private final CommunityCommentRepository comments;
    private final UserActionPolicyPort actionPolicy;
    private final CommunityNotifier notifier;

    @Transactional(readOnly = true)
    public PageResult<CommentDto> list(String contentType, Long contentId, Long viewerId, Integer page, Integer size) {
        Target target = access.requireViewable(contentType, contentId, viewerId);
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        if (target.row() == null) return new PageResult<>(List.of(), p, s, 0, 0);
        boolean moderator = CommunityAccessService.isModerator();
        PageRequest pageable = PageRequest.of(p, s);
        Page<CommunityComment> result = moderator
                ? comments.findAllNotDeleted(target.row().getId(), pageable)
                : comments.findVisible(target.row().getId(), viewerId, pageable);
        Map<Long, AuthorDto> authors = access.authors(result.getContent().stream().map(CommunityComment::getUserId).toList());
        List<CommentDto> items = result.getContent().stream()
                .map(c -> toDto(c, authors.get(c.getUserId()), viewerId, moderator, target.publicNow())).toList();
        return new PageResult<>(items, p, s, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public CommentDto create(String contentType, Long contentId, Long viewerId, String body) {
        String text = clean(body);
        actionPolicy.assertCanAct(viewerId);
        Target target = access.lockInteractable(access.requireViewable(contentType, contentId, viewerId));
        CommunityContent row = target.row();
        CommunityComment comment = comments.saveAndFlush(CommunityComment.builder()
                .communityContentId(row.getId()).userId(viewerId).body(text)
                .status(CommunityStatus.PUBLISHED.name()).reportCount(0).build());
        access.refreshCommentCount(row);
        AuthorDto author = access.author(viewerId);
        notifier.newComment(target.source(), comment.getId(), viewerId, author.displayName());
        return toDto(comment, author, viewerId, CommunityAccessService.isModerator(), true);
    }

    @Transactional
    public CommentDto update(Long commentId, Long viewerId, String body) {
        String text = clean(body);
        actionPolicy.assertCanAct(viewerId);
        CommunityComment comment = access.fresh(comments.lockById(commentId)
                .orElseThrow(CommunityAccessService::notFound));
        if (comment.getDeletedAt() != null) throw CommunityAccessService.notFound();
        Target target = access.requireViewable(contentOf(comment), viewerId);
        boolean moderator = CommunityAccessService.isModerator();
        if (!Objects.equals(comment.getUserId(), viewerId)) {
            throw visibleTo(comment, viewerId, moderator)
                    ? new ApiException(ErrorCode.FORBIDDEN, "Bạn chỉ có thể sửa bình luận của mình")
                    : CommunityAccessService.notFound();
        }
        if (CommunityStatus.REMOVED.name().equals(comment.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Bình luận đã bị gỡ nên không thể sửa");
        }
        if (!target.publicNow()) {
            throw new ApiException(ErrorCode.CONFLICT, "Nội dung đang không công khai nên không thể sửa bình luận");
        }
        comment.setBody(text);
        // Chỉ sửa nội dung mới đổi updated_at (cờ "đã sửa"); báo cáo / gỡ / khôi phục giữ nguyên (xem CommunityComment).
        comment.setUpdatedAt(LocalDateTime.now());
        comments.saveAndFlush(comment);
        return toDto(comment, access.author(viewerId), viewerId, moderator, target.publicNow());
    }

    @Transactional
    public void delete(Long commentId, Long viewerId) {
        CommunityComment existing = comments.findById(commentId)
                .filter(c -> c.getDeletedAt() == null).orElseThrow(CommunityAccessService::notFound);
        // Khoá nội dung trước rồi tới bình luận (cùng thứ tự với báo cáo) để tránh deadlock; dùng bản đọc lại sau khi khoá.
        CommunityContent row = access.fresh(contents.lockById(existing.getCommunityContentId())
                .orElseThrow(CommunityAccessService::notFound));
        CommunityComment comment = access.fresh(comments.lockById(commentId)
                .orElseThrow(CommunityAccessService::notFound));
        if (comment.getDeletedAt() != null) throw CommunityAccessService.notFound();
        access.requireViewable(row, viewerId);
        boolean moderator = CommunityAccessService.isModerator();
        boolean author = Objects.equals(comment.getUserId(), viewerId);
        if (!author && !moderator) {
            throw visibleTo(comment, viewerId, false)
                    ? new ApiException(ErrorCode.FORBIDDEN, "Bạn chỉ có thể xoá bình luận của mình")
                    : CommunityAccessService.notFound();
        }
        comment.setDeletedAt(LocalDateTime.now());
        comments.saveAndFlush(comment);
        access.refreshCommentCount(row);
    }

    private CommunityContent contentOf(CommunityComment comment) {
        return contents.findById(comment.getCommunityContentId()).orElseThrow(CommunityAccessService::notFound);
    }

    private static boolean visibleTo(CommunityComment comment, Long viewerId, boolean moderator) {
        if (comment.getDeletedAt() != null) return false;
        if (moderator || Objects.equals(comment.getUserId(), viewerId)) return true;
        return CommunityStatus.PUBLISHED.name().equals(comment.getStatus());
    }

    private static String clean(String body) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Nội dung bình luận không được để trống");
        if (text.length() > CommunityComment.MAX_LENGTH) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Bình luận tối đa " + CommunityComment.MAX_LENGTH + " ký tự");
        }
        return text;
    }

    static CommentDto toDto(CommunityComment c, AuthorDto author, Long viewerId, boolean moderator,
                            boolean contentActive) {
        boolean mine = Objects.equals(c.getUserId(), viewerId);
        boolean published = CommunityStatus.PUBLISHED.name().equals(c.getStatus());
        boolean removed = CommunityStatus.REMOVED.name().equals(c.getStatus());
        boolean edited = c.getCreatedAt() != null && c.getUpdatedAt() != null
                && c.getUpdatedAt().isAfter(c.getCreatedAt().plusSeconds(1));
        AuthorDto shown = author != null ? author : new AuthorDto(c.getUserId(), "Người dùng #" + c.getUserId(), null);
        return new CommentDto(c.getId(), shown, c.getBody(), c.getStatus(), !published, mine,
                mine && !removed && contentActive, mine || moderator,
                !mine && published && contentActive, edited, c.getCreatedAt(), c.getUpdatedAt(),
                moderator && !removed);
    }
}
