package com.jlas.shadowing.service;

import com.jlas.moderation.credit.service.UserCreditPort;
import com.jlas.notification.service.UserNotificationPort;
import com.jlas.shadowing.model.ShadowingViewCounted;
import com.jlas.shadowing.repository.VideoViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Notifies creators about view milestones and pays credit from 1k views on. */
@Service
@RequiredArgsConstructor
public class ShadowingViewMilestoneService {

    /** Creator credit per milestone: base 10, then +5 for each later milestone. */
    static final Map<Integer, Integer> CREDIT_MILESTONES = new LinkedHashMap<>();
    static {
        CREDIT_MILESTONES.put(1_000, 10);
        CREDIT_MILESTONES.put(3_000, 15);
        CREDIT_MILESTONES.put(5_000, 20);
        CREDIT_MILESTONES.put(10_000, 25);
        CREDIT_MILESTONES.put(15_000, 30);
        CREDIT_MILESTONES.put(20_000, 35);
    }

    /** Early milestones only send a notification. */
    private static final int[] NOTICE_MILESTONES = {10, 50, 100, 500};

    private final VideoViewRepository views;
    private final UserCreditPort credits;
    private final UserNotificationPort notifications;

    /**
     * Pays every credit milestone the video has reached but not yet been paid for, so a milestone
     * missed by a failed reward is picked up on the next counted view.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rewardReachedMilestones(ShadowingViewCounted event) {
        if (event.ownerId() == null) return;
        for (int milestone : NOTICE_MILESTONES) {
            if (event.viewCount() == milestone) notifyOwner(event, milestone, 0);
        }
        if (event.viewCount() < CREDIT_MILESTONES.keySet().iterator().next()) return;

        Set<Integer> rewarded = views.rewardedMilestones(event.videoId());
        CREDIT_MILESTONES.forEach((milestone, credit) -> {
            if (event.viewCount() < milestone || rewarded.contains(milestone)) return;
            if (!views.recordMilestone(event.videoId(), milestone, event.ownerId(), credit)) return;
            credits.rewardCreator(event.ownerId(), credit, "SHADOWING_VIEW_" + milestone, event.videoId(),
                    "Video Shadowing đạt " + formatViews(milestone) + " lượt xem");
            notifyOwner(event, milestone, credit);
        });
    }

    private void notifyOwner(ShadowingViewCounted event, int milestone, int credit) {
        String message = "“" + event.title() + "” đã đạt " + formatViews(milestone) + " lượt xem."
                + (credit > 0 ? " Bạn nhận +" + credit + " credit." : "");
        notifications.notifyUser(event.ownerId(), "SHADOWING_VIEW_MILESTONE", "Bài Shadowing đạt mốc mới", message,
                "/shadowing/player/" + event.videoId(), "SHADOWING_VIEW_" + event.videoId() + "_" + milestone);
    }

    private static String formatViews(int views) {
        return String.format("%,d", views).replace(',', '.');
    }
}
