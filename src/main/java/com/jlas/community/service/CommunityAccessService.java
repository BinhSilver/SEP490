package com.jlas.community.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.security.CurrentUser;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.AuthorDto;
import com.jlas.community.entity.CommunityContent;
import com.jlas.community.enums.CommunityStatus;
import com.jlas.community.handler.CommunityContentTypeHandler;
import com.jlas.community.handler.CommunitySource;
import com.jlas.community.repository.CommunityCommentRepository;
import com.jlas.community.repository.CommunityContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Quy tắc xem và tương tác dùng chung cho mọi API Community (kiểm tra ở service, không dựa vào FE):
 * <ul>
 *   <li>Công khai (nguồn public + PUBLISHED): mọi user đã đăng nhập.</li>
 *   <li>Nguồn không public hoặc TEMPORARILY_HIDDEN: chỉ owner và MODERATOR/ADMIN.</li>
 *   <li>REMOVED: chỉ MODERATOR/ADMIN.</li>
 * </ul>
 * Không đủ quyền xem luôn trả 404 (không 403) để không lộ sự tồn tại của nội dung.
 */
@Service
@RequiredArgsConstructor
public class CommunityAccessService {
    static final String NOT_FOUND_MESSAGE = "Không tìm thấy nội dung";

    private final List<CommunityContentTypeHandler> handlers;
    private final CommunityContentRepository contents;
    private final CommunityCommentRepository comments;
    private final UserRepository users;

    /** Nội dung gốc + trạng thái cộng đồng ({@code row} null khi chưa có dòng community_content). */
    public record Target(CommunitySource source, CommunityContent row, String status) {
        public boolean publicNow() {
            return source.publicAtSource() && CommunityStatus.PUBLISHED.name().equals(status);
        }

        public boolean ownedBy(Long userId) {
            return userId != null && Objects.equals(source.ownerId(), userId);
        }

        Target withRow(CommunityContent locked) {
            return new Target(source, locked, locked.getStatus());
        }
    }

    public List<CommunityContentTypeHandler> handlers() {
        return handlers;
    }

    public Optional<CommunityContentTypeHandler> handler(String contentType) {
        if (!StringUtils.hasText(contentType)) return Optional.empty();
        return handlers.stream().filter(h -> h.supports(contentType)).findFirst();
    }

    public static boolean isModerator() {
        return CurrentUser.hasRole("ROLE_MODERATOR") || CurrentUser.hasRole("ROLE_ADMIN");
    }

    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
    }

    /** Nạp nội dung theo loại + id gốc; loại chưa có handler hoặc nguồn đã xoá thì 404. */
    public Target load(String contentType, Long contentId) {
        CommunityContentTypeHandler handler = handler(contentType).orElseThrow(CommunityAccessService::notFound);
        CommunitySource source = handler.resolve(contentId).orElseThrow(CommunityAccessService::notFound);
        CommunityContent row = contents.findByContentTypeAndContentId(handler.contentType(), contentId).orElse(null);
        return new Target(source, row, row == null ? CommunityStatus.PUBLISHED.name() : row.getStatus());
    }

    /** Nạp nội dung từ dòng community_content (dùng cho thao tác trên bình luận). */
    public Target load(CommunityContent row) {
        CommunityContentTypeHandler handler = handler(row.getContentType()).orElseThrow(CommunityAccessService::notFound);
        CommunitySource source = handler.resolve(row.getContentId()).orElseThrow(CommunityAccessService::notFound);
        return new Target(source, row, row.getStatus());
    }

    public boolean canView(Target target, Long viewerId, boolean moderator) {
        if (CommunityStatus.REMOVED.name().equals(target.status())) return moderator;
        if (target.publicNow()) return true;
        return moderator || target.ownedBy(viewerId);
    }

    public Target requireViewable(String contentType, Long contentId, Long viewerId) {
        Target target = load(contentType, contentId);
        if (!canView(target, viewerId, isModerator())) throw notFound();
        return target;
    }

    public Target requireViewable(CommunityContent row, Long viewerId) {
        Target target = load(row);
        if (!canView(target, viewerId, isModerator())) throw notFound();
        return target;
    }

    /**
     * Khoá dòng trạng thái (tạo nếu chưa có) và kiểm tra lại nội dung còn công khai. Mọi thay đổi bộ đếm đi qua
     * khoá này nên rating/comment/report đồng thời không làm lệch số liệu.
     */
    public Target lockInteractable(Target target) {
        CommunitySource source = target.source();
        contents.ensureRow(source.contentType(), source.contentId());
        CommunityContent locked = contents.lockBySource(source.contentType(), source.contentId())
                .orElseThrow(CommunityAccessService::notFound);
        Target fresh = target.withRow(locked);
        if (!fresh.publicNow()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Nội dung đang không công khai nên không thể đánh giá, bình luận hoặc báo cáo");
        }
        return fresh;
    }

    /** Đếm lại bình luận PUBLISHED chưa xoá; gọi khi đang giữ khoá dòng community_content. */
    public void refreshCommentCount(CommunityContent row) {
        long count = comments.countByCommunityContentIdAndStatusAndDeletedAtIsNull(row.getId(),
                CommunityStatus.PUBLISHED.name());
        row.setCommentCount((int) count);
        contents.save(row);
    }

    public Map<Long, AuthorDto> authors(Collection<Long> userIds) {
        Map<Long, AuthorDto> result = new HashMap<>();
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return result;
        for (User user : users.findAllById(ids)) result.put(user.getId(), author(user));
        return result;
    }

    public AuthorDto author(Long userId) {
        if (userId == null) return null;
        return users.findById(userId).map(CommunityAccessService::author)
                .orElse(new AuthorDto(userId, "Người dùng #" + userId, null));
    }

    public static AuthorDto author(User user) {
        return new AuthorDto(user.getId(), displayName(user.getId(), user.getFullName(), user.getUsername()),
                user.getAvatarUrl());
    }

    static String displayName(Long id, String fullName, String username) {
        if (StringUtils.hasText(fullName)) return fullName.trim();
        if (StringUtils.hasText(username)) return username.trim();
        return "Người dùng #" + id;
    }
}
