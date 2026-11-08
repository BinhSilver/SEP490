package com.jlas.jlptpath.enums;

/**
 * [JLAS-W0108] Internal review status of content; never shown to learners. Learners see REVIEWED and AI_CHECKED
 * (DRAFT only in draft preview), never HOLD.
 */
public enum JpathContentStatus {
    REVIEWED, AI_CHECKED, DRAFT, HOLD
}
