package com.jlas.jlptpath.service;

/** [JLAS-W0108] Where {@code /learning} sends a learner (plan decision 11). */
public enum JpathEntryRoute {
    /** The learner has an ACTIVE JLPT path: show the path page. */
    JPATH_PAGE,
    /** JLPT goal but no ACTIVE path (none, paused or archived only): go to the entry flow. */
    JPATH_START,
    /** Communication goal (BA, TRAVEL, DAILY_LIFE) with {@code learning.comm-path.enabled}: the communication path. */
    COMM,
    /** Flag off, no survey or another goal: keep the old learning path. */
    LEGACY
}
