package com.jlas.shadowing.service;

import org.springframework.stereotype.Service;

/**
 * BR-03 — Unlock Rule cho Shadowing:
 * <ul>
 *   <li>User thường: 200 view đầu trong 24h miễn phí.</li>
 *   <li>View thứ 201+: trừ 5 Credit/lượt xem hoàn tất.</li>
 *   <li>Premium / ADMIN: unlimited.</li>
 * </ul>
 *
 * <p>Chưa gate thật. Creator không còn nhận credit theo từng view mà theo mốc view,
 * xem {@link ShadowingViewMilestoneService}.
 */
@Service
public class ShadowingUnlockService {

    /** Ngưỡng view miễn phí mỗi ngày (BR-03). */
    public static final int FREE_DAILY_VIEWS = 200;
    /** Credit trừ cho mỗi lượt unlock. */
    public static final int UNLOCK_CREDIT_COST = 5;
}
