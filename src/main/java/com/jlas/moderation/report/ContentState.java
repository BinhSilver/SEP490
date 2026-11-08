package com.jlas.moderation.report;

/**
 * Community content states from the JLAS2-37 report/state contract.
 * Moderation only consumes TEMPORARILY_HIDDEN and moves it to REMOVED or PUBLISHED.
 */
public final class ContentState {
    public static final String PUBLISHED = "PUBLISHED";
    public static final String TEMPORARILY_HIDDEN = "TEMPORARILY_HIDDEN";
    public static final String REMOVED = "REMOVED";

    private ContentState() { }
}
