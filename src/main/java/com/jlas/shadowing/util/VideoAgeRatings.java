package com.jlas.shadowing.util;

import java.util.Set;

/** Nhãn độ tuổi của video Shadowing (video.min_age): 13+, 16+ hoặc 18+; null = mọi lứa tuổi. */
public final class VideoAgeRatings {
    public static final Set<Integer> LEVELS = Set.of(13, 16, 18);

    private VideoAgeRatings() {
    }

    /** Nhãn người dùng chọn: null hoặc 0 = mọi lứa tuổi; ngoài ra chỉ nhận {@link #LEVELS}. */
    public static Integer parse(Integer requested) {
        if (requested == null || requested == 0) return null;
        if (!LEVELS.contains(requested)) {
            throw new IllegalArgumentException("Nhãn độ tuổi chỉ nhận 13+, 16+ hoặc 18+.");
        }
        return requested;
    }

    /** Nhãn cao nhất không vượt {@code age_limit} của YouTube (hiện chỉ có 0 hoặc 18); dưới 13 = không nhãn. */
    public static Integer fromYoutubeAgeLimit(int ageLimit) {
        Integer rating = null;
        for (int level : LEVELS) {
            if (level <= ageLimit && (rating == null || level > rating)) rating = level;
        }
        return rating;
    }

    /** Nhãn chặt hơn trong hai nhãn: người đăng không hạ được giới hạn mà YouTube đã đặt. */
    public static Integer stricter(Integer first, Integer second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.max(first, second);
    }
}
