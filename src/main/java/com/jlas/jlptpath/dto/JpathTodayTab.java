package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathTab;

/**
 * [JLAS-W0108] One tab of today's plan. {@code newTarget} is the effective target (shrinks at the end of the level),
 * {@code reviewDue} the cards of the tab due now.
 */
public record JpathTodayTab(JpathTab tab, int newTarget, int newDone, int reviewDue, int reviewCap, int reviewDone,
                            boolean checkPassed, boolean done, boolean required) {
}
