package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathRating;

/**
 * [JLAS-W0108] One item the learner touched on a history day: its last action, the last rating given and the last
 * right/wrong result (null when the day has none).
 */
public record JpathDayItemView(JpathItemType itemType, String code, String label, String reading, String lastAction,
                               JpathRating lastRating, Boolean correct) {
}
