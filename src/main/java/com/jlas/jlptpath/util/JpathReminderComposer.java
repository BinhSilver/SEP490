package com.jlas.jlptpath.util;

import com.jlas.jlptpath.dto.JpathReminderFacts;
import com.jlas.jlptpath.dto.JpathReminderMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * [JLAS-W0108] Builds the one combined study reminder of the day (policy #23): due cards, skill day, exam countdown
 * (only within 30 days) and the lower level still to learn, joined by " · " and ended with a full stop.
 */
public final class JpathReminderComposer {

    public static final String TITLE = "Đến giờ học tiếng Nhật rồi";
    static final String FALLBACK = "Phần học hôm nay đang chờ bạn.";
    static final int EXAM_COUNTDOWN_DAYS = 30;

    private JpathReminderComposer() {
    }

    public static JpathReminderMessage compose(JpathReminderFacts facts) {
        List<String> parts = new ArrayList<>();
        if (facts.dueCount() > 0) {
            parts.add(facts.dueCount() + " thẻ đang chờ ôn");
        }
        if (facts.skillDay()) {
            parts.add("Hôm nay là ngày luyện kỹ năng");
        }
        Integer days = facts.daysToExam();
        if (days != null && days == 0) {
            parts.add("Hôm nay là ngày thi");
        } else if (days != null && days > 0 && days <= EXAM_COUNTDOWN_DAYS) {
            parts.add("Còn " + days + " ngày tới kỳ thi");
        }
        if (facts.lowerLevelLeft() > 0 && facts.lowerLevel() != null) {
            parts.add("Còn " + facts.lowerLevelLeft() + " mục " + facts.lowerLevel() + " chưa học");
        }
        String message = parts.isEmpty() ? FALLBACK : String.join(" · ", parts) + ".";
        return new JpathReminderMessage(TITLE, message);
    }
}
