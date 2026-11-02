package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.ContentDetail;
import com.jlas.community.dto.CommunityDtos.RatingResult;
import com.jlas.community.entity.CommunityContent;
import com.jlas.community.entity.CommunityRating;
import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.enums.CommunityStatus;
import com.jlas.community.handler.CommunitySource;
import com.jlas.community.repository.CommunityContentRepository;
import com.jlas.community.repository.CommunityRatingRepository;
import com.jlas.community.service.CommunityAccessService.Target;
import com.jlas.moderation.service.UserActionPolicyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Chi tiết nội dung cộng đồng và đánh giá 1-5 sao. */
@Service
@RequiredArgsConstructor
public class CommunityContentService {
    private final CommunityAccessService access;
    private final CommunityContentRepository contents;
    private final CommunityRatingRepository ratings;
    private final UserActionPolicyPort actionPolicy;

    @Transactional(readOnly = true)
    public ContentDetail detail(String contentType, Long contentId, Long viewerId) {
        Target target = access.requireViewable(contentType, contentId, viewerId);
        CommunitySource source = target.source();
        CommunityContent row = target.row();
        boolean moderator = CommunityAccessService.isModerator();
        boolean owner = target.ownedBy(viewerId);
        boolean active = target.publicNow();
        Integer myRating = row == null || viewerId == null ? null
                : ratings.findByCommunityContentIdAndUserId(row.getId(), viewerId)
                .map(r -> r.getScore().intValue()).orElse(null);
        return new ContentDetail(source.contentType(), CommunityContentType.label(source.contentType()),
                source.contentId(), source.title(), source.description(), source.thumbnailUrl(),
                source.jlptLevel(), source.link(), access.author(source.ownerId()), source.createdAt(),
                source.extra(), target.status(), source.publicAtSource(),
                row == null ? 0 : row.getRatingAvg().doubleValue(),
                row == null ? 0 : row.getRatingCount(),
                row == null ? 0 : row.getCommentCount(),
                myRating, owner, moderator,
                active && !owner, active, active && !owner, notice(target, owner));
    }

    /** Upsert: đánh giá lại chỉ cập nhật điểm, không tạo bản ghi mới. Owner không tự đánh giá được. */
    @Transactional
    public RatingResult rate(String contentType, Long contentId, Long viewerId, int score) {
        if (score < 1 || score > 5) throw new ApiException(ErrorCode.BAD_REQUEST, "Điểm đánh giá phải từ 1 đến 5");
        actionPolicy.assertCanAct(viewerId);
        Target target = access.requireViewable(contentType, contentId, viewerId);
        if (target.ownedBy(viewerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không thể tự đánh giá nội dung của mình");
        }
        CommunityContent row = access.lockInteractable(target).row();
        CommunityRating rating = ratings.findByCommunityContentIdAndUserId(row.getId(), viewerId)
                .orElseGet(() -> CommunityRating.builder().communityContentId(row.getId()).userId(viewerId).build());
        rating.setScore((byte) score);
        ratings.saveAndFlush(rating);
        refreshRating(row);
        return new RatingResult(row.getRatingAvg().doubleValue(), row.getRatingCount(), score);
    }

    /** Gỡ đánh giá của chính mình; chưa đánh giá thì không làm gì. */
    @Transactional
    public RatingResult unrate(String contentType, Long contentId, Long viewerId) {
        Target target = access.requireViewable(contentType, contentId, viewerId);
        if (target.row() == null) return new RatingResult(0, 0, null);
        CommunityContent row = contents.lockById(target.row().getId()).orElseThrow(CommunityAccessService::notFound);
        ratings.findByCommunityContentIdAndUserId(row.getId(), viewerId).ifPresent(r -> {
            ratings.delete(r);
            ratings.flush();
        });
        refreshRating(row);
        return new RatingResult(row.getRatingAvg().doubleValue(), row.getRatingCount(), null);
    }

    /** Tính lại từ bảng community_rating khi đang giữ khoá dòng nội dung. */
    private void refreshRating(CommunityContent row) {
        long count = ratings.countByCommunityContentId(row.getId());
        Double avg = ratings.averageScore(row.getId());
        row.setRatingCount((int) count);
        row.setRatingAvg(BigDecimal.valueOf(count == 0 || avg == null ? 0 : avg).setScale(2, RoundingMode.HALF_UP));
        contents.save(row);
    }

    private static String notice(Target target, boolean owner) {
        String status = target.status();
        if (CommunityStatus.REMOVED.name().equals(status)) return "Nội dung đã bị kiểm duyệt viên gỡ khỏi cộng đồng";
        if (CommunityStatus.TEMPORARILY_HIDDEN.name().equals(status)) return "Nội dung đang tạm ẩn do bị báo cáo";
        if (!target.source().publicAtSource()) {
            return owner ? "Nội dung đang ở chế độ riêng tư nên không hiển thị trong cộng đồng"
                    : "Nội dung không công khai ở nguồn";
        }
        return null;
    }
}
