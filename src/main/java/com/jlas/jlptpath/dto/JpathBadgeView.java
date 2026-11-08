package com.jlas.jlptpath.dto;

import java.time.LocalDateTime;

/** [JLAS-W0108] One badge of the catalog for a learner: earned or not, and progress towards {@code goal}. */
public record JpathBadgeView(String code, String title, String description, boolean earned, LocalDateTime earnedAt,
                             int progress, int goal) {
}
