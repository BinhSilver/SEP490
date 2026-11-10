package com.jlas.mission.enums;

import com.jlas.common.event.LearningEventType;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import java.util.Arrays;
import java.util.Optional;

/**
 * Built-in missions. Rows in mission_definition are seeded from these defaults; the table is the
 * source of truth afterwards, so titles, targets and rewards can be tuned without a release.
 */
public enum MissionCode {
    DAILY_CHECK_IN("DAILY", "Điểm danh hôm nay", "Ghé KotsuKotsu và nhận thưởng điểm danh.",
            1, 5, 10, CreditReason.DAILY_CHECKIN, null),
    COMPLETE_LESSON("DAILY", "Hoàn thành một bài học", "Học xong trọn vẹn một bài học.",
            1, 10, 20, CreditReason.STUDY_REWARD, LearningEventType.LESSON_COMPLETED),
    COMPLETE_QUIZ("DAILY", "Làm một bài quiz", "Nộp một bài quiz của bài học.",
            1, 5, 30, CreditReason.QUIZ_REWARD, LearningEventType.LESSON_QUIZ_COMPLETED),
    PUBLISH_SHADOWING("DAILY", "Đăng một bài Shadowing", "Chia sẻ công khai một video Shadowing.",
            1, 10, 40, CreditReason.SHADOWING_REWARD, LearningEventType.SHADOWING_PUBLISHED),
    STREAK_3("STREAK", "Chuỗi 3 ngày", "Hoàn thành mục tiêu ngày 3 ngày liên tiếp.",
            3, 20, 110, CreditReason.STREAK_MILESTONE_REWARD, null),
    STREAK_7("STREAK", "Chuỗi 7 ngày", "Hoàn thành mục tiêu ngày 7 ngày liên tiếp.",
            7, 50, 120, CreditReason.STREAK_MILESTONE_REWARD, null),
    STREAK_15("STREAK", "Chuỗi 15 ngày", "Hoàn thành mục tiêu ngày 15 ngày liên tiếp.",
            15, 75, 125, CreditReason.STREAK_MILESTONE_REWARD, null),
    STREAK_30("STREAK", "Chuỗi 30 ngày", "Hoàn thành mục tiêu ngày 30 ngày liên tiếp.",
            30, 100, 130, CreditReason.STREAK_MILESTONE_REWARD, null);

    public static final String TYPE_DAILY = "DAILY";
    public static final String TYPE_STREAK = "STREAK";

    private final String type;
    private final String title;
    private final String description;
    private final int target;
    private final int reward;
    private final int displayOrder;
    private final CreditReason creditReason;
    private final LearningEventType trigger;

    MissionCode(String type, String title, String description, int target, int reward, int displayOrder,
                CreditReason creditReason, LearningEventType trigger) {
        this.type = type;
        this.title = title;
        this.description = description;
        this.target = target;
        this.reward = reward;
        this.displayOrder = displayOrder;
        this.creditReason = creditReason;
        this.trigger = trigger;
    }

    public String type() { return type; }
    public String title() { return title; }
    public String description() { return description; }
    public int target() { return target; }
    public int reward() { return reward; }
    public int displayOrder() { return displayOrder; }
    public CreditReason creditReason() { return creditReason; }

    /** The daily mission advanced by a learning event, if any. */
    public static Optional<MissionCode> triggeredBy(LearningEventType type) {
        return Arrays.stream(values()).filter(code -> code.trigger == type).findFirst();
    }

    public static Optional<MissionCode> parse(String code) {
        return Arrays.stream(values()).filter(value -> value.name().equals(code)).findFirst();
    }
}
