package com.jlas.shadowing.service;

import com.jlas.moderation.credit.service.UserCreditPort;
import com.jlas.shadowing.model.Video;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * BR-03 — Unlock Rule cho Shadowing:
 * <ul>
 *   <li>User thường: 200 view đầu trong 24h miễn phí.</li>
 *   <li>View thứ 201+: trừ 5 Credit/lượt xem hoàn tất.</li>
 *   <li>Premium / SUPER_ADMIN / ADMIN: unlimited.</li>
 *   <li>Creator nhận +2 Credit cho mỗi view hoàn tất từ user khác.</li>
 * </ul>
 *
 * <p>Counter đếm theo ngày (UTC) lưu trong Redis hoặc DB. Ở scope turn này tôi
 * giữ đơn giản: đếm theo {@code UserCreditTransaction} record trong 24h — tức
 * "đã trừ credit cho video X" không tính lại. Để tăng tốc production nên dùng
 * Redis sau.
 */
@Service
@RequiredArgsConstructor
public class ShadowingUnlockService {

    /** Ngưỡng view miễn phí mỗi ngày (BR-03). */
    public static final int FREE_DAILY_VIEWS = 200;
    /** Credit trừ cho mỗi lượt unlock. */
    public static final int UNLOCK_CREDIT_COST = 5;
    /** Creator reward cho mỗi lượt xem hoàn tất. */
    public static final int CREATOR_REWARD_PER_VIEW = 2;

    private final UserCreditPort creditService;

    /**
     * Sau khi 1 view hoàn tất (≥80% duration), gọi method này để cộng credit
     * cho creator và (optional) check quota nếu BE muốn gate trước.
     */
    public void onCompletedView(Video video, Long viewerUserId) {
        if (video.getOwnerId() == null
                || video.getOwnerId().equals(viewerUserId)) {
            // Creator xem video của chính mình  không nhận reward.
            return;
        }
        // +2 cho creator
        try {
            creditService.rewardCreator(video.getOwnerId(), CREATOR_REWARD_PER_VIEW,
                    "VIDEO", video.getId(),
                    "Creator reward: video viewed by user " + viewerUserId);
        } catch (RuntimeException ex) {
            // Không làm fail flow chính vì lỗi credit
            org.slf4j.LoggerFactory.getLogger(ShadowingUnlockService.class)
                    .warn("Creator reward failed for video {} owner {}: {}",
                            video.getId(), video.getOwnerId(), ex.getMessage());
        }
    }
}
