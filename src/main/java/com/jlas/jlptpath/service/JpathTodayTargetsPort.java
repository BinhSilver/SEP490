package com.jlas.jlptpath.service;

import com.jlas.jlptpath.enums.JpathTab;
import java.util.Set;

/**
 * [JLAS-W0108] Re-applies the open level's sliders and study mode to today's plan after a learner changes them
 * (policy #29). Tabs of today's plan not started yet (no new, extra or review item done) take the new targets; started
 * tabs keep theirs and are returned, the change reaches them from tomorrow. Called while the caller holds the
 * {@code PESSIMISTIC_WRITE} lock on the open level row, so it must never create today's plan (preflight C10): with no
 * plan today it returns an empty set. Optional bean; without it nothing is refreshed.
 */
public interface JpathTodayTargetsPort {

    /** Tabs of today's plan that keep their targets because they were already started. */
    Set<JpathTab> refreshTodayTargets(Long userId);

    /**
     * After the week schedule changed (skill days, rest days, third skill day): today's plan that nothing was done on
     * yet (no item, review or reward, no open study session, no skill session) takes the kind of the new schedule, its
     * tabs rebuilt, when that kind is a study day. A rest or skill day moved onto today, or a started plan, keeps
     * today's kind; the change reaches it from tomorrow (F130). True when today changed.
     */
    default boolean replanUntouchedToday(Long userId) {
        return false;
    }
}
