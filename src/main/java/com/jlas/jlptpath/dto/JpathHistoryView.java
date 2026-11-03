package com.jlas.jlptpath.dto;

import java.time.LocalDate;
import java.util.List;

/** [JLAS-W0108] History of {@code [from, to]}: the days with activity or a plan, newest first. */
public record JpathHistoryView(LocalDate from, LocalDate to, List<JpathHistoryDayView> days) {
}
