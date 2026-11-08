package com.jlas.jlptpath.dto;

import java.time.LocalDate;

/** [JLAS-W0108] One day of the stats chart; {@code correctPercent} is null when the day has no check answer. */
public record JpathStatsDayView(LocalDate day, int newLearned, int reviewed, Double correctPercent) {
}
