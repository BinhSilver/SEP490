package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathTab;
import java.time.LocalDate;

/** [JLAS-W0108] CP amounts and reference types of the JLPT path rewards (policy #24, #25). */
public final class JpathRewardPolicy {

    public static final int TAB_DAY = 2;
    public static final int REVIEW_DAY = 1;
    public static final int DAY = 2;
    public static final int SKILL_DAY = 2;
    public static final int BADGE = 5;

    public static final String REF_TAB_DAY = "JPATH_TAB_DAY";
    public static final String REF_REVIEW_DAY = "JPATH_REVIEW_DAY";
    public static final String REF_DAY = "JPATH_DAY";
    public static final String REF_SKILL_DAY = "JPATH_SKILL_DAY";
    public static final String REF_BADGE = "JPATH_BADGE";

    private JpathRewardPolicy() {
    }

    /** Reference id of a tab day: epochDay × 10 + tab ordinal. */
    public static long tabDayRef(LocalDate day, JpathTab tab) {
        return day.toEpochDay() * 10 + tab.ordinal();
    }
}
