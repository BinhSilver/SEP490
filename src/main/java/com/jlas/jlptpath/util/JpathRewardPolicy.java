package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathTab;
import java.time.LocalDate;

/** [JLAS-W0108] CP amounts and reference types of the JLPT path rewards (policy #24, #25). */
public final class JpathRewardPolicy {

    public static final int TAB_DAY = 2;
    public static final int REVIEW_DAY = 1;
    public static final int DAY = 2;
    public static final int SKILL_DAY = 2;
    /** Plan 3 #44: a DAY skill session done (Reading or Listening), once per skill and day (ref id = epochDay). */
    public static final int SKILL_SESSION = 2;
    public static final int BADGE = 5;

    /**
     * Owner 25/11: CP is paid once, when the whole day is done, never piece by piece during the day. The lump keeps the
     * old total of a full day: study day = 3 tabs x TAB_DAY + REVIEW_DAY + DAY, skill day = 2 sessions + SKILL_DAY,
     * review-only day = REVIEW_DAY + DAY.
     */
    public static int dayTotal(com.jlas.jlptpath.enums.JpathDayKind kind) {
        if (kind == null) return 0;
        return switch (kind) {
            case SKILL -> 2 * SKILL_SESSION + SKILL_DAY;
            case REVIEW_ONLY -> REVIEW_DAY + DAY;
            case REST -> 0;
            default -> 3 * TAB_DAY + REVIEW_DAY + DAY;
        };
    }
    /** #44: passing the JLPT mock test of a level (ref id = mock attempt id). */
    public static final int MOCK_PASS = 20;

    public static final String REF_TAB_DAY = "JPATH_TAB_DAY";
    public static final String REF_REVIEW_DAY = "JPATH_REVIEW_DAY";
    public static final String REF_DAY = "JPATH_DAY";
    public static final String REF_SKILL_DAY = "JPATH_SKILL_DAY";
    public static final String REF_SKILL_READING = "JPATH_SKILL_READING";
    public static final String REF_SKILL_LISTENING = "JPATH_SKILL_LISTENING";
    public static final String REF_BADGE = "JPATH_BADGE";
    public static final String REF_MOCK_PASS = "JPATH_MOCK_PASS";
    /** Q5 (owner 24/11): a weekly check passed at 80%+, like the comm path (CPATH_WEEK 2 CP); once per tab and week. */
    public static final int WEEK_CHECK = 2;
    public static final String REF_WEEK_CHECK = "JPATH_WEEK_CHECK";

    private JpathRewardPolicy() {
    }

    /** Reference id of a weekly check: Monday of the week's epochDay × 10 + tab ordinal. */
    public static long weekCheckRef(LocalDate day, JpathTab tab) {
        return tabDayRef(day.with(java.time.DayOfWeek.MONDAY), tab);
    }

    /** Reference id of a tab day: epochDay × 10 + tab ordinal. */
    public static long tabDayRef(LocalDate day, JpathTab tab) {
        return day.toEpochDay() * 10 + tab.ordinal();
    }
}
