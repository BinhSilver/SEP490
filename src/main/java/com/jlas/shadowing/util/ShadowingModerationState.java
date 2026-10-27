package com.jlas.shadowing.util;

import java.util.Locale;

/**
 * Report-driven values of {@code video.moderation_status}, next to the AI verdicts
 * (SAFE / REVIEW / BLOCKED / NOT_SCANNED / ERROR).
 * <ul>
 *   <li>REPORTED: hidden from everyone but its owner until a moderator decides.</li>
 *   <li>REMOVED: taken down for good; no scan, edit or publish may bring it back.</li>
 * </ul>
 * Both are "locked": rescans and moderation resets must leave them alone.
 */
public final class ShadowingModerationState {
    public static final String REPORTED = "REPORTED";
    public static final String REMOVED = "REMOVED";

    private ShadowingModerationState() { }

    public static boolean isRemoved(String status) {
        return REMOVED.equalsIgnoreCase(status);
    }

    public static boolean isLocked(String status) {
        if (status == null) return false;
        String value = status.toUpperCase(Locale.ROOT);
        return REPORTED.equals(value) || REMOVED.equals(value);
    }
}
